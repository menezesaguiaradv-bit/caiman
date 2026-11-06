package br.com.caimanagro.bia

/* Servico em primeiro plano (icone fixo "Bia ouvindo") que escuta SO a palavra "bia" com o Vosk offline (gramatica
   restrita: ["bia", "[unk]"]).
   06/11: microfone proprio (AudioRecord) em vez do SpeechService, para guardar o audio da frase inteira: "Bia, quanto
   tem de milho?" dito de uma vez vai junto para a conversa (ela ja responde a pergunta). Biometria de voz: com voz
   cadastrada, so a voz dele abre; com o celular bloqueado, so abre com a voz dele. */

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File

class OuvirService : Service() {
  private val h = Handler(Looper.getMainLooper())
  @Volatile private var rodando = false
  private var thread: Thread? = null
  private var silencioAte = 0L

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
    Modelos.carregar(this) { erro -> if (erro != null) avisar("Não consegui carregar o modelo de voz: $erro") else escutar() }
  }

  @SuppressLint("MissingPermission")
  private fun escutar() {
    val m = Modelos.pt ?: return
    if (rodando) return
    rodando = true
    thread = Thread {
      var rec: AudioRecord? = null
      var r: Recognizer? = null
      try {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
        r = Recognizer(m, 16000f, "[\"bia\", \"[unk]\"]")
        Modelos.spk?.let { try { r.setSpeakerModel(it) } catch (_: Exception) {} }
        rec.startRecording()
        val buf = ByteArray(3200)            // 100 ms
        val frase = ByteArrayOutputStream()  // audio desde o ultimo fim de frase (ate 12 s)
        while (rodando) {
          val n = rec.read(buf, 0, buf.size)
          if (n <= 0) continue
          frase.write(buf, 0, n)
          if (frase.size() > 16000 * 2 * 12) { val b = frase.toByteArray(); frase.reset(); frase.write(b, b.size - 16000 * 2 * 6, 16000 * 2 * 6) }
          if (r.acceptWaveForm(buf, n)) {
            val res = r.result
            val audio = frase.toByteArray(); frase.reset()
            h.post { ouviu(res, audio) }
          }
        }
      } catch (e: Exception) {
        h.post { avisar("Microfone ocupado; tento de novo em 1 minuto") }
        rodando = false
        h.postDelayed({ escutar() }, 60_000)
      } finally {
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        try { r?.close() } catch (_: Exception) {}
      }
    }.also { it.start() }
  }

  private fun parar() { rodando = false; try { thread?.join(1500) } catch (_: Exception) {}; thread = null }

  private fun ouviu(json: String?, audio: ByteArray) {
    val t = try { JSONObject(json ?: "{}").optString("text", "") } catch (e: Exception) { "" }
    if (!Regex("\\bbia\\b").containsMatchIn(t)) return
    /* nao dispara com som de outro app tocando, durante ligacao, nem nos 20 s depois de fechar a conversa */
    val am = getSystemService(AudioManager::class.java)
    if (am.isMusicActive || am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) { VozPerfil.registrar(this, "ignorado: som de outro app ou ligação"); return }
    if (System.currentTimeMillis() < silencioAte) return
    val bloqueado = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    val perfil = VozPerfil.perfil(this)
    var vozOk = false
    if (perfil != null) {
      val (v, frames) = VozPerfil.vetor(json)
      val sim = if (v != null) VozPerfil.cos(v, perfil) else -1.0
      vozOk = v != null && sim >= VozPerfil.limiar(this)
      VozPerfil.registrar(this, (if (v == null) "sem amostra" else "%.2f".format(sim)) + (if (vozOk) " ✓ você" else " ✗ não reconhecida") + " · ${frames / 100.0}s")
      if (!vozOk) return
    } else if (bloqueado) { VozPerfil.registrar(this, "celular bloqueado e voz não cadastrada"); return }
    parar()
    /* frase com mais que o nome ("Bia, quanto tem de milho?"): o audio vai para a conversa responder direto */
    var primeira: String? = null
    if (audio.size > 16000 * 2 * 1.6) {
      try { val f = File(cacheDir, "primeira.pcm"); f.writeBytes(audio); primeira = f.absolutePath } catch (_: Exception) {}
    }
    if (bloqueado) acordarTela()
    if (Settings.canDrawOverlays(this)) Abrir.bia(this, vozOk, primeira) else avisar("Toque para falar com a Bia")
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

  /* pausar: a tela Conversa ou o cadastro de voz estao com o microfone (volta sozinho em 15 min); retomar: fecharam */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == "pausar") { parar(); h.removeCallbacksAndMessages(null); h.postDelayed({ escutar() }, 15 * 60_000L) }
    if (intent?.action == "retomar") { silencioAte = System.currentTimeMillis() + 20_000; h.removeCallbacksAndMessages(null); h.postDelayed({ escutar() }, 1_000) }
    return START_STICKY
  }

  override fun onDestroy() { h.removeCallbacksAndMessages(null); parar(); super.onDestroy() }
}
