#include "com_prasoon_panoramastitching_NativePanorama.h"
#include "opencv2/opencv.hpp"
#include "opencv2/stitching.hpp"
#include <vector>
#include <android/log.h>

using namespace cv;
using std::vector;

JNIEXPORT jint JNICALL Java_com_prasoon_panoramastitching_NativePanorama_processPanorama
  (JNIEnv *env, jclass clazz, jlongArray imageAddressArray, jlong outputAddress){
  jint ret=1;
  // Get the length of the long array
  jsize a_len = env->GetArrayLength(imageAddressArray);
  // Convert the jlongArray to an array of jlong
  jlong *imgAddressArr = env->GetLongArrayElements(imageAddressArray, 0);
  // Create a vector to store all the image
  vector<Mat> imgVec;

    for(int k=0;k<a_len;k++){
        // Get the image
        Mat & curimage=*(Mat*)imgAddressArr[k];
        Mat newimage;

        // Convert to a 3 channel Mat to use with Stitcher module
        cvtColor(curimage, newimage, COLOR_RGBA2RGB);

        // Rotate image 90 degrees counter-clockwise for vertical stitching logic
        // This makes the vertical panorama appear as a horizontal one to the stitcher
        rotate(newimage, newimage, ROTATE_90_COUNTERCLOCKWISE);

        // Reduce the resolution for fast computation
        // Scale based on height (which corresponds to the original width of the vertical strip)
        float scale = 1000.0f / newimage.rows;
        resize(newimage, newimage, Size(), scale, scale);

        // Check for duplicates
        bool isDuplicate = false;
        for(size_t i=0; i<imgVec.size(); i++) {
             Mat diff;
             cv::compare(newimage, imgVec[i], diff, cv::CMP_NE);
             // cntNonZero only works on single channel matrices. Reshape to 1 channel.
             diff = diff.reshape(1);
             if (cv::countNonZero(diff) == 0) {
                 isDuplicate = true;
                 break;
             }
        }

        if (isDuplicate) {
            continue;
        }

        imgVec.push_back(newimage);
      }
    // Example: Logging a debug message
    __android_log_print(ANDROID_LOG_DEBUG, "Panorama", "Debug message: imgVec size is %d",a_len );

    Mat & result  = *(Mat*) outputAddress;
    
    if (imgVec.empty()) {
          env->ReleaseLongArrayElements(imageAddressArray, imgAddressArr ,0);
          return -1;
    }

    if (imgVec.size() == 1) {
        result = imgVec[0];
        rotate(result, result, ROTATE_90_CLOCKWISE);
        cv::cvtColor(result, result, cv::COLOR_BGR2RGBA);
        env->ReleaseLongArrayElements(imageAddressArray, imgAddressArr ,0);
        return 1;
    }
    __android_log_print(ANDROID_LOG_DEBUG, "Panorama", "Debug message: Stitcher object created");
    bool try_gpu = true;
    // 1. Create the stitcher in SCANS mode (best for flat receipts)
//    cv::Ptr<cv::Stitcher> stitcher = cv::Stitcher::create(cv::Stitcher::SCANS);
    cv::Ptr<cv::Stitcher> stitcher = cv::Stitcher::create(cv::Stitcher::PANORAMA);

    // 2. Customize ORB parameters for better text detection
// nfeatures: Increase from 500 to 1500+ for dense text on receipts
// scaleFactor & nlevels: Standard pyramid parameters
    cv::Ptr<cv::Feature2D> orbFinder = cv::ORB::create(3000, 1.2f, 8);

// 3. Set the custom finder to the stitcher
    stitcher->setFeaturesFinder(orbFinder);

    stitcher->setRegistrationResol(0.3);
    stitcher->setSeamEstimationResol(0.1);
    stitcher->setCompositingResol(-1);
    stitcher->setPanoConfidenceThresh(0.2);
    stitcher->setWaveCorrection(false);
//    stitcher->setWaveCorrectKind(detail::WAVE_CORRECT_HORIZ);
    __android_log_print(ANDROID_LOG_DEBUG, "Panorama", "Debug message: Stitching process started");
    Stitcher::Status status = stitcher->stitch(imgVec, result);
    __android_log_print(ANDROID_LOG_DEBUG, "Panorama", "Debug message: Stitching process completed, status is  %d",status);

    if (status != Stitcher::OK){
        ret= (jint)status;
    } else {
        rotate(result, result, ROTATE_90_CLOCKWISE);
        cv::cvtColor(result, result, cv::COLOR_BGR2RGBA);
    }

  // Release the jlong array
  env->ReleaseLongArrayElements(imageAddressArray, imgAddressArr ,0);
  return ret;

}


JNIEXPORT jstring JNICALL Java_com_prasoon_panoramastitching_NativePanorama_getMessageFromJni
  (JNIEnv *env, jclass obj){
  return env->NewStringUTF("This is a message from JNI");
  }