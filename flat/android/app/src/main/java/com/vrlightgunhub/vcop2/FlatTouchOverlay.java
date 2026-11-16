// SPDX-License-Identifier: GPL-2.0-or-later
package com.vrlightgunhub.vcop2;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Touch overlay handling touch forwarding and gesture recognition to the flat host.
 */
public class FlatTouchOverlay extends View {
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private boolean mTwoFingerReloadActive = false;

    public FlatTouchOverlay(Context context) {
        super(context);
        init();
    }

    public FlatTouchOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FlatTouchOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setSurfaceSize(int width, int height) {
        mSurfaceWidth = width;
        mSurfaceHeight = height;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mSurfaceWidth = w;
        mSurfaceHeight = h;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mSurfaceWidth <= 0 || mSurfaceHeight <= 0) {
            mSurfaceWidth = getWidth();
            mSurfaceHeight = getHeight();
        }

        final int actionMasked = event.getActionMasked();
        final int pointerCount = event.getPointerCount();

        // Two-finger tap reload gesture recognition
        if (pointerCount == 2 && actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            mTwoFingerReloadActive = true;
            FlatHostJNI.nativeSendButton(1, 1); // RELOAD press
        } else if (mTwoFingerReloadActive && (actionMasked == MotionEvent.ACTION_POINTER_UP || actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL)) {
            mTwoFingerReloadActive = false;
            FlatHostJNI.nativeSendButton(1, 0); // RELOAD release
        }

        // Pointer forwarding
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                int id = event.getPointerId(index);
                float x = event.getX(index);
                float y = event.getY(index);
                FlatHostJNI.nativeSendPointer(id, 0, x, y, mSurfaceWidth, mSurfaceHeight);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < pointerCount; i++) {
                    int id = event.getPointerId(i);
                    float x = event.getX(i);
                    float y = event.getY(i);
                    FlatHostJNI.nativeSendPointer(id, 1, x, y, mSurfaceWidth, mSurfaceHeight);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                int id = event.getPointerId(index);
                float x = event.getX(index);
                float y = event.getY(index);
                FlatHostJNI.nativeSendPointer(id, 2, x, y, mSurfaceWidth, mSurfaceHeight);
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                FlatHostJNI.nativeSendPointer(0, 3, 0, 0, mSurfaceWidth, mSurfaceHeight);
                break;
            }
        }
        return true;
    }
}
