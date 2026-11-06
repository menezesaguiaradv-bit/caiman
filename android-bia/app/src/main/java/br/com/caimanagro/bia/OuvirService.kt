package br.com.caimanagro.bia

/* Servico em primeiro plano (icone fixo "Bia ouvindo") que escuta SO a palavra "bia" com o Vosk offline (gramatica
   restrita: ["bia", "[unk]"]). Ouviu: solta o microfone (a pagina da Bia precisa dele), abre a Bia ja ouvindo e volta
   a escutar depois de PAUSA_MS. Sem permissao de "aparecer sobre outros apps", mostra uma notificacao para tocar. */

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class OuvirService : Service(), RecognitionListener {
  private var modelo: Model? = null
  private var fala: SpeechService? = null
  private val h = Handler(Looper.getMainLooper())
  private val PAUSA_MS = 120_000L   // tempo da conversa com a pagina aberta (o microfone fica com ela)

  override fun onBind(i: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("ouvir", "Friday ouvindo", NotificationManager.IMPORTANCE_MIN))
    nm.createNotificationChannel(NotificationChannel("chamar", "Chamar a Friday", NotificationManager.IMPORTANCE_HIGH))
    val abrirApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val n = Notification.Builder(this, "ouvir").setContentTitle("Friday ouvindo").setContentText("Diga \"Friday\" para conversar")
      .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(abrirApp).setOngoing(true).build()
    if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
    LibVosk.setLogLevel(LogLevel.WARNINGS)
    /* 05/11 (ele: trocar o nome para Friday): "friday" nao existe no modelo de portugues, entao a palavra de
       ativacao usa o modelo pequeno de INGLES do Vosk (offline, gramatica so ["friday", "[unk]"]). */
    StorageService.unpack(this, "model-en", "model-en", { m -> modelo = m; escutar() }, { e -> avisar("Não consegui carregar o modelo de voz: ${e.message}") })
  }

  private fun escutar() {
    val m = modelo ?: return
    try {
      val r = Recognizer(m, 16000f, "[\"friday\", \"[unk]\"]")
      fala = SpeechService(r, 16000f).also { it.startListening(this) }
    } catch (e: Exception) { avisar("Microfone ocupado; tento de novo em 1 minuto"); h.postDelayed({ escutar() }, 60_000) }
  }

  private fun parar() { fala?.stop(); fala?.shutdown(); fala = null }

  private fun ouviu(json: String?) {
    val t = try { JSONObject(json ?: "{}").optString("text", "") } catch (e: Exception) { "" }
    if (!Regex("\\bfriday\\b").containsMatchIn(t)) return
    parar()
    if (Settings.canDrawOverlays(this)) Abrir.bia(this) else avisar("Toque para falar com a Friday")
    h.postDelayed({ escutar() }, PAUSA_MS)
  }

  private fun avisar(txt: String) {
    val pi = PendingIntent.getActivity(this, 1, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(Abrir.URL)), PendingIntent.FLAG_IMMUTABLE)
    val n = Notification.Builder(this, "chamar").setContentTitle("Friday").setContentText(txt).setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentIntent(pi).setAutoCancel(true).setFullScreenIntent(pi, true).build()
    getSystemService(NotificationManager::class.java).notify(2, n)
  }

  override fun onResult(hyp: String?) = ouviu(hyp)
  override fun onFinalResult(hyp: String?) = ouviu(hyp)
  override fun onPartialResult(hyp: String?) {}
  override fun onError(e: Exception?) { parar(); h.postDelayed({ escutar() }, 5_000) }
  override fun onTimeout() { parar(); escutar() }

  override fun onDestroy() { h.removeCallbacksAndMessages(null); parar(); modelo?.close(); super.onDestroy() }
}
