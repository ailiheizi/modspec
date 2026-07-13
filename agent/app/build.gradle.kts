plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.modspec.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.modspec.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    implementation("org.luckypray:dexkit:2.0.5")
}

tasks.configureEach {
    if (name.contains("checkDebugAarMetadata") || name.contains("checkReleaseAarMetadata")) {
        enabled = false
    }
}
