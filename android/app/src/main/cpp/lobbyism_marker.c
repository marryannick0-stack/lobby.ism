// JNI bridge between Kotlin (camera/MarkerNative.kt) and the AprilTag library.
//
// The detector works directly on the camera's grey (Y) plane: no copy, no
// conversion. Frames are never stored; only the detected poses leave this file.
#include <jni.h>
#include <string.h>
#include "apriltag.h"
#include "apriltag_pose.h"
#include "tag36h11.h"

/** One detector instance. The handle passed to Kotlin is a pointer to this. */
typedef struct {
    apriltag_detector_t *detector;
    apriltag_family_t *family;
} marker_ctx;

/** Doubles written per detection, see MarkerNative.kt for the layout. */
#define VALUES_PER_DETECTION 14

JNIEXPORT jlong JNICALL
Java_com_lobbyism_app_camera_MarkerNative_create(JNIEnv *env, jclass cls, jfloat decimate, jint threads) {
    (void) env; (void) cls;
    marker_ctx *ctx = (marker_ctx *) calloc(1, sizeof(marker_ctx));
    if (!ctx) return 0;
    ctx->family = tag36h11_create();
    ctx->detector = apriltag_detector_create();
    if (!ctx->family || !ctx->detector) {
        if (ctx->detector) apriltag_detector_destroy(ctx->detector);
        if (ctx->family) tag36h11_destroy(ctx->family);
        free(ctx);
        return 0;
    }
    // Only one bit error corrected: fewer false detections on posters, books, tiles.
    apriltag_detector_add_family_bits(ctx->detector, ctx->family, 1);
    ctx->detector->quad_decimate = decimate;
    ctx->detector->nthreads = threads;
    ctx->detector->refine_edges = 1;
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_com_lobbyism_app_camera_MarkerNative_destroy(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    marker_ctx *ctx = (marker_ctx *) (intptr_t) handle;
    if (!ctx) return;
    apriltag_detector_destroy(ctx->detector);
    tag36h11_destroy(ctx->family);
    free(ctx);
}

/**
 * Detects markers in one grey image and estimates their pose.
 *
 * Writes per detection: id, decision margin, R (3x3 row-major), t (3) into `out`.
 * R and t map tag coordinates into camera coordinates (x right, y down, z forward).
 * @return number of detections written (at most out.length / 14)
 */
JNIEXPORT jint JNICALL
Java_com_lobbyism_app_camera_MarkerNative_detect(
    JNIEnv *env, jclass cls, jlong handle, jobject grey, jint width, jint height, jint stride,
    jdouble fx, jdouble fy, jdouble cx, jdouble cy, jdouble tag_size, jdoubleArray out) {
    (void) cls;
    marker_ctx *ctx = (marker_ctx *) (intptr_t) handle;
    if (!ctx) return 0;
    uint8_t *pixels = (uint8_t *) (*env)->GetDirectBufferAddress(env, grey);
    if (!pixels) return 0;

    image_u8_t image = {.width = width, .height = height, .stride = stride, .buf = pixels};
    zarray_t *detections = apriltag_detector_detect(ctx->detector, &image);

    jsize capacity = (*env)->GetArrayLength(env, out) / VALUES_PER_DETECTION;
    jdouble *dst = (*env)->GetDoubleArrayElements(env, out, NULL);
    int written = 0;
    for (int i = 0; i < zarray_size(detections) && written < capacity; i++) {
        apriltag_detection_t *det;
        zarray_get(detections, i, &det);
        apriltag_detection_info_t info = {det, tag_size, fx, fy, cx, cy};
        apriltag_pose_t pose;
        estimate_tag_pose(&info, &pose);

        jdouble *d = dst + written * VALUES_PER_DETECTION;
        d[0] = det->id;
        d[1] = det->decision_margin;
        memcpy(d + 2, pose.R->data, 9 * sizeof(double));
        memcpy(d + 11, pose.t->data, 3 * sizeof(double));
        matd_destroy(pose.R);
        matd_destroy(pose.t);
        written++;
    }
    (*env)->ReleaseDoubleArrayElements(env, out, dst, 0);
    apriltag_detections_destroy(detections);
    return written;
}
