import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mycast.dlna"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mycast.dlna"
        // محرك Brave adblock-rust يتطلب 26+
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    // لازم لتشغيل صوت الهاتف عند بث HLS (m3u8)
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    // محرك Brave adblock-rust (JNI)
    implementation("io.github.mlm-games:adblock:0.1.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
