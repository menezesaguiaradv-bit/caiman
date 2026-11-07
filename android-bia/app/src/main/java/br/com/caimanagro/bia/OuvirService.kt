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
    try {
      if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
      VozPerfil.diario(this, "serviço iniciado")
    } catch (e: Exception) { VozPerfil.diario(this, "Android barrou o serviço: ${e.javaClass.simpleName}"); stopSelf(); return }
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
        h.post { VozPerfil.diario(this, "microfone ligado (escutando)"); notificar("Ouvindo · diga \"Bia\"") }
        val buf = ByteArray(3200)            // 100 ms
        val frase = ByteArrayOutputStream()  // audio desde o ultimo fim de frase (ate 12 s)
        /* 06/11 18h50 (ele: "nao esta funcionando com o app fechado em segundo plano"): o Android pode deixar o
           microfone MUDO para servico que voltou sozinho em segundo plano (so zeros). 30 s de zeros = avisa para tocar
           e reativar. E um "estou vivo" a cada 20 s, que a tela do app mostra. */
        var zeros = 0; var ultPing = 0L
        val pref = getSharedPreferences("bia", MODE_PRIVATE)
        while (rodando) {
          val n = rec.read(buf, 0, buf.size)
          if (n <= 0) continue
          var mudo = true; var i = 0
          while (i < n) { if (buf[i].toInt() != 0 || buf[i + 1].toInt() != 0) { mudo = false; break }; i += 2 }
          zeros = if (mudo) zeros + 1 else 0
          if (zeros == 300) { pref.edit().putBoolean("mic_mudo", true).apply(); h.post { avisarMudo() } }
          if (!mudo && pref.getBoolean("mic_mudo", false)) pref.edit().putBoolean("mic_mudo", false).apply()
          val agora = System.currentTimeMillis()
          if (agora - ultPing > 20_000) { ultPing = agora; pref.edit().putLong("ouvindo_em", agora).apply() }
          frase.write(buf, 0, n)
          if (frase.size() > 16000 * 2 * 12) { val b = frase.toByteArray(); frase.reset(); frase.write(b, b.size - 16000 * 2 * 6, 16000 * 2 * 6) }
          if (r.acceptWaveForm(buf, n)) {
            val res = r.result
            val audio = frase.toByteArray(); frase.reset()
            h.post { ouviu(res, audio) }
          }
        }
      } catch (e: Exception) {
        h.post { VozPerfil.diario(this, "erro no microfone: ${e.javaClass.simpleName} ${e.message ?: ""}"); notificar("Microfone ocupado · tento de novo em 1 min") }
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
    VozPerfil.diario(this, "ouvi \"bia\"")
    /* nao dispara com som de outro app tocando, durante ligacao, nem nos 20 s depois de fechar a conversa */
    val am = getSystemService(AudioManager::class.java)
    if (am.isMusicActive || am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) { VozPerfil.diario(this, "ignorei: som de outro app (${am.isMusicActive}) ou ligação (modo ${am.mode})"); notificar("Ignorei: outro app tocando som"); return }
    if (System.currentTimeMillis() < silencioAte) { VozPerfil.diario(this, "ignorei: 20 s depois de fechar a conversa"); return }
    val bloqueado = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    val perfil = VozPerfil.perfil(this)
    var vozOk = false
    if (perfil != null) {
      val (v, frames) = VozPerfil.vetor(json)
      val (sim, ok) = VozPerfil.verificar(this, v, frames)
      vozOk = ok
      VozPerfil.registrar(this, (if (v == null) "sem amostra" else "%.2f".format(sim)) + (if (vozOk) " ✓ você" else " ✗ não reconhecida") + " · ${frames / 100.0}s")
      VozPerfil.diario(this, "voz " + (if (vozOk) "reconhecida" else "NÃO reconhecida") + " (nota ${"%.2f".format(sim)}, ${frames / 100.0}s)")
      if (!vozOk) { notificar("Voz não reconhecida (nota ${"%.2f".format(sim)})"); return }
    } else if (bloqueado) { VozPerfil.registrar(this, "celular bloqueado e voz não cadastrada"); VozPerfil.diario(this, "ignorei: bloqueado e sem voz cadastrada"); notificar("Cadastre a voz para abrir com o celular bloqueado"); return }
    parar()
    /* frase com mais que o nome ("Bia, quanto tem de milho?"): o audio vai para a conversa responder direto */
    var primeira: String? = null
    if (audio.size > 16000 * 2 * 1.6) {
      try { val f = File(cacheDir, "primeira.pcm"); f.writeBytes(audio); primeira = f.absolutePath } catch (_: Exception) {}
    }
    if (bloqueado) acordarTela()
    if (Settings.canDrawOverlays(this)) {
      try { Abrir.bia(this, vozOk, primeira); VozPerfil.diario(this, "abri a Bia" + (if (bloqueado) " (tela bloqueada)" else "")) }
      catch (e: Exception) { VozPerfil.diario(this, "Android barrou abrir a tela: ${e.javaClass.simpleName}"); avisar("Toque para falar com a Bia") }
    } else { VozPerfil.diario(this, "sem permissão de aparecer sobre outros apps"); avisar("Toque para falar com a Bia") }
    h.postDelayed({ escutar() }, 15 * 60_000L)   // a Conversa manda "retomar" ao fechar
  }

  @Suppress("DEPRECATION")
  private fun acordarTela() {
    try {
      val pm = getSystemService(PowerManager::class.java)
      pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "bia:acordar").acquire(10_000)
    } catch (e: Exception) {}
  }

  /* atualiza a notificacao fixa com o ultimo acontecimento (ele ve sem abrir o app) */
  private fun notificar(txt: String) {
    try {
      val hora = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date())
      val abrirApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
      val n = Notification.Builder(this, "ouvir").setContentTitle("Bia ouvindo").setContentText("$hora · $txt")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(abrirApp).setOngoing(true).build()
      getSystemService(NotificationManager::class.java).notify(1, n)
    } catch (_: Exception) {}
  }

  private fun avisarMudo() {
    VozPerfil.diario(this, "Android deixou o microfone MUDO (30 s de silêncio absoluto)")
    notificar("Microfone pausado pelo Android · toque para reativar")
    val pi = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val n = Notification.Builder(this, "chamar").setContentTitle("Bia: escuta pausada pelo Android")
      .setContentText("Toque aqui para reativar o \"Bia\"").setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentIntent(pi).setAutoCancel(true).build()
    getSystemService(NotificationManager::class.java).notify(3, n)
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
    /* reiniciar: o app abriu (tela visivel) -> recomeca o microfone, que volta a ter permissao total */
    if (intent?.action == "reiniciar") { h.removeCallbacksAndMessages(null); parar(); h.postDelayed({ escutar() }, 300) }
    if (intent?.action == "retomar") { silencioAte = System.currentTimeMillis() + 20_000; h.removeCallbacksAndMessages(null); h.postDelayed({ escutar() }, 1_000) }
    return START_STICKY
  }

  override fun onDestroy() { VozPerfil.diario(this, "serviço encerrado (Android ou desligado)"); h.removeCallbacksAndMessages(null); parar(); super.onDestroy() }
  override fun onTaskRemoved(i: Intent?) { VozPerfil.diario(this, "app tirado dos recentes"); super.onTaskRemoved(i) }
}
