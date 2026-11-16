// SPDX-License-Identifier: GPL-2.0-or-later
package com.vrlightgunhub.vcop2;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class GameActivity extends SDLActivity implements SurfaceHolder.Callback {
    private static final String TAG = "GameActivity";
    private static final String PREFS_NAME = "vcop2_flat_prefs";
    private static final String PREF_TOUCH_MODE = "touch_mode"; // 0 = tap-to-shoot, 1 = aim+fire

    private FlatTouchOverlay mTouchOverlay;
    private Button mBtnFire;
    private Button mBtnReload;
    private Button mBtnStart;
    private Button mBtnTest;
    private Button mBtnService;
    private Button mBtnModeToggle;
    private int mTouchMode = 0; // default: tap-to-shoot
    private AlertDialog mRomErrorDialog;
    private final List<String> mMissingLibraries = new ArrayList<>();

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "SDL2",
            "vcop2_xr",
            "main"
        };
    }

    @Override
    public void loadLibraries() {
        mMissingLibraries.clear();
        for (String lib : getLibraries()) {
            try {
                System.loadLibrary(lib);
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "Failed to load library " + lib + ": " + e.getMessage());
                mMissingLibraries.add("lib" + lib + ".so");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to load library " + lib + ": " + t.getMessage());
                mMissingLibraries.add("lib" + lib + ".so");
            }
        }
        if (!mMissingLibraries.isEmpty()) {
            mBrokenLibraries = true;
        }
    }

    @Override
    protected String[] getArguments() {
        File filesDir = getFilesDir();
        File nvramDir = new File(filesDir, "nvram");
        File cfgDir = new File(filesDir, "cfg");
        File stateDir = new File(filesDir, "state");
        File snapshotDir = new File(filesDir, "snapshot");

        nvramDir.mkdirs();
        cfgDir.mkdirs();
        stateDir.mkdirs();
        snapshotDir.mkdirs();

        String romPath = GameFiles.getRomPath();

        return new String[] {
            "vcop2",
            "-rompath", romPath,
            "-nvram_directory", nvramDir.getAbsolutePath(),
            "-cfg_directory", cfgDir.getAbsolutePath(),
            "-state_directory", stateDir.getAbsolutePath(),
            "-snapshot_directory", snapshotDir.getAbsolutePath(),
            "-video", "none",
            "-sound", "sdl",
            "-skip_gameinfo",
            "-noreadconfig",
            "-nomouse",
            "-numprocessors", "2",
            "-throttle"
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Load native libraries first before super.onCreate
        loadLibraries();

        try {
            super.onCreate(savedInstanceState);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Native method call failed in super.onCreate: " + e.getMessage());
            mBrokenLibraries = true;
            if (!mMissingLibraries.contains("libSDL2.so")) {
                mMissingLibraries.add("libSDL2.so");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Initialization error in super.onCreate: " + t.getMessage());
            mBrokenLibraries = true;
        }

        // Check if any native libraries failed to load
        if (!mMissingLibraries.isEmpty() || mBrokenLibraries) {
            mBrokenLibraries = true;
            StringBuilder missingStr = new StringBuilder();
            for (String lib : mMissingLibraries) {
                if (missingStr.length() > 0) missingStr.append(", ");
                missingStr.append(lib);
            }
            if (missingStr.length() == 0) missingStr.append("libSDL2.so, libmain.so");
            Log.e(TAG, "Missing native libraries: " + missingStr);
            mRomErrorDialog = new AlertDialog.Builder(this)
                .setTitle("Native Libraries Required")
                .setMessage("The following native libraries were not found in the APK:\n\n" + missingStr + "\n\nPlease compile MAME and SDL2 libraries according to docs/BUILD.md and place them in jniLibs/arm64-v8a/.")
                .setPositiveButton("OK", (dialog, which) -> {
                    dialog.dismiss();
                    finish();
                })
                .setCancelable(false)
                .show();
            return;
        }

        // Set environment variables required by engine & host before SDLActivity initializes native libraries
        File filesDir = getFilesDir();
        String nativeLibDir = getApplicationInfo().nativeLibraryDir;
        String xrLibPath = new File(nativeLibDir, "libvcop2_xr.so").getAbsolutePath();
        String settingsPath = new File(filesDir, "vcop2-settings.ini").getAbsolutePath();
        String logPath = new File(filesDir, "vcop2-log").getAbsolutePath();

        setEnv("VCOP2_VR", "1");
        setEnv("VCOP2_XR_LIBRARY", xrLibPath);
        setEnv("VCOP2_SETTINGS", settingsPath);
        setEnv("VCOP2_XR_LOG", logPath);
        setEnv("VCOP2_ASSETS", filesDir.getAbsolutePath());

        // Validate ROMs before continuing
        GameFiles.ValidationResult romCheck = GameFiles.validateRoms();
        if (!romCheck.valid) {
            mBrokenLibraries = true;
            Log.e(TAG, "ROM validation failed: " + romCheck.errorMessage);
            mRomErrorDialog = new AlertDialog.Builder(this)
                .setTitle("ROM Files Required")
                .setMessage(romCheck.errorMessage)
                .setPositiveButton("OK", (dialog, which) -> {
                    dialog.dismiss();
                    finish();
                })
                .setCancelable(false)
                .show();
            return;
        }

        // Load persisted touch mode preference
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mTouchMode = prefs.getInt(PREF_TOUCH_MODE, 0);
        FlatHostJNI.nativeSetTouchMode(mTouchMode);

        setupUIOverlays();
    }

    private void setEnv(String key, String value) {
        try {
            OsEnvHelper.setenv(key, value, true);
        } catch (Throwable t) {
            Log.w(TAG, "Could not set environment variable " + key + "=" + value, t);
        }
    }

    private void setupUIOverlays() {
        // Inflate or create the flat overlay UI over the main layout
        View rootView = getWindow().getDecorView().findViewById(android.R.id.content);
        if (rootView instanceof ViewGroup) {
            ViewGroup layout = (ViewGroup) rootView;

            // Touch overlay to handle direct touch forwarding to game
            mTouchOverlay = new FlatTouchOverlay(this);
            layout.addView(mTouchOverlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));

            // Create HUD control buttons
            setupControlButtons(layout);
        }
    }

    private void setupControlButtons(ViewGroup container) {
        // Overlay Layout Container
        View overlayLayout = getLayoutInflater().inflate(R.layout.activity_game, container, false);
        container.addView(overlayLayout);

        mBtnReload = overlayLayout.findViewById(R.id.btn_reload);
        mBtnFire = overlayLayout.findViewById(R.id.btn_fire);
        mBtnStart = overlayLayout.findViewById(R.id.btn_start);
        mBtnTest = overlayLayout.findViewById(R.id.btn_test);
        mBtnService = overlayLayout.findViewById(R.id.btn_service);
        mBtnModeToggle = overlayLayout.findViewById(R.id.btn_mode_toggle);

        // Wire UI buttons (consume touches so they don't fire gun)
        wireButton(mBtnReload, 1);  // 1 = RELOAD
        wireButton(mBtnFire, 0);    // 0 = FIRE
        wireButton(mBtnStart, 2);   // 2 = START
        wireButton(mBtnTest, 3);    // 3 = TEST
        wireButton(mBtnService, 4); // 4 = SERVICE

        // Update FIRE button visibility according to touch mode (0 = tap-to-shoot, 1 = aim+fire)
        updateTouchModeUI();

        if (mBtnModeToggle != null) {
            mBtnModeToggle.setOnClickListener(v -> {
                mTouchMode = (mTouchMode == 0) ? 1 : 0;
                FlatHostJNI.nativeSetTouchMode(mTouchMode);
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(PREF_TOUCH_MODE, mTouchMode)
                    .apply();
                updateTouchModeUI();
                Toast.makeText(this, mTouchMode == 1 ? "Aim + Fire Mode" : "Tap-to-Shoot Mode", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void updateTouchModeUI() {
        if (mBtnFire != null) {
            mBtnFire.setVisibility(mTouchMode == 1 ? View.VISIBLE : View.GONE);
        }
        if (mBtnModeToggle != null) {
            mBtnModeToggle.setText(mTouchMode == 1 ? "Aim+Fire" : "Tap-Shoot");
        }
    }

    private void wireButton(View button, final int buttonId) {
        if (button == null) return;
        button.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                FlatHostJNI.nativeSendButton(buttonId, 1);
                v.setPressed(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                FlatHostJNI.nativeSendButton(buttonId, 0);
                v.setPressed(false);
            }
            return true; // Consume touch so it never reaches game surface
        });
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (holder.getSurface() != null) {
            int w = holder.getSurfaceFrame().width();
            int h = holder.getSurfaceFrame().height();
            FlatHostJNI.nativeSetSurface(holder.getSurface(), w, h);
            if (mTouchOverlay != null) {
                mTouchOverlay.setSurfaceSize(w, h);
            }
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (holder.getSurface() != null) {
            FlatHostJNI.nativeSetSurface(holder.getSurface(), width, height);
            if (mTouchOverlay != null) {
                mTouchOverlay.setSurfaceSize(width, height);
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        FlatHostJNI.nativeClearSurface();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!mBrokenLibraries) {
            FlatHostJNI.nativeSetFocused(hasFocus ? 1 : 0);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!mBrokenLibraries) {
            FlatHostJNI.nativeSetMenuOpen(1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mBrokenLibraries) {
            FlatHostJNI.nativeSetMenuOpen(0);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mRomErrorDialog != null && mRomErrorDialog.isShowing()) {
            mRomErrorDialog.dismiss();
            mRomErrorDialog = null;
        }
        super.onDestroy();
    }

    // Native environment helper fallback
    private static class OsEnvHelper {
        static {
            System.loadLibrary("c");
        }
        public static void setenv(String name, String value, boolean overwrite) {
            try {
                android.system.Os.setenv(name, value, overwrite);
            } catch (Throwable t) {
                Log.w(TAG, "Os.setenv failed", t);
            }
        }
    }
}
