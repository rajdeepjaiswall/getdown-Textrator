plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Values come from -P gradle properties or environment variables (GitHub Actions secrets).
fun cfg(name: String, default: String = ""): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: default

android {
    namespace = "com.getdown.textrator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.getdown.textrator"
        minSdk = 24
        targetSdk = 35
        versionCode = cfg("VERSION_CODE", "1").toInt()
        versionName = cfg("VERSION_NAME", "1.0.0")
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("SUPABASE_ANON_KEY")}\"")
        // optional = login screen with "Continue as guest"; required = must log in; off = skip login.
        buildConfigField("String", "AUTH_MODE", "\"${cfg("AUTH_MODE", "optional")}\"")
    }

    signingConfigs {
        // A fixed key means every new APK installs over the old one (no "app not installed" on update).
        // For Play Store, set KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD to your private key.
        create("release") {
            val ks = cfg("KEYSTORE_FILE")
            if (ks.isNotEmpty() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = cfg("KEYSTORE_PASSWORD")
                keyAlias = cfg("KEY_ALIAS")
                keyPassword = cfg("KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("keystore/dev.keystore")
                storePassword = "getdown123"
                keyAlias = "texttractor"
                keyPassword = "getdown123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // Bundled ML Kit models: OCR works offline from the very first tap (no model download wait).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
