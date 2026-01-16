#include "com_prasoon_panoramastitching_NativePanorama.h"
#include "opencv2/opencv.hpp"
#include "opencv2/stitching.hpp"

using namespace std;
using namespace cv;

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

        imgVec.push_back(newimage);
      }

    Mat & result  = *(Mat*) outputAddress;

    Ptr<Stitcher> stitcher = Stitcher::create(Stitcher::PANORAMA);

    stitcher->setRegistrationResol(-1);
    stitcher->setSeamEstimationResol(-1);
    stitcher->setCompositingResol(-1);
    stitcher->setPanoConfidenceThresh(-1);
    stitcher->setWaveCorrection(true);
    stitcher->setWaveCorrectKind(detail::WAVE_CORRECT_HORIZ);

    Stitcher::Status status = stitcher->stitch(imgVec, result);

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