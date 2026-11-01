plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
  namespace = "br.com.caimanagro.bia"
  compileSdk = 34
  defaultConfig {
    applicationId = "br.com.caimanagro.bia"
    minSdk = 26
    targetSdk = 34
    versionCode = 1
    versionName = "2026.10.31"
  }
  buildTypes { getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  androidResources { noCompress += listOf("conf", "mdl", "fst", "int", "mat", "uuid", "ie", "dubm", "txt", "carpa") }
}
dependencies {
  implementation("com.alphacephei:vosk-android:0.3.47@aar")
  implementation("net.java.dev.jna:jna:5.13.0@aar")
  implementation("androidx.core:core-ktx:1.13.1")
}
