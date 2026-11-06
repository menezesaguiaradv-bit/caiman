package br.com.caimanagro.bia

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.app.KeyguardManager
import android.os.Build
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/* 05/11 22h56 (ele: "da para fazer isso sem a pagina dela estar aberta? ... pelo caminho 2, pode ser com o celular
   desbloqueado"): a Bia abre DENTRO do app, numa tela propria (WebView com a pagina da Bia), e ja liga a conversa ao
   vivo (?aovivo=1). No WebView o app libera o microfone e o som sem toque na tela, o que o Chrome nao deixa.
   O login fica guardado no app (entra uma vez). A escuta da palavra "Bia" fica parada enquanto esta tela esta aberta. */
class Conversa : Activity() {
  private lateinit var web: WebView

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.statusBarColor = 0xFF0E0505.toInt()
    tratarBloqueio(intent)
    servico("pausar")
    web = WebView(this)
    web.setBackgroundColor(0xFF0E0505.toInt())
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true
    web.settings.mediaPlaybackRequiresUserGesture = false
    web.webViewClient = WebViewClient()
    web.webChromeClient = object : WebChromeClient() {
      override fun onPermissionRequest(req: PermissionRequest) {
        runOnUiThread {
          if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) req.grant(req.resources)
          else { pendente = req; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7) }
        }
      }
    }
    /* 06/11: ponte com a pagina. fechar(): a conversa acabou. nativo/iniciar/enviar/parar: o audio da conversa ao vivo
       roda no app (AoVivo), com a biometria de voz em cada frase; a pagina pede a chave temporaria e mostra o texto. */
    aoVivo = AoVivo(this) { ev -> runOnUiThread { try { web.evaluateJavascript("window.aoVivoNativo&&aoVivoNativo(" + org.json.JSONObject.quote(ev) + ")", null) } catch (_: Exception) {} } }
    web.addJavascriptInterface(object {
      @android.webkit.JavascriptInterface fun fechar() { runOnUiThread { finish() } }
      @android.webkit.JavascriptInterface fun nativo(): Boolean = true
      @android.webkit.JavascriptInterface fun iniciar(chave: String, modelo: String) {
        val p = primeira; primeira = null
        runOnUiThread { Modelos.carregar(this@Conversa) { aoVivo?.iniciar(chave, modelo, p) } }
      }
      @android.webkit.JavascriptInterface fun enviar(json: String) { aoVivo?.enviar(json) }
      @android.webkit.JavascriptInterface fun parar() { runOnUiThread { aoVivo?.parar() } }
    }, "BiaApp")
    primeira = intent?.getStringExtra("primeira")
    Modelos.carregar(this) { }
    setContentView(web)
    web.loadUrl(Abrir.URL_AO_VIVO)
  }

  private var aoVivo: AoVivo? = null
  private var primeira: String? = null
  private var pendente: PermissionRequest? = null
  override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
    super.onRequestPermissionsResult(rc, p, r)
    val q = pendente ?: return; pendente = null
    if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) q.grant(q.resources) else q.deny()
  }

  /* chamada de novo (gesto/palavra com a tela ja aberta): recarrega e liga de novo */
  override fun onNewIntent(i: Intent?) { super.onNewIntent(i); tratarBloqueio(i); aoVivo?.parar(); primeira = i?.getStringExtra("primeira"); web.loadUrl(Abrir.URL_AO_VIVO) }

  /* 06/11 (ele: "quero que a Bia comece a operar com o celular bloqueado ... biometria vocal para so responder a mim"):
     chamada pela voz reconhecida (voz=true) -> abre por cima da tela bloqueada e acende a tela.
     Chamada por gesto/notificacao com o celular bloqueado -> pede o desbloqueio antes (ninguem le os dados sem ele). */
  @Suppress("DEPRECATION")
  private fun tratarBloqueio(i: Intent?) {
    val km = getSystemService(KeyguardManager::class.java)
    val voz = i?.getBooleanExtra("voz", false) == true
    if (voz) {
      if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
      else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    } else {
      if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(false)
      if (km.isKeyguardLocked) km.requestDismissKeyguard(this, null)
    }
  }

  private fun servico(acao: String) {
    if (getSharedPreferences("bia", MODE_PRIVATE).getBoolean("ligado", false)) {
      try { startService(Intent(this, OuvirService::class.java).setAction(acao)) } catch (e: Exception) {}
    }
  }

  /* 06/11 17h39 (print dele: o WhatsApp dizia "nao e possivel gravar durante uma ligacao" e "a Bia fica falando toda
     hora quando eu acesso os apps"): a conversa ao vivo seguia aberta por tras, segurando o microfone e respondendo
     ao som dos outros apps. Agora, saiu da tela da Bia (outro app, tela inicial, tela apagada), a conversa ENCERRA. */
  override fun onStop() {
    super.onStop()
    if (!isChangingConfigurations) finish()
  }

  override fun onDestroy() {
    try { aoVivo?.parar() } catch (e: Exception) {}
    try { web.loadUrl("about:blank"); web.destroy() } catch (e: Exception) {}
    servico("retomar")
    super.onDestroy()
  }
}
