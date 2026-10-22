plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kushrike.txnbuddy"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kushrike.txnbuddy"
        minSdk = 29
        targetSdk = 36
        // CI passes the run number, so every build from main installs as an update over the previous one.
        versionCode = providers.environmentVariable("VERSION_CODE").orNull?.toInt() ?: 1
        versionName = "1.0.$versionCode"
    }

    signingConfigs {
        // Release key from CI secrets (.github/workflows/build.yml). Without one, the debug key keeps sideloading working.
        val keystore = providers.environmentVariable("TXNBUDDY_KEYSTORE").orNull
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = providers.environmentVariable("TXNBUDDY_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("TXNBUDDY_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("TXNBUDDY_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    // English UI only: drop the 80+ translations bundled by AndroidX libraries.
    androidResources { localeFilters += "en" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    testImplementation("junit:junit:4.13.2")
}
