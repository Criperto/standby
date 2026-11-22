plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.criperto.standby"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.criperto.standby"
        minSdk = 31
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.onnxruntime.android)
    implementation(libs.androidsvg)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

val repoRoot = rootProject.projectDir.parentFile
val rustDir = File(repoRoot, "rust")
val jniArm64Dir = file("src/main/jniLibs/arm64-v8a")
val uniffiOutDir = file("src/main/java")
val cargoBin = File(repoRoot, ".toolchain/cargo/bin/cargo")
val rustupHome = File(repoRoot, ".toolchain/rustup")
val cargoHome = File(repoRoot, ".toolchain/cargo")
val androidSdk = File(repoRoot, ".toolchain/android-sdk")

val buildRustArm64 by tasks.registering(Exec::class) {
    group = "rust"
    description = "Compile standby-core for arm64-v8a using isolated cargo-ndk"
    workingDir = rustDir
    inputs.dir(File(rustDir, "standby-core/src"))
    inputs.file(File(rustDir, "standby-core/Cargo.toml"))
    inputs.file(File(rustDir, "Cargo.toml"))
    outputs.file(File(jniArm64Dir, "libstandby_core.so"))

    onlyIf {
        System.getenv("STANDBY_SKIP_GRADLE_RUST") != "1"
    }

    environment("RUSTUP_HOME", rustupHome.absolutePath)
    environment("CARGO_HOME", cargoHome.absolutePath)
    environment("ANDROID_HOME", androidSdk.absolutePath)
    environment("ANDROID_SDK_ROOT", androidSdk.absolutePath)
    environment(
        "PATH",
        "${cargoHome.absolutePath}/bin:${System.getenv("PATH") ?: ""}"
    )
    environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")

    commandLine(
        cargoBin.absolutePath, "ndk",
        "--target", "arm64-v8a",
        "--platform", "31",
        "-o", file("src/main/jniLibs").absolutePath,
        "build", "-p", "standby-core", "--release"
    )
}

val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generate UniFFI Kotlin bindings from libstandby_core.so"
    dependsOn(buildRustArm64)
    workingDir = rustDir
    inputs.file(File(jniArm64Dir, "libstandby_core.so"))
    outputs.dir(File(uniffiOutDir, "uniffi"))

    onlyIf {
        System.getenv("STANDBY_SKIP_GRADLE_RUST") != "1"
    }

    environment("RUSTUP_HOME", rustupHome.absolutePath)
    environment("CARGO_HOME", cargoHome.absolutePath)
    environment(
        "PATH",
        "${cargoHome.absolutePath}/bin:${System.getenv("PATH") ?: ""}"
    )

    commandLine(
        cargoBin.absolutePath, "run",
        "-p", "standby-core",
        "--bin", "uniffi-bindgen",
        "--",
        "generate",
        "--library", File(jniArm64Dir, "libstandby_core.so").absolutePath,
        "--language", "kotlin",
        "--out-dir", uniffiOutDir.absolutePath
    )
}

tasks.named("preBuild") {
    dependsOn(generateUniffiBindings)
}
