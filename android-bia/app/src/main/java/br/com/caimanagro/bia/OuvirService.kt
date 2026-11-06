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
import android.app.KeyguardManager
import android.os.PowerManager
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class OuvirService : Service(), RecognitionListener {
  private var modelo: Model? = null
  private var spk: SpeakerModel? = null   // 06/11: biometria de voz
  private var silencioAte = 0L
  private var fala: SpeechService? = null
  private val h = Handler(Looper.getMainLooper())
  private val PAUSA_MS = 120_000L   // tempo da conversa com a pagina aberta (o microfone fica com ela)

  override fun onBind(i: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("ouvir", "Bia ouvindo", NotificationManager.IMPORTANCE_MIN))
    nm.createNotificationChannel(NotificationChannel("chamar", "Chamar a Bia", NotificationManager.IMPORTANCE_HIGH))
    val abrirApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val n = Notification.Builder(this, "ouvir").setContentTitle("Bia ouvindo").setContentText("Diga \"Bia\" para conversar")
      .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(abrirApp).setOngoing(true).build()
    if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
    LibVosk.setLogLevel(LogLevel.WARNINGS)
    /* 05/11 (ele: trocar o nome para Friday): "friday" nao existe no modelo de portugues, entao a palavra de
       ativacao usa o modelo pequeno de INGLES do Vosk (offline, gramatica so ["friday", "[unk]"]). */
    StorageService.unpack(this, "model-pt", "model", { m -> modelo = m; carregarBiometria() }, { e -> avisar("Não consegui carregar o modelo de voz: ${e.message}") })
  }

  /* 06/11: o modelo de locutor carrega numa thread (copia ~13 MB na primeira vez); sem ele a Bia ainda funciona
     com o celular DESBLOQUEADO, mas nao abre por cima da tela bloqueada */
  private fun carregarBiometria() {
    Thread {
      try { spk = SpeakerModel(VozPerfil.caminhoModelo(this)) } catch (e: Exception) { spk = null }
      h.post { escutar() }
    }.start()
  }

  private fun escutar() {
    val m = modelo ?: return
    try {
      val r = Recognizer(m, 16000f, "[\"bia\", \"[unk]\"]")
      spk?.let { try { r.setSpeakerModel(it) } catch (_: Exception) {} }
      fala = SpeechService(r, 16000f).also { it.startListening(this) }
    } catch (e: Exception) { avisar("Microfone ocupado; tento de novo em 1 minuto"); h.postDelayed({ escutar() }, 60_000) }
  }

  private fun parar() { fala?.stop(); fala?.shutdown(); fala = null }

  private fun ouviu(json: String?) {
    val t = try { JSONObject(json ?: "{}").optString("text", "") } catch (e: Exception) { "" }
    if (!Regex("\\bbia\\b").containsMatchIn(t)) return
    /* 06/11 17h39: nao dispara com som de outro app tocando (audio do WhatsApp, video, musica), durante ligacao,
       nem nos 20 s depois de fechar a conversa */
    val am = getSystemService(android.media.AudioManager::class.java)
    if (am.isMusicActive || am.mode == android.media.AudioManager.MODE_IN_CALL || am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) { VozPerfil.registrar(this, "ignorado: som de outro app ou ligação"); return }
    if (System.currentTimeMillis() < silencioAte) return
    /* 06/11: biometria de voz. Com voz cadastrada, so abre se a frase parecer a voz do chefe. Sem cadastro, so abre
       com o celular desbloqueado (bloqueado, quem chamar nao passa). */
    val bloqueado = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    val perfil = VozPerfil.perfil(this)
    var vozOk = false
    if (perfil != null) {
      val (v, frames) = VozPerfil.vetor(json)
      val sim = if (v != null) VozPerfil.cos(v, perfil) else -1.0
      val lim = VozPerfil.limiar(this)
      vozOk = v != null && sim >= lim
      VozPerfil.registrar(this, (if (v == null) "sem amostra" else "%.2f".format(sim)) + (if (vozOk) " ✓ você" else " ✗ não reconhecida") + " · ${frames / 100.0}s")
      if (!vozOk) return
    } else if (bloqueado) { VozPerfil.registrar(this, "celular bloqueado e voz não cadastrada"); return }
    parar()
    if (bloqueado) acordarTela()
    if (Settings.canDrawOverlays(this)) Abrir.bia(this, vozOk) else avisar("Toque para falar com a Bia")
    h.postDelayed({ escutar() }, 15 * 60_000L)   // a Conversa manda "retomar" ao fechar
  }

  @Suppress("DEPRECATION")
  private fun acordarTela() {
    try {
      val pm = getSystemService(PowerManager::class.java)
      pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "bia:acordar").acquire(10_000)
    } catch (e: Exception) {}
  }

  private fun avisar(txt: String) {
    val pi = PendingIntent.getActivity(this, 1, Intent(this, Conversa::class.java), PendingIntent.FLAG_IMMUTABLE)
    val n = Notification.Builder(this, "chamar").setContentTitle("Bia").setContentText(txt).setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentIntent(pi).setAutoCancel(true).setFullScreenIntent(pi, true).build()
    getSystemService(NotificationManager::class.java).notify(2, n)
  }

  /* 05/11 (ele: "queria que ela fosse tipo o ok google do android"): o botao de assistente do Android abre a Friday
     pela Assistente; antes ela manda "pausar" aqui para soltar o microfone para a pagina. */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    /* pausar: a tela Conversa esta com o microfone (volta sozinho em 15 min se ela nao avisar); retomar: tela fechou */
    if (intent?.action == "pausar") { parar(); h.removeCallbacksAndMessages(null); h.postDelayed({ escutar() }, 15 * 60_000L) }
    if (intent?.action == "retomar") { silencioAte = System.currentTimeMillis() + 20_000; h.removeCallbacksAndMessages(null); if (fala == null) h.postDelayed({ escutar() }, 1_000) }
    return START_STICKY
  }

  override fun onResult(hyp: String?) = ouviu(hyp)
  override fun onFinalResult(hyp: String?) = ouviu(hyp)
  override fun onPartialResult(hyp: String?) {}
  override fun onError(e: Exception?) { parar(); h.postDelayed({ escutar() }, 5_000) }
  override fun onTimeout() { parar(); escutar() }

  override fun onDestroy() { h.removeCallbacksAndMessages(null); parar(); modelo?.close(); try { spk?.close() } catch (_: Exception) {}; super.onDestroy() }
}
