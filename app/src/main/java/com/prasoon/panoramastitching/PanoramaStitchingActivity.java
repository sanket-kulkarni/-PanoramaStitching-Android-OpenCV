package com.prasoon.panoramastitching;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.io.FileOutputStream;
import java.io.IOException;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PanoramaStitchingActivity extends AppCompatActivity {

    static {
        System.loadLibrary("MyLibs");
        System.loadLibrary("opencv_java4");
    }

    private TextView mTextViewJni;
    private Button captureBtn, saveBtn, recordBtn;
    private PreviewView viewFinder; // Replaces mSurfaceView
    private SurfaceView mSurfaceViewOnTop;
    private ImageCapture imageCapture; // CameraX ImageCapture use case
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private ExecutorService cameraExecutor;

    private boolean safeToTakePicture = true;
    ProgressDialog ringProgressDialog;
    List<Mat> listImage = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // PreviewView for CameraX
        viewFinder = findViewById(R.id.viewFinder);
        
        // Overlay SurfaceView
        mSurfaceViewOnTop = findViewById(R.id.surfaceViewOnTop);
        mSurfaceViewOnTop.setZOrderOnTop(true);
        mSurfaceViewOnTop.getHolder().setFormat(PixelFormat.TRANSPARENT);

        captureBtn = findViewById(R.id.capture);
        saveBtn = findViewById(R.id.save);
        recordBtn = findViewById(R.id.record);

        // Start CameraX
        startCamera();
        
        cameraExecutor = Executors.newSingleThreadExecutor();

        captureBtn.setOnClickListener(v -> {
            if (safeToTakePicture) {
                takePhoto();
            }
        });

        saveBtn.setOnClickListener(v -> {
            Thread thread = new Thread(imageProcessingRunnable);
            thread.start();
        });

        recordBtn.setOnClickListener(v -> captureVideo());
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                // Used to bind the lifecycle of cameras to the lifecycle owner
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Preview
                Preview preview = new Preview.Builder()
                        .build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // ImageCapture
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                // VideoCapture
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                // Select back camera as a default
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // Unbind use cases before rebinding
                cameraProvider.unbindAll();

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, videoCapture);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("Panorama", "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        safeToTakePicture = false;

        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                processCapturedImage(image);
                image.close(); // Make sure to close the image!
                safeToTakePicture = true;
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e("Panorama", "Photo capture failed: " + exception.getMessage(), exception);
                safeToTakePicture = true;
            }
        });
    }

    private void captureVideo() {
        if (videoCapture == null) {
            return;
        }

        if (recording != null) {
            // Stop recording
            recording.stop();
            recording = null;
            return;
        }

        // Create MediaStoreOutputOptions to save directly to Pictures
        String name = "CameraX-Record-" + System.currentTimeMillis();
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);

        MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(
                getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build();

        // Start recording
        // Audio is disabled by default if not requested.
        recording = videoCapture.getOutput()
                .prepareRecording(this, options)
                .start(ContextCompat.getMainExecutor(this), recordEvent -> {
                    if (recordEvent instanceof VideoRecordEvent.Start) {
                        recordBtn.setText("Stop");
                        recordBtn.setEnabled(true);
                    } else if (recordEvent instanceof VideoRecordEvent.Finalize) {
                         VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) recordEvent;
                        if (!finalizeEvent.hasError()) {
                            String msg = "Video saved to: " + finalizeEvent.getOutputResults().getOutputUri();
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                            Log.d("Panorama", msg);
                            
                            // Extract frames
                            extractFrames(finalizeEvent.getOutputResults().getOutputUri());
                        } else {
                            if (recording != null) {
                                recording.close(); 
                                recording = null;
                            }
                            Log.e("Panorama", "Video capture failed: " + finalizeEvent.getError());
                            Toast.makeText(this, "Video capture failed", Toast.LENGTH_SHORT).show();
                        }
                        recordBtn.setText("Record");
                        recordBtn.setEnabled(true);
                    }
                });
    }

    private void extractFrames(Uri videoUri) {
        cameraExecutor.execute(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, videoUri);
                String time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = Long.parseLong(time);
                
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "frames");
                if (!dir.exists()) {
                    dir.mkdirs();
                }

                runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Extracting Video Frames...", Toast.LENGTH_SHORT).show());
                
                int count = 0;
                for (long i = 0; i < durationMs; i += 1000) {
                    Bitmap frame = retriever.getFrameAtTime(i * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame != null) {
                        File file = new File(dir, "frame_" + System.currentTimeMillis() + "_" + count + ".png");
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            frame.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                        processFrames(frame);
                        count++;
                    }
                }
                
                 final int finalCount = count;
                 runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Extracted " + finalCount + " frames.", Toast.LENGTH_SHORT).show());
                 Log.d("Panorama", "Extracted " + finalCount + " frames.");

            } catch (Exception e) {
                Log.e("Panorama", "Error extracting frames", e);
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Error extracting frames", Toast.LENGTH_SHORT).show());
            } finally {
                try {
                    retriever.release();
                } catch (IOException e) {
                    e.printStackTrace();
                    // Ignore
                }
            }
        });
    }

    private void processCapturedImage(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

        // Handle rotation
        // CameraX images might be rotated. We should rotate them to match display or requirements.
        // The old code forced 90 degrees.
        // Let's use the image's rotation info.
        int rotationDegrees = image.getImageInfo().getRotationDegrees();
        // Or if the user code specifically wanted 90, we can add 90?
        // Let's stick to rotationDegrees which makes it upright.
        
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                bitmap.getHeight(), matrix, false);
        
        // Add to list for OpenCV (convert to Mat)
        Mat mat = new Mat();
        Bitmap bmp32 = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Utils.bitmapToMat(bmp32, mat);
        listImage.add(mat);

        // Update Overlay on Main Thread
        updateOverlay(rotatedBitmap);
    }

    private void processFrames(Bitmap rotatedBitmap) {
        // Handle rotation
        // CameraX images might be rotated. We should rotate them to match display or requirements.
        // The old code forced 90 degrees.
        // Let's use the image's rotation info.
//        int rotationDegrees = image.getImageInfo().getRotationDegrees();
        // Or if the user code specifically wanted 90, we can add 90?
        // Let's stick to rotationDegrees which makes it upright.

//        Matrix matrix = new Matrix();
//        matrix.postRotate(rotationDegrees);
//        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
//                bitmap.getHeight(), matrix, false);

        // Add to list for OpenCV (convert to Mat)
        Mat mat = new Mat();
        Bitmap bmp32 = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Utils.bitmapToMat(bmp32, mat);
        listImage.add(mat);

        // Update Overlay on Main Thread
        //  updateOverlay(rotatedBitmap);
    }
    
    private void updateOverlay(Bitmap bitmap) {
         Canvas canvas = null;
         try {
             canvas = mSurfaceViewOnTop.getHolder().lockCanvas(null);
             synchronized (mSurfaceViewOnTop.getHolder()) {
                 // Clear canvas
                 canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

                 // Scale the image to fit the SurfaceView width
                 int viewWidth = viewFinder.getWidth();
                 
                 float scale = 1.0f * viewWidth / bitmap.getWidth();
                 Bitmap scaleImage = Bitmap.createScaledBitmap(bitmap,
                         viewWidth, (int) (scale * bitmap.getHeight()), false);

                 Paint paint = new Paint();
                 // Set the opacity of the image
                 paint.setAlpha(200);

                 // Draw the image with an offset so we only see one third of the image from top.
                 canvas.drawBitmap(scaleImage, 0, -scaleImage.getHeight() * 2 / 3, paint);
             }
         } catch (Exception e) {
             e.printStackTrace();
         } finally {
             if (canvas != null) {
                 mSurfaceViewOnTop.getHolder().unlockCanvasAndPost(canvas);
             }
         }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
    
    // --- Existing Save/Process Logic ---
    
    Runnable imageProcessingRunnable = new Runnable() {
        @Override
        public void run() {
            showProcessingDialog();
            try {
                // Create a long array to store all image address
                int elems = listImage.size();
                Log.i("Panorama", "elems " + elems);

                long[] tempobjadr = new long[elems];
                for (int i = 0; i < elems; i++) {
                    tempobjadr[i] = listImage.get(i).getNativeObjAddr();
                    Log.i("Panorama", "tempobjadr[" + i + "] " + tempobjadr[i]);
                }

                // Create a Mat to store the final panorama image
                Mat result = new Mat();
                Log.i("Panorama", "processPanorama started ");
                long start = System.currentTimeMillis();
                // Call the OpenCV C++ code to perform stitching process
                int ret = NativePanorama.processPanorama(tempobjadr, result.getNativeObjAddr());
                Log.i("Panorama", "processPanorama completed  in "+((System.currentTimeMillis() - start)/1000)+" seconds");
                if (ret == 0) {
                    Log.i("Panorama", "ret " + ret);
                } else {
                    Log.i("Panorama", "ret " + ret);
                }
                
                // Save the image to external storage
                String directoryName = Environment.DIRECTORY_PICTURES;
                File sdcard = Environment.getExternalStoragePublicDirectory(directoryName);

                sdcard.mkdirs();
                final String fileName = sdcard + "/openCV_" + System.currentTimeMillis() + ".png";
                try {
                    boolean bool = Imgcodecs.imwrite(fileName, result);
                    if (bool)
                        Log.i("Panorama", "SUCCESS writing image to external storage" + fileName);
                    else
                        Log.i("Panorama", "Fail writing image to external storage" + fileName);

                } catch (Exception e) {
                    Log.i("Panorama", "Fail writing image to external storage111" + fileName);
                    e.printStackTrace();
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (ret == 1) { // Wait, old code said if ret == 0 log it, but here it checks ret == 1 for success toast? 
                            // Re-checking old code: 
                            // if(ret==0) Log... else Log...
                            // runOnUiThread: if (ret == 1) Toast Success else Fail.
                            // Assuming NativePanorama returns 1 on success.
                            Toast.makeText(getApplicationContext(), "File saved at: " + fileName, Toast.LENGTH_LONG).show();
                        } else
                            Toast.makeText(getApplicationContext(), "File NOT saved. ", Toast.LENGTH_LONG).show();

                    }
                });
                listImage.clear();
            } catch (Exception e) {
                e.printStackTrace();
            }
            closeProcessingDialog();
        }

        private void closeProcessingDialog() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // mCam.startPreview(); // CameraX stays active usually, or we can unbind/rebind if needed.
                    // But CameraX preview doesn't stop automatically on other operations unless we tell it.
                    // We don't need to restart preview explicitly if we didn't stop it.
                    // The stopping in showProcessingDialog implies we should maintain that behavior if possible.
                    // But for simplicity, let's just dismiss dialog.
                    // If we want to freeze preview, we unbind preview.
                    ringProgressDialog.dismiss();
                }
            });
        }

        private void showProcessingDialog() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // mCam.stopPreview(); 
                    // To mimic stopPreview, we could unbind Preview, but leaving it running is often better UX unless resources are tight.
                    // Let's leave it running for now as it's simpler.
                    ringProgressDialog = ProgressDialog.show(PanoramaStitchingActivity.this, "", "Panorama", true);
                    ringProgressDialog.setCancelable(false);
                }
            });
        }
    };
}