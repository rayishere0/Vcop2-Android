# SPDX-License-Identifier: GPL-2.0-or-later
# Flat overlay CMakeLists.txt. The fetch script overlays this file onto the
# upstream tree root, replacing upstream CMakeLists.txt for the flat build.
# Android-only: it builds the flat host library (libvcop2_xr.so) from the
# flat host source plus the KEPT upstream renderer files. No OpenXR, no
# Windows test targets, no dropped sources. The target name is kept
# (libvcop2_xr.so) because the engine binds it by name; see docs/FLAT-PORT.md.
cmake_minimum_required(VERSION 3.22)
project(vcop2_flat LANGUAGES C CXX)
if(NOT ANDROID)
  message(FATAL_ERROR "This overlay CMakeLists.txt is Android-only.")
endif()
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_library(vcop2_xr SHARED
  native/flat_host.cpp
  native/gl_scene.cpp
  native/gl_context.cpp
  vendor/glad/src/gl.c)
# Same search paths as upstream minus native/shared and vendor/openxr/include,
# which only the dropped VR/presentation sources needed.
target_include_directories(vcop2_xr PRIVATE native vendor vendor/stb vendor/glad/include vendor/glm)
target_link_libraries(vcop2_xr PRIVATE EGL GLESv3 android log dl)
