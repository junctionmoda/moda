import java.net.URI
import java.util.zip.ZipInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.junction.focusglasses"
  compileSdk = 36

  buildFeatures {
    buildConfig = true
    compose = true
  }

  defaultConfig {
    applicationId = "com.junction.focusglasses"
    minSdk = 31
    targetSdk = 36
    // MediaPipe only ships arm64 natives; x86_64 kept so the app still installs on an emulator.
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    versionCode = 1
    versionName = "0.1"

    // Developer Mode in the Meta AI app: attestation is not used, so "0" is fine.
    manifestPlaceholders["mwdat_application_id"] = "0"
    manifestPlaceholders["mwdat_client_token"] = "0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  androidResources { noCompress += "tflite" }
  sourceSets { getByName("main") { assets.srcDir(layout.buildDirectory.dir("generated/vosk-assets")) } }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.material3)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.inputs)
  implementation(libs.mwdat.motion)
  implementation(libs.mwdat.mockdevice)
  implementation(libs.mwdat.speech)
  implementation(libs.mediapipe.tasks.vision)
  implementation(libs.vosk.android)
}

// Offline English speech model for the voice commands (Apache 2.0, ~40 MB). Downloaded once into
// .cache/ at the repo root and unpacked into generated assets, so it is not committed to git.
val voskModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
val fetchVoskModel by tasks.registering {
  val zip = rootProject.layout.projectDirectory.file(".cache/vosk-model-small-en-us-0.15.zip").asFile
  val outDir = layout.buildDirectory.dir("generated/vosk-assets/model-en-us").get().asFile
  outputs.dir(outDir)
  doLast {
    if (!zip.exists()) {
      zip.parentFile.mkdirs()
      logger.lifecycle("Downloading $voskModelUrl")
      URI(voskModelUrl).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
    }
    if (outDir.resolve("uuid").exists()) return@doLast
    outDir.deleteRecursively()
    outDir.mkdirs()
    ZipInputStream(zip.inputStream()).use { zin ->
      while (true) {
        val e = zin.nextEntry ?: break
        val rel = e.name.substringAfter('/') // strip the top-level folder
        if (rel.isEmpty() || e.isDirectory) continue
        val f = outDir.resolve(rel)
        f.parentFile.mkdirs()
        f.outputStream().use { zin.copyTo(it) }
      }
    }
    // Vosk's StorageService re-unpacks the assets when this id changes.
    outDir.resolve("uuid").writeText("vosk-model-small-en-us-0.15")
  }
}
tasks.named("preBuild") { dependsOn(fetchVoskModel) }
