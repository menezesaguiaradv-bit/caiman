package br.com.caimanagro.bia

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
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
    setContentView(web)
    web.loadUrl(Abrir.URL_AO_VIVO)
  }

  private var pendente: PermissionRequest? = null
  override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
    super.onRequestPermissionsResult(rc, p, r)
    val q = pendente ?: return; pendente = null
    if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) q.grant(q.resources) else q.deny()
  }

  /* chamada de novo (gesto/palavra com a tela ja aberta): recarrega e liga de novo */
  override fun onNewIntent(i: Intent?) { super.onNewIntent(i); web.loadUrl(Abrir.URL_AO_VIVO) }

  private fun servico(acao: String) {
    if (getSharedPreferences("bia", MODE_PRIVATE).getBoolean("ligado", false)) {
      try { startService(Intent(this, OuvirService::class.java).setAction(acao)) } catch (e: Exception) {}
    }
  }

  override fun onDestroy() {
    try { web.loadUrl("about:blank"); web.destroy() } catch (e: Exception) {}
    servico("retomar")
    super.onDestroy()
  }
}
