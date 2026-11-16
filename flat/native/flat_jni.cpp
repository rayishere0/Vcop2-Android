// SPDX-License-Identifier: GPL-2.0-or-later
// JNI bridge implementation connecting Android Java layer to flat host C API.

#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>

extern "C" {
void vcop2_flat_pointer(int id, int action, float x, float y, int surface_w, int surface_h);
void vcop2_flat_button(int button, int pressed);
void vcop2_flat_set_touch_mode(int mode);
void vcop2_flat_set_surface(void* native_window, int w, int h);
void vcop2_flat_clear_surface();
void vcop2_flat_set_focused(int focused);
void vcop2_flat_set_menu_open(int open);
void vcop2_flat_request_quit();
}

static ANativeWindow* g_native_window = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_vrlightgunhub_vcop2_FlatHostJNI_nativeSetSurface(JNIEnv* env, jclass clazz, jobject surface, jint width, jint height) {
    if (g_native_window) {
        ANativeWindow_release(g_native_window);
        g_native_window = nullptr;
    }
    if (surface) {
        g_native_window = ANativeWindow_fromSurface(env, surface);
        if (g_native_window) {
            vcop2_flat_set_surface(g_native_window, width, height);
        }
    } else {
        vcop2_flat_clear_surface();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrlightgunhub_vcop2_FlatHostJNI_nativeClearSurface(JNIEnv* env, jclass clazz) {
    vcop2_flat_clear_surface();
    if (g_native_window) {
        ANativeWindow_release(g_native_window);
        g_native_window = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrlightgunhub_vcop2_FlatHostJNI_nativeSendPointer(JNIEnv* env, jclass clazz, jint id, jint action, jfloat x, jfloat y, jint sw, jint sh) {
    vcop2_flat_pointer(id, action, x, y, sw, sh);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrlightgunhub_vcop2_FlatHostJNI_nativeSendButton(JNIEnv* env, jclass clazz, jint button, jint pressed) {
    vcop2_flat_button(button, pressed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrlightgunhub_vcop2_FlatHostJNI_nativeSetTouchMode(JNIEnv* env, jclass clazz, jint mode) {
    vcop2_flat_set_touch_mode(mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrlightgunhub_vcop2_FlatHostJNI_nativeSetFocused(JNIEnv* env, jclass clazz, jint focused) {
    vcop2_flat_set_focused(focused);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrlightgunhub_vcop2_FlatHostJNI_nativeSetMenuOpen(JNIEnv* env, jclass clazz, jint open) {
    vcop2_flat_set_menu_open(open);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrlightgunhub_vcop2_FlatHostJNI_nativeRequestQuit(JNIEnv* env, jclass clazz) {
    vcop2_flat_request_quit();
}
