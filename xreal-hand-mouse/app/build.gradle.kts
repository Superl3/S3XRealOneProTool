import java.util.zip.ZipFile

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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Installable release APK without a private keystore: signed with the local debug
            // key. Release builds exclude the reverse-engineering ioctl tap (see below), so this
            // is the build to use day to day.
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

// O interpositor do spike (libioctltap.so) NUNCA vai pro release. Usamos a Variant API
// (em vez de buildTypes.release.packaging{}) porque esse bloco DSL, nesta versão do AGP
// (8.10.1) + Gradle 8.14, vazou a exclusão para a variant debug também (confirmado
// empiricamente: com o bloco em buildTypes.release, libioctltap.so sumia do APK debug).
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.add("**/libioctltap.so")
    }
}

// Guarda ESTRUTURAL (fix de revisão final) contra o achado "release exclusion depende de um
// nome de arquivo hard-coded": AGP 8.10.1 não expôs, nos testes desta sessão, uma forma
// funcional de escopar externalNativeBuild por variant — `externalNativeBuild.cmake.targets`
// em defaultConfig/buildTypes.release é um `MutableSet<String>` que a AGP UNE (não sobrescreve)
// entre defaultConfig e buildType, então um `targets()` vazio em release NÃO impede o CMake de
// compilar (nem, empiricamente, de EMPACOTAR — confirmado buildando com o excludes acima
// desligado: libioctltap.so reapareceu no APK de release mesmo com targets() vazio ali).
// Optamos então pela verificação pós-build: deriva os nomes de .so DIRETO do
// src/main/cpp/CMakeLists.txt (via regex em add_library(... SHARED ...), não hard-coded) e
// falha assembleRelease se qualquer um deles estiver no APK de release — cobre tanto um rename
// do alvo CMake quanto um 2º .so novo adicionado ao CMakeLists.txt, sem exigir nenhuma edição
// paralela aqui. `androidComponents{}` acima continua como defesa em profundidade (mais barata,
// roda durante o merge de packaging), esta verificação é o que realmente garante a invariante.
tasks.register("verifyNoSpikeNativeLibsInRelease") {
    group = "verification"
    description = "Falha se algum .so definido em src/main/cpp/CMakeLists.txt estiver no APK de release."
    dependsOn("packageRelease")

    doLast {
        val cmakeListsFile = file("src/main/cpp/CMakeLists.txt")
        val libTargetRegex = Regex("""add_library\(\s*(\S+)\s+SHARED""")
        val forbiddenSoNames = cmakeListsFile.readText()
            .lineSequence()
            .mapNotNull { libTargetRegex.find(it)?.groupValues?.get(1) }
            .map { "lib$it.so" }
            .toSet()

        if (forbiddenSoNames.isEmpty()) {
            logger.warn("verifyNoSpikeNativeLibsInRelease: nenhum add_library(... SHARED ...) encontrado em $cmakeListsFile — nada a verificar (suspeito; confira o CMakeLists.txt).")
            return@doLast
        }

        val apkOutDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apkFile = apkOutDir.listFiles { f -> f.name.endsWith(".apk") }?.firstOrNull()
            ?: throw GradleException("verifyNoSpikeNativeLibsInRelease: nenhum APK de release encontrado em $apkOutDir")

        val leaked = ZipFile(apkFile).use { zip ->
            zip.entries().asSequence()
                .map { it.name.substringAfterLast('/') }
                .filter { it in forbiddenSoNames }
                .toList()
        }

        if (leaked.isNotEmpty()) {
            throw GradleException(
                "APK de release ($apkFile) contém biblioteca(s) nativa(s) definida(s) em " +
                    "src/main/cpp/CMakeLists.txt que deveriam ser DEBUG-ONLY: $leaked"
            )
        }
    }
}

afterEvaluate {
    tasks.named("assembleRelease") {
        finalizedBy("verifyNoSpikeNativeLibsInRelease")
    }
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

    // MediaPipe HandLandmarker (LIVE_STREAM/GPU) — PLANO.md §3.2. 0.10.35 é a versão estável
    // mais recente no repositório Maven do Google (dl.google.com/dl/android/maven2; NÃO existe
    // no Maven Central — verificado nesta sessão) — mais nova que a 0.10.29 citada no plano.
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    // Conversão YUV_420_888 -> RGBA com downscale (FrameConverter) — PLANO.md §6.1. Versão
    // mais recente no Maven Central (verificado nesta sessão): 0.43.2.
    implementation("io.github.crow-misia.libyuv:libyuv-android:0.43.2")

    testImplementation("junit:junit:4.13.2")
}
