plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.epubreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.epubreader"
        minSdk = 29
        targetSdk = 35
        versionCode = 117
        versionName = "1.0.17"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.webkit:webkit:1.11.0")
    // okhttp：原生支持任意 HTTP 方法（MKCOL/PROPFIND），解决反射改 method 在部分安卓版本失效导致实际发 GET 的 404 问题
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
