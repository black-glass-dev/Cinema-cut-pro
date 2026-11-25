plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.kotlin.serialization)
}

val ffmpegBuildRoot = layout.buildDirectory.dir("ffmpeg-android").get().asFile
val ffmpegGeneratedMarker = ".cinema-cut-pro-ffmpeg-generated"
val ffmpegGeneratedOutputMarkers = listOf("build", "prefix")
  .map { directory -> ffmpegBuildRoot.resolve("$directory/$ffmpegGeneratedMarker") }
val ffmpegLibraries = listOf("avutil", "avcodec", "avformat", "swscale", "swresample")
val ffmpegBuildOutputFiles = ffmpegLibraries.flatMap { library ->
  listOf(
    ffmpegBuildRoot.resolve("prefix/lib/lib${library}.so"),
    ffmpegBuildRoot.resolve("jniLibs/arm64-v8a/lib${library}.so")
  )
} + listOf(
  ffmpegBuildRoot.resolve("assets/ffmpeg/COPYING.LGPLv2.1"),
  ffmpegBuildRoot.resolve("assets/ffmpeg/FFMPEG_BUILD_INFO.txt")
) + ffmpegGeneratedOutputMarkers
val ffmpegBuildHeaderDirectory = ffmpegBuildRoot.resolve("prefix/include")
val buildFfmpegAndroid by tasks.registering(Exec::class) {
  group = "build"
  description = "Builds the pinned LGPL-only FFmpeg libraries for Android MediaCodec playback."
  workingDir(rootProject.projectDir)
  commandLine("bash", rootProject.file("tools/build_ffmpeg_android.sh").absolutePath)
  environment("FFMPEG_BUILD_JOBS", providers.gradleProperty("ffmpegBuildJobs").orElse("2").get())
  inputs.file(rootProject.file("tools/build_ffmpeg_android.sh"))
  inputs.property("ffmpegSourceRevision", "1041abdc962f4cc4f394aa8de9dc5236c0c3b9e7")
  outputs.files(ffmpegBuildOutputFiles)
  outputs.dir(ffmpegBuildHeaderDirectory)
}

val releaseKeystoreFile = System.getenv("KEYSTORE_PATH")
  ?.takeIf { it.isNotBlank() }
  ?.let { rootProject.file(it) }
val releaseStorePassword = System.getenv("STORE_PASSWORD")
val releaseKeyAlias = System.getenv("KEY_ALIAS")
val releaseKeyPassword = System.getenv("KEY_PASSWORD")
val releaseSigningConfigured = releaseKeystoreFile?.isFile == true &&
  !releaseStorePassword.isNullOrBlank() &&
  !releaseKeyAlias.isNullOrBlank() &&
  !releaseKeyPassword.isNullOrBlank()

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.chromapro.xzvqpw"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    externalNativeBuild {
      cmake {
        cppFlags += "-std=c++17"
        arguments += "-DFFMPEG_PREFIX=${ffmpegBuildRoot.resolve("prefix").absolutePath}"
      }
    }
    ndk {
      abiFilters.add("arm64-v8a")
    }
  }

  externalNativeBuild {
    cmake {
      path("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  sourceSets.getByName("main").jniLibs.srcDir(ffmpegBuildRoot.resolve("jniLibs"))
  sourceSets.getByName("main").assets.srcDir(ffmpegBuildRoot.resolve("assets"))

  signingConfigs {
    if (releaseSigningConfigured) {
      create("release") {
        storeFile = requireNotNull(releaseKeystoreFile)
        storePassword = requireNotNull(releaseStorePassword)
        keyAlias = requireNotNull(releaseKeyAlias)
        keyPassword = requireNotNull(releaseKeyPassword)
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (releaseSigningConfigured) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
    // Keep the Android Gradle Plugin's default generated debug signing configuration.
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        it.systemProperty(
          "robolectric.dependency.repo.url",
          "https://maven-central.storage-download.googleapis.com/maven2/"
        )
        it.systemProperty("robolectric.dependency.repo.id", "googleMavenCentralMirror")
      }
    }
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// FFmpeg headers/libraries are required by the native decoder target and the app package.
tasks.configureEach {
  val needsFfmpeg = name == "preBuild" ||
    ((name.startsWith("configureCMake") || name.startsWith("externalNativeBuild")) &&
      !name.contains("clean", ignoreCase = true))
  if (needsFfmpeg) dependsOn(buildFfmpegAndroid)
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  implementation("org.videolan.android:libvlc-all:3.5.1")
  implementation(libs.media3.exoplayer)
  implementation(libs.media3.ui)
  implementation(libs.media3.effect)
  implementation(libs.media3.transformer)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.work.runtime.ktx)
  implementation(libs.retrofit.serialization)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
