plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nativegamepad"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nativegamepad"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-beta"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "manchae19"
            keyAlias = "nyxxpad"
            keyPassword = "manchae19"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation( "androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
