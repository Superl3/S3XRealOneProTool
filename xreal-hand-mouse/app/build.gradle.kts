plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.raphael.handmouse"
    compileSdk = 35

    defaultConfig {
        // Eye Tools fork: own applicationId so it can coexist with upstream Hand Mouse. The Kotlin
        // package / namespace stays com.raphael.handmouse (keeps the diff against upstream small).
        applicationId = "io.github.xrealeyetools"
        minSdk = 34
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.1-eyetools"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Installable release APK without a private keystore: signed with the local debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
            java.srcDirs("src/main/java")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
    }

    // hand_landmarker.task precisa ficar sem compressão no APK: o runtime do MediaPipe faz
    // mmap direto do asset (AssetFileDescriptor com offset/length não-zero exige que o
    // arquivo esteja STORED, não DEFLATE) — sem isso, createFromFile/createFromOptions falha
    // em runtime ao tentar abrir o modelo.
    androidResources {
        noCompress += "task"
    }

    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // UI redesign "painel de instrumentos AR": Material3 (MaterialButton/MaterialCardView + tema
    // Theme.Material3.Dark) e ConstraintLayout (aspect ratio 4:3 do preview, casando com o
    // stream 768x576).
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // Eye Tools fork: settings screen (PreferenceFragmentCompat).
    implementation("androidx.preference:preference-ktx:1.2.1")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // MediaPipe HandLandmarker (LIVE_STREAM/GPU) — PLANO.md §3.2. 0.10.35 é a versão estável
    // mais recente no repositório Maven do Google (dl.google.com/dl/android/maven2; NÃO existe
    // no Maven Central — verificado nesta sessão) — mais nova que a 0.10.29 citada no plano.
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    // Conversão YUV_420_888 -> RGBA com downscale (FrameConverter) — PLANO.md §6.1. Versão
    // mais recente no Maven Central (verificado nesta sessão): 0.43.2.
    implementation("io.github.crow-misia.libyuv:libyuv-android:0.43.2")

    testImplementation("junit:junit:4.13.2")
}
