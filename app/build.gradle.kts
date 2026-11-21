plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.satya.workbench"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.satya.workbench"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same key as the debug builds from this machine, so a release installs over them and later updates install over it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        // Hidden framework APIs are the point of this app (reached through HiddenApiBypass and the system hook).
        disable += "BlockedPrivateApi"
    }
}

dependencies {
    compileOnly(project(":hidden"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("in.devh:coui-kit:1.2.1")
}
