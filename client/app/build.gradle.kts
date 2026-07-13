import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace = "com.nativegamepad"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nativegamepad"
        minSdk = 28
        targetSdk = 37
        versionCode = 4
        versionName = "1.0.2"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {                                                                                                                                                                                                                                                                     
            storeFile = file("release.keystore")
            storePassword = localProperties.getProperty("KEYSTORE_PASSWORD") ?: ""
            keyAlias = localProperties.getProperty("KEYSTORE_ALIAS") ?: "nyxxpad"
            keyPassword = localProperties.getProperty("KEYSTORE_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    // QR Scanner was removed
    implementation("com.google.zxing:core:3.5.2")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation( "androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Required by auto-discovery coroutines in MainActivity / HomeActivity
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
}
