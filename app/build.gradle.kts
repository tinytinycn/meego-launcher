plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.meego.launcher"
    // compileSdk 35：需要 android:windowOptOutEdgeToEdgeEnforcement（API 35 引入），
    // 用于退出 Android 15 强制 edge-to-edge，使内容可铺满至物理屏幕底边（详见 res/values-v35/themes.xml）。
    compileSdk = 35

    defaultConfig {
        applicationId = "com.meego.launcher"
        minSdk = 28
        // targetSdk 35：启用 Android 官方 edge-to-edge 退出开关
        // (android:windowOptOutEdgeToEdgeEnforcement，见 res/values-v35/themes.xml)，
        // 使应用内容可铺满至物理屏幕底边 y=2400，消除底部 66px 黑缝。
        //
        // 真机实测根因（Redmi K40 / 1080x2400 / LineageOS Android 16）：
        //   mBounds    = Rect(0,  0 - 1080, 2400)   ← 物理屏幕
        //   mAppBounds = Rect(0, 95 - 1080, 2334)   ← 系统划给应用的安全区
        // 该退出属性仅对 targetSdk 35+ 生效，targetSdk 34 时是死属性。
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
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
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ""
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
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Coil for app icons / images
    implementation("io.coil-kt:coil-compose:2.5.0")
}
