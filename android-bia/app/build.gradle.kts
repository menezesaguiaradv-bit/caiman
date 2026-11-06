plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
  namespace = "br.com.caimanagro.bia"
  compileSdk = 34
  defaultConfig {
    applicationId = "br.com.caimanagro.bia"
    minSdk = 26
    targetSdk = 34
    versionCode = 12
    versionName = "2026.11.06-voz3"
    ndk { abiFilters += listOf("arm64-v8a") }   // celulares atuais; corta o APK pela metade
  }
  /* 06/11: chave FIXA (cofre do GitHub: BIA_KEYSTORE em base64 + BIA_KEY_PASS). Com ela a versao nova instala por cima
     e a voz cadastrada fica. Sem os segredos, cai na chave de debug (muda a cada compilacao). */
  val ks = file("bia.jks")
  signingConfigs {
    if (ks.exists() && System.getenv("BIA_KEY_PASS") != null) create("bia") {
      storeFile = ks; storePassword = System.getenv("BIA_KEY_PASS"); keyAlias = "bia"; keyPassword = System.getenv("BIA_KEY_PASS")
    }
  }
  buildTypes { getByName("release") { isMinifyEnabled = false
    signingConfig = if (ks.exists() && System.getenv("BIA_KEY_PASS") != null) signingConfigs.getByName("bia") else signingConfigs.getByName("debug") } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  androidResources { noCompress += listOf("conf", "mdl", "fst", "int", "mat", "uuid", "ie", "dubm", "txt", "carpa") }
}
dependencies {
  implementation("com.alphacephei:vosk-android:0.3.47@aar")
  implementation("net.java.dev.jna:jna:5.13.0@aar")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")   // 06/11: WebSocket da conversa ao vivo
}
