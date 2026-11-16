// SPDX-License-Identifier: GPL-2.0-or-later
package com.vrlightgunhub.vcop2;

import android.view.Surface;

/**
 * JNI bindings for the flat (non-VR) host native C API (libvcop2_xr.so).
 */
public class FlatHostJNI {
    static {
        System.loadLibrary("vcop2_xr");
    }

    public static native void nativeSetSurface(Surface surface, int width, int height);
    public static native void nativeClearSurface();
    public static native void nativeSendPointer(int id, int action, float x, float y, int surfaceWidth, int surfaceHeight);
    public static native void nativeSendButton(int button, int pressed);
    public static native void nativeSetTouchMode(int mode);
    public static native void nativeSetFocused(int focused);
    public static native void nativeSetMenuOpen(int open);
    public static native void nativeRequestQuit();
}
