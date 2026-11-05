plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.attendance"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.attendance"
        // The C6 runs Android 10 / API 29. minSdk 29 keeps us honest: there is
        // no point supporting devices we will never deploy to, and a lower
        // minSdk would silently permit APIs that behave differently here.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Both ABIs are present in the vendor libraries. arm64-v8a is what
            // the C6 actually uses; armeabi-v7a is kept so the same APK works
            // if a 32-bit unit turns up.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // The vendor .so files were extracted from the device's own demo app.
    // See ../SDK-CONTRACT.md for provenance.
    sourceSets["main"].jniLibs.srcDirs("../jniLibs")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        // The vendor libraries are not page-aligned and must not be compressed
        // out from under the loader.
        jniLibs.useLegacyPackaging = true
    }
}

// exportSchema = true needs somewhere to put the schema, otherwise Room warns
// on every build and future migrations have nothing to diff against.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ---------------------------------------------------------------------
    // The ZKTeco SDK, extracted from the demo app that ships on the device.
    // Produced by ../tools/build-sdk-jar.ps1; see ../SDK-CONTRACT.md.
    //
    // implementation, NOT compileOnly. The .so files are native code; the Java
    // classes that bind to them via JNI have to be inside our APK, exactly as
    // the vendor demo bundles them. compileOnly builds cleanly and then dies
    // with NoClassDefFoundError the first time the sensor is opened.
    // ---------------------------------------------------------------------
    implementation(files("libs/zkteco-sdk.jar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Declared explicitly even though material pulls it in transitively. The
    // staff list recycles rows because the roster is expected to reach several
    // hundred people, and depending on someone else's transitive dependency for
    // a class we use directly breaks the day that dependency is bumped.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room - our own persistence. The SDK's template store is in-memory only
    // (see SDK-CONTRACT.md), so this is not optional.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager - drains the offline punch queue with backoff.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
