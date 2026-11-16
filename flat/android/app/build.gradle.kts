// SPDX-License-Identifier: GPL-2.0-or-later
plugins {
    id("com.android.application")
}

val nativeLibsDir = project.findProperty("vcop2NativeDir") as String? ?: "${project.projectDir}/src/main/libs"

android {
    namespace = "com.vrlightgunhub.vcop2"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vrlightgunhub.vcop2"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.15-flat"

        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs(nativeLibsDir)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
