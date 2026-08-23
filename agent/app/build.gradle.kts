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

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
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

    // Script engines: pure-Java runtimes (no native ABI surface).
    // Rhino: MPL-2.0; LuaJ: MIT (see THIRD_PARTY_NOTICES).
    implementation("org.mozilla:rhino:1.7.15")
    implementation("org.luaj:luaj-jse:3.0.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.12")
}

tasks.configureEach {
    if (name.contains("checkDebugAarMetadata") || name.contains("checkReleaseAarMetadata")) {
        enabled = false
    }
}
