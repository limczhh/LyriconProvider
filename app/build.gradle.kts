/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    kotlin("plugin.serialization") version "2.1.21"
    id("kotlin-parcelize")
}

configure<ApplicationExtension> {
    namespace = "io.github.proify.lyricon.provider.app"
    compileSdk = (rootProject.extra.get("compileSdkVersion") as Int)

    defaultConfig {
        applicationId = "io.github.proify.lyricon.provider"
        minSdk = 28
        targetSdk = (rootProject.extra.get("targetSdkVersion") as Int)
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("RELEASE_STORE_FILE") ?: "release.jks"
            val jksFile = file(storeFilePath)
            if (jksFile.exists()) {
                storeFile = jksFile
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
        }
        getByName("release") {
            val jksFile = file(System.getenv("RELEASE_STORE_FILE") ?: "release.jks")
            if (jksFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
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

    buildFeatures {
        buildConfig = true
        aidl = true
    }
}

dependencies {
    implementation(libs.lyricon.provider)
    implementation(libs.lyricon.lyric.model)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dexkit)

    implementation(libs.yukihookapi.api)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    compileOnly(libs.xposed.api)
    ksp(libs.yukihookapi.ksp.xposed)

    implementation(libs.okhttp)
    implementation(libs.okhttp.brotli)
    implementation(libs.taglib)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
