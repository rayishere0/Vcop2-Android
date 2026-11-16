// SPDX-License-Identifier: GPL-2.0-or-later
// Flat (non-VR) host for the Virtua Cop 2 Android touch port.
//
// This file replaces the OpenXR host (upstream native/xr_host.cpp) in the
// flat build. It preserves the engine-side contract byte-for-byte: the same
// three exported symbols, the same SharedRuntime layout/ABI, the same
// env-var names, and the same GLScene capture-replay renderer, driven as a
// single flat view in panel mode with the native aspect preserved.
//
// NOT compile-checked and NOT device-tested. The contract evidence, the
// view-projection derivation, and the UNKNOWN list live in
// docs/FLAT-PORT.md. Touch and surface input arrives through the plain C
// API at the bottom of this file; the Java/JNI glue that calls it is NOT
// part of this deliverable (see docs/ANDROID-GAP.md).
#include "shared_runtime.h"
#include "gl_scene.h"
#include "gl_context.h"
#include "settings_io.h"
#include "startup_policy.h"
#include "model2_scene.h"
#include <glad/gl.h>
#include <glm/glm.hpp>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#if defined(__ANDROID__)
#include <android/log.h>
#include <jni.h>
#endif
#if !defined(_WIN32)
#include <EGL/egl.h>
#endif

#if defined(_WIN32)
#define FLAT_EXPORT extern "C" __declspec(dllexport)
#else
#define FLAT_EXPORT extern "C" __attribute__((visibility("default")))
#endif

namespace vcop2 {
namespace {

// ---------------------------------------------------------------- logging
std::mutex g_log_mutex;
std::ofstream g_log_file;
bool g_log_opened{};

void flat_log(const std::string& message) {
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "vcop2-flat", "%s", message.c_str());
#endif
    std::lock_guard lock(g_log_mutex);
    if (!g_log_opened) {
        g_log_opened = true;
        if (const char* path = std::getenv("VCOP2_XR_LOG"))
            g_log_file.open(path, std::ios::app);
    }
    if (g_log_file) g_log_file << message << '\n' << std::flush;
    else std::fprintf(stderr, "vcop2-flat: %s\n", message.c_str());
}

// ------------------------------------------------------- touch/button state
// Written by the C API (any thread, e.g. a JNI thread); snapshotted by the
// worker once per frame. Coordinates are surface pixels, origin top-left.
struct PointerState {
    float x{}, y{};
    bool down{};
};
struct TouchState {
    std::map<int, PointerState> pointers;
    int aim_pointer{-1};
    float last_x{}, last_y{};
    bool has_point{};
    bool fire_held{}, reload_held{}, start_held{};
    bool test_held{}, service_held{};
    int touch_mode{};  // 0 = tap-to-shoot, 1 = aim+fire
    bool focused{true}, menu_open{};
    void* window{};
    int surface_w{}, surface_h{};
};
std::mutex g_touch_mutex;
TouchState g_touch;

struct TouchSnapshot {
    float last_x{}, last_y{};
    bool has_point{}, aim_down{};
    bool fire_held{}, reload_held{}, start_held{};
    bool test_held{}, service_held{};
    int touch_mode{};
    bool focused{}, menu_open{};
    void* window{};
    int surface_w{}, surface_h{};
};

TouchSnapshot snapshot_touch() {
    std::lock_guard lock(g_touch_mutex);
    TouchSnapshot snap;
    snap.last_x = g_touch.last_x;
    snap.last_y = g_touch.last_y;
    snap.has_point = g_touch.has_point;
    snap.aim_down = g_touch.aim_pointer >= 0 &&
        g_touch.pointers.count(g_touch.aim_pointer) &&
        g_touch.pointers.at(g_touch.aim_pointer).down;
    snap.fire_held = g_touch.fire_held;
    snap.reload_held = g_touch.reload_held;
    snap.start_held = g_touch.start_held;
    snap.test_held = g_touch.test_held;
    snap.service_held = g_touch.service_held;
    snap.touch_mode = g_touch.touch_mode;
    snap.focused = g_touch.focused;
    snap.menu_open = g_touch.menu_open;
    snap.window = g_touch.window;
    snap.surface_w = g_touch.surface_w;
    snap.surface_h = g_touch.surface_h;
    return snap;
}

// ------------------------------------------------------------ view geometry
struct ViewRect {
    int x{}, y{}, w{}, h{};
    bool valid{};
};

// Pillarbox/letterbox rect preserving the NATIVE frame aspect (from the
// live frame, never a hardcoded 4:3 or 496x384). See docs/FLAT-PORT.md.
ViewRect pillarbox_rect(int sw, int sh, int fw, int fh) {
    ViewRect rect;
    if (sw <= 0 || sh <= 0 || fw <= 0 || fh <= 0) return rect;
    const double native_aspect = double(fw) / double(fh);
    const double surface_aspect = double(sw) / double(sh);
    rect.valid = true;
    if (surface_aspect > native_aspect) {
        rect.h = sh;
        rect.w = int(std::lround(sh * native_aspect));
        rect.x = (sw - rect.w) / 2;
        rect.y = 0;
    } else {
        rect.w = sw;
        rect.h = int(std::lround(sw / native_aspect));
        rect.x = 0;
        rect.y = (sh - rect.h) / 2;
    }
    return rect;
}

// Infinite-far-plane asymmetric frustum built from a NativeLens, matching
// the panel-mode vertex placement (unproject native pixel through the lens).
// Verified algebra (see docs/FLAT-PORT.md): NDC_x = (2*px - W) / W and
// NDC_y = (H - 2*py) / H for any panel distance/scale.
glm::mat4 lens_frustum(const NativeLens& lens) {
    const float left = float(-lens.cx / lens.fx);
    const float right = float((lens.width - lens.cx) / lens.fx);
    const float bottom = float((lens.cy - lens.height) / lens.fy);
    const float top = float(lens.cy / lens.fy);
    const float near_plane = 0.02f;
    glm::mat4 m(0);
    m[0][0] = 2.f / (right - left);
    m[1][1] = 2.f / (top - bottom);
    m[2][0] = (right + left) / (right - left);
    m[2][1] = (top + bottom) / (top - bottom);
    m[2][2] = -1.f;
    m[2][3] = -1.f;
    m[3][2] = -2.f * near_plane;
    return m;
}

// ------------------------------------------------------------ worker state
SharedRuntime* g_shared{};
std::thread g_worker;
std::atomic<bool> g_running{}, g_stopping{};
#if defined(__ANDROID__)
JavaVM* g_vm{};
jobject g_activity_global{};
#endif

double monotonic_now() {
    return std::chrono::duration<double>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

void worker_main(SharedRuntime* shared) {
#if defined(__ANDROID__)
    JNIEnv* env{};
    bool attached = false;
    if (g_vm) {
        if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) ==
            JNI_EDETACHED) {
            attached = g_vm->AttachCurrentThread(&env, nullptr) == 0;
            if (!attached) env = nullptr;
        }
        if (!env) {
            flat_log("flat host: JVM attach failed; failing startup");
            shared->host_ready = false;
            shared->quit_requested = true;
            return;
        }
    }
#endif
    try {
        // Settings: same file/format as upstream. Flat Phase 1 forces the
        // conservative presentation (no camera widening, panel mode) while
        // keeping persisted reload/hand/scale/distance tuning.
        const char* settings_path = std::getenv("VCOP2_SETTINGS");
        Settings settings =
            load_settings(settings_path ? settings_path : "vcop2-settings.ini");
        settings.wider_view = false;
        // SharedRuntime::reset() leaves camera_fov at 110 (the VR default);
        // the flat host must clear it before the first frame or patch 0009
        // widens every camera write.
        shared->camera_fov.store(0.f);

        InputPolicy policy;
        std::unique_ptr<GLContext> context;
        std::unique_ptr<GLScene> scene;
        void* current_window = nullptr;
        bool ready_announced = false;
        bool was_focused = true;
        bool prev_test = false, prev_service = false;
        ViewRect rect;

        auto teardown_gl = [&]() {
            // GLScene holds GL objects: destroy it before its context.
            scene.reset();
            context.reset();
            current_window = nullptr;
            ready_announced = false;
        };

        while (!g_stopping.load()) {
            const TouchSnapshot touch = snapshot_touch();
            if (!touch.focused && was_focused) policy.suspend();
            was_focused = touch.focused;

            // ---- surface / GL lifecycle (rebuild on window change) ----
            if (touch.window != current_window) {
                teardown_gl();
                if (touch.window) {
                    try {
                        context = std::make_unique<GLContext>(touch.window);
#if !defined(_WIN32)
                        // Pacing assumption (UNKNOWN, see docs): pace the
                        // loop on vsync through the swap interval.
                        eglSwapInterval(context->display(), 1);
#endif
                        // A context must be current before GLScene compiles
                        // its shaders; GLContext's constructor makes it so.
                        scene = std::make_unique<GLScene>();
                        scene->set_immersive(false);
                        scene->set_view_boundary(false, false);
                        current_window = touch.window;
                        flat_log("flat host: GL context ready");
                    } catch (const std::exception& e) {
                        flat_log(std::string("flat host: GL init failed: ") +
                                 e.what());
                        teardown_gl();
                        std::this_thread::sleep_for(
                            std::chrono::milliseconds(250));
                    }
                }
            }
            if (context && scene && !ready_announced) {
                shared->host_ready = true;
                ready_announced = true;
            }

            // ---- input: synthesize one persistent P1 hand ----
            // tracked stays true and aim stays at the last point even with
            // no finger down; flapping tracked would eat the first tap
            // after every lift (InputPolicy blocked_ discipline).
            GameState state;
            {
                std::lock_guard lock(shared->mutex);
                state = shared->state;
            }
            const ViewRect map_rect =
                rect.valid ? rect
                           : ViewRect{0, 0, touch.surface_w, touch.surface_h,
                                      touch.surface_w > 0 && touch.surface_h > 0};
            double u = 0.5, v = 0.5;
            bool inside = false;
            if (touch.has_point && map_rect.valid && map_rect.w > 0 &&
                map_rect.h > 0) {
                u = double(touch.last_x - map_rect.x) / double(map_rect.w);
                v = double(touch.last_y - map_rect.y) / double(map_rect.h);
                inside = touch.last_x >= map_rect.x &&
                    touch.last_x < map_rect.x + map_rect.w &&
                    touch.last_y >= map_rect.y &&
                    touch.last_y < map_rect.y + map_rect.h;
            }
            Hands hands{};
            hands[0].tracked = true;
            hands[0].aim = Aim{u, v, true, inside};
            const bool fire = touch.touch_mode == 1
                ? touch.fire_held
                : touch.aim_down;
            hands[0].trigger = fire;
            hands[0].pump = touch.reload_held;
            hands[0].start = touch.start_held;
            const CabinetInput input = policy.update(
                monotonic_now(), hands, state, settings, touch.focused,
                touch.menu_open);
            {
                std::lock_guard lock(shared->mutex);
                shared->input = input;
            }
            // Operator buttons: rising edges OR the latched bits consumed
            // by system_input() (4 = Test, 8 = Service advance).
            if (touch.test_held && !prev_test)
                shared->operator_pending.fetch_or(uint16_t(4));
            if (touch.service_held && !prev_service)
                shared->operator_pending.fetch_or(uint16_t(8));
            prev_test = touch.test_held;
            prev_service = touch.service_held;
            shared->pause_requested = !touch.focused || touch.menu_open;

            // ---- render one flat view ----
            auto frame = shared->scene.load();
            if (context && scene) {
                if (frame && native_picture_available(frame.get()) &&
                    frame->width > 1 && frame->height > 1 &&
                    touch.surface_w > 0 && touch.surface_h > 0) {
                    const NativeLens lens = presentation_lens(*frame);
                    scene->prepare(frame, false);
                    scene->set_presentation_lens(lens);
                    rect = pillarbox_rect(touch.surface_w, touch.surface_h,
                                          frame->width, frame->height);
                    // Clear the full surface first so the pillarbox bars
                    // show the clear colour, then draw into the rect.
                    glViewport(0, 0, touch.surface_w, touch.surface_h);
                    glClearColor(0.f, 0.f, 0.f, 1.f);
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    glViewport(rect.x, rect.y, rect.w, rect.h);
                    scene->draw(lens_frustum(lens), settings.world_scale,
                                settings.panel_distance, 1.f, 1.f);
                    if (!context->swap_buffers()) {
                        flat_log("flat host: swap failed; rebuilding GL");
                        teardown_gl();
                    }
                } else {
                    // No picture yet (or no surface size): keep the surface
                    // alive with a black frame and avoid a busy loop.
                    if (touch.surface_w > 0 && touch.surface_h > 0) {
                        glViewport(0, 0, touch.surface_w, touch.surface_h);
                        glClearColor(0.f, 0.f, 0.f, 1.f);
                        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                        context->swap_buffers();
                    }
                    std::this_thread::sleep_for(std::chrono::milliseconds(2));
                }
            } else {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
        }
        teardown_gl();
        shared->host_ready = false;
    } catch (const std::exception& e) {
        flat_log(std::string("flat host: fatal: ") + e.what());
        shared->host_ready = false;
        shared->quit_requested = true;
    } catch (...) {
        flat_log("flat host: fatal unknown error");
        shared->host_ready = false;
        shared->quit_requested = true;
    }
#if defined(__ANDROID__)
    if (g_activity_global && env) {
        env->DeleteGlobalRef(g_activity_global);
        g_activity_global = nullptr;
    }
    if (attached && g_vm) g_vm->DetachCurrentThread();
#endif
}

}  // namespace
}  // namespace vcop2

// ------------------------------------------------------- exported contract
// Symbol names and signatures MUST match what the engine binds in
// native/host_bridge.h (vcop2_xr_start/stop/android_init). Renaming them
// requires an engine-side patch; the names are vestigial, not VR behaviour.
FLAT_EXPORT bool vcop2_xr_start(vcop2::SharedRuntime* shared) {
    if (!shared || shared->abi != vcop2::SharedRuntime::Abi) return false;
    bool expected = false;
    if (!vcop2::g_running.compare_exchange_strong(expected, true))
        return false;
    vcop2::g_shared = shared;
    vcop2::g_stopping = false;
    try {
        vcop2::g_worker = std::thread(vcop2::worker_main, shared);
    } catch (...) {
        vcop2::g_running = false;
        vcop2::g_shared = nullptr;
        return false;
    }
    return true;
}

FLAT_EXPORT void vcop2_xr_stop() {
    vcop2::g_stopping = true;
    if (vcop2::g_worker.joinable()) vcop2::g_worker.join();
    vcop2::g_running = false;
    vcop2::g_shared = nullptr;
}

FLAT_EXPORT void vcop2_xr_android_init(void* vm, void* activity) {
#if defined(__ANDROID__)
    auto* java_vm = static_cast<JavaVM*>(vm);
    auto* activity_obj = static_cast<jobject>(activity);
    if (!java_vm || !activity_obj) return;
    vcop2::g_vm = java_vm;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) ==
        JNI_EDETACHED) {
        attached = java_vm->AttachCurrentThread(&env, nullptr) == 0;
        if (!attached) env = nullptr;
    }
    if (env) {
        jobject global = env->NewGlobalRef(activity_obj);
        if (vcop2::g_activity_global)
            env->DeleteGlobalRef(vcop2::g_activity_global);
        vcop2::g_activity_global = global;
    }
    if (attached) java_vm->DetachCurrentThread();
#else
    (void)vm;
    (void)activity;
#endif
}

// ------------------------------------------------- flat C API (JNI glue calls
// these; see docs/FLAT-PORT.md for the interface contract). All functions
// are thread-safe and never block on the worker thread.
extern "C" {

// Pointer actions: 0 = down, 1 = move, 2 = up, 3 = cancel. Coordinates are
// surface pixels, origin top-left. surface_w/h describe the same surface.
void vcop2_flat_pointer(int id, int action, float x, float y, int surface_w,
                        int surface_h) {
    std::lock_guard lock(vcop2::g_touch_mutex);
    auto& touch = vcop2::g_touch;
    touch.surface_w = surface_w;
    touch.surface_h = surface_h;
    if (action == 3) {
        // Cancel releases every pointer, mirroring SDL's own handling.
        touch.pointers.clear();
        touch.aim_pointer = -1;
        return;
    }
    if (action == 0) {
        touch.pointers[id] = vcop2::PointerState{x, y, true};
        if (touch.aim_pointer < 0) touch.aim_pointer = id;
        touch.last_x = x;
        touch.last_y = y;
        touch.has_point = true;
    } else if (action == 1) {
        auto it = touch.pointers.find(id);
        if (it != touch.pointers.end()) {
            it->second.x = x;
            it->second.y = y;
        }
        if (id == touch.aim_pointer) {
            touch.last_x = x;
            touch.last_y = y;
            touch.has_point = true;
        }
    } else if (action == 2) {
        touch.pointers.erase(id);
        if (id == touch.aim_pointer) {
            // Aim stays at the last point (InputPolicy discipline); the
            // next remaining pointer becomes the aim pointer, if any.
            touch.aim_pointer =
                touch.pointers.empty() ? -1 : touch.pointers.begin()->first;
            if (touch.aim_pointer >= 0) {
                const auto& p = touch.pointers.at(touch.aim_pointer);
                touch.last_x = p.x;
                touch.last_y = p.y;
            }
        }
    }
}

// Buttons: 0 = FIRE, 1 = RELOAD, 2 = START, 3 = TEST, 4 = SERVICE.
// pressed is 0/1. Callers must deliver both press and release.
void vcop2_flat_button(int button, int pressed) {
    std::lock_guard lock(vcop2::g_touch_mutex);
    auto& touch = vcop2::g_touch;
    const bool held = pressed != 0;
    switch (button) {
        case 0: touch.fire_held = held; break;
        case 1: touch.reload_held = held; break;
        case 2: touch.start_held = held; break;
        case 3: touch.test_held = held; break;
        case 4: touch.service_held = held; break;
        default: break;
    }
}

// Touch modes: 0 = tap-to-shoot (default), 1 = aim+fire. Other values ignored.
void vcop2_flat_set_touch_mode(int mode) {
    if (mode != 0 && mode != 1) return;
    std::lock_guard lock(vcop2::g_touch_mutex);
    vcop2::g_touch.touch_mode = mode;
}

// Surface handoff: native_window is an ANativeWindow* owned by the caller.
// Pass null via vcop2_flat_clear_surface() when the surface is destroyed.
void vcop2_flat_set_surface(void* native_window, int w, int h) {
    std::lock_guard lock(vcop2::g_touch_mutex);
    vcop2::g_touch.window = native_window;
    vcop2::g_touch.surface_w = w;
    vcop2::g_touch.surface_h = h;
}

void vcop2_flat_clear_surface() {
    std::lock_guard lock(vcop2::g_touch_mutex);
    vcop2::g_touch.window = nullptr;
}

void vcop2_flat_set_focused(int focused) {
    std::lock_guard lock(vcop2::g_touch_mutex);
    vcop2::g_touch.focused = focused != 0;
}

void vcop2_flat_set_menu_open(int open) {
    std::lock_guard lock(vcop2::g_touch_mutex);
    vcop2::g_touch.menu_open = open != 0;
}

void vcop2_flat_request_quit() {
    if (vcop2::g_shared) vcop2::g_shared->quit_requested = true;
}

}  // extern "C"
