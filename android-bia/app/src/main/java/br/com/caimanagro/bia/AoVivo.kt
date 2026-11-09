package br.com.caimanagro.bia

/* 06/11 (ele: "so quero que ela reconheca somente a minha voz"): a conversa ao vivo com o AUDIO no proprio app.
   O microfone passa pelo Vosk, que acha o fim de cada frase e tira a impressao da voz. So a frase com a voz dele
   (biometria, mesmo limiar da escuta do "Bia") vai para a OpenAI; frase de outra pessoa, TV ou a propria voz da Bia
   saindo do alto-falante e descartada no celular. A resposta chega pelo WebSocket e toca aqui. A tela (WebView) so
   mostra o texto e faz as consultas (ela tem o login): os eventos vao para ela pelo js(). */

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AoVivo(private val ctx: Context, private val js: (String) -> Unit) {
  private var ws: WebSocket? = null
  @Volatile private var ativo = false
  @Volatile private var falando = false
  private var micThread: Thread? = null
  private var somThread: Thread? = null
  private val fila = LinkedBlockingQueue<ByteArray>()
  private var track: AudioTrack? = null
  private var primeira: File? = null
  @Volatile private var respostaAtiva = false

  fun iniciar(chave: String, modelo: String, primeiraFrase: String?) {
    if (ativo) parar()
    ativo = true
    primeira = primeiraFrase?.let { File(it) }?.takeIf { it.exists() }
    evento("estado", "pensando")
    val cli = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    val req = Request.Builder().url("wss://api.openai.com/v1/realtime?model=$modelo").header("Authorization", "Bearer $chave").build()
    ws = cli.newWebSocket(req, object : WebSocketListener() {
      override fun onOpen(w: WebSocket, r: Response) { }
      override fun onMessage(w: WebSocket, texto: String) { receber(texto) }
      override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
        if (ativo) { evento("erro", "A conexão caiu (${r?.code ?: t.message}). Chame a Bia de novo."); parar() }
      }
      override fun onClosed(w: WebSocket, code: Int, reason: String) { if (ativo) parar() }
    })
    tocar()
  }

  /** a tela manda eventos JSON (resultado de ferramenta etc.) */
  fun enviar(json: String) { ws?.send(json) }

  fun parar() {
    ativo = false
    try { ws?.close(1000, "fim") } catch (_: Exception) {}
    ws = null
    try { micThread?.join(1500) } catch (_: Exception) {}
    fila.clear(); fila.offer(ByteArray(0))
    try { somThread?.join(1000) } catch (_: Exception) {}
    try { track?.stop(); track?.release() } catch (_: Exception) {}
    track = null
    try { primeira?.delete() } catch (_: Exception) {}
  }

  private fun evento(tipo: String, valor: Any?) {
    val o = JSONObject().put("type", "nativo.$tipo").put("valor", valor)
    js(o.toString())
  }

  private fun receber(texto: String) {
    val ev = try { JSONObject(texto) } catch (e: Exception) { return }
    when (ev.optString("type")) {
      "session.created" -> {
        ouvir()
        val f = primeira
        if (f != null) { mandarAudio(f.readBytes()); primeira = null; try { f.delete() } catch (_: Exception) {} }
        else ws?.send(JSONObject().put("type", "response.create").put("response", JSONObject()
          .put("instructions", "Cumprimente o Guilherme (pelo nome, nunca de chefe) em uma frase curta e animada e pergunte em que pode ajudar.")).toString())
      }
      "response.created" -> respostaAtiva = true
      "response.done" -> respostaAtiva = false
      "response.output_audio.delta", "response.audio.delta" -> {
        val b = Base64.decode(ev.optString("delta"), Base64.DEFAULT)
        if (!falando) { falando = true; evento("estado", "falando") }
        fila.offer(b)
        return   // audio nao vai para a tela (so peso)
      }
      "response.output_audio.done", "response.audio.done" -> fila.offer(FIM)
    }
    js(texto)   // a tela mostra transcricoes e trata as ferramentas
  }

  /* toca a resposta (PCM 24 kHz) */
  private fun tocar() {
    val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    track = AudioTrack.Builder()
      .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
      .setBufferSizeInBytes(maxOf(min, 24000)).setTransferMode(AudioTrack.MODE_STREAM).build()
    track?.play()
    somThread = Thread {
      while (ativo) {
        val b = try { fila.poll(300, TimeUnit.MILLISECONDS) } catch (e: Exception) { null } ?: continue
        if (b === FIM) { if (fila.isEmpty()) { Thread.sleep(250); falando = false; evento("estado", "ouvindo") }; continue }
        if (b.isEmpty()) continue
        try { track?.write(b, 0, b.size) } catch (_: Exception) {}
      }
    }.also { it.start() }
  }

  /* corta a fala dela (ele falou por cima com a voz dele) */
  private fun calar() {
    if (respostaAtiva) ws?.send("{\"type\":\"response.cancel\"}")
    fila.clear()
    try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
    if (falando) { falando = false; evento("estado", "ouvindo") }
  }

  /* microfone: Vosk acha o fim da frase; a biometria decide se manda */
  @SuppressLint("MissingPermission")
  private fun ouvir() {
    val m = Modelos.pt ?: run { evento("erro", "Modelo de voz não carregado."); return }
    micThread = Thread {
      var rec: AudioRecord? = null; var r: Recognizer? = null
      try {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
        /* 06/11 19h: sem filtros do Android aqui — o cadastro da voz e feito sem eles; filtro muda a impressao da voz */
        r = Recognizer(m, 16000f)
        Modelos.spk?.let { try { r.setSpeakerModel(it) } catch (_: Exception) {} }
        rec.startRecording()
        val buf = ByteArray(3200)
        val frase = ByteArrayOutputStream()
        while (ativo) {
          val n = rec.read(buf, 0, buf.size)
          if (n <= 0) continue
          frase.write(buf, 0, n)
          if (frase.size() > 16000 * 2 * 25) { val b = frase.toByteArray(); frase.reset(); frase.write(b, b.size - 16000 * 2 * 15, 16000 * 2 * 15) }
          if (r.acceptWaveForm(buf, n)) {
            val res = r.result
            val audio = frase.toByteArray(); frase.reset()
            avaliar(res, audio)
          }
        }
      } catch (e: Exception) {
        evento("erro", "Microfone: ${e.message}")
      } finally {
        try { rec?.stop(); rec?.release() } catch (_: Exception) {}
        try { r?.close() } catch (_: Exception) {}
      }
    }.also { it.start() }
  }

  private fun avaliar(json: String, audio: ByteArray) {
    val o = try { JSONObject(json) } catch (e: Exception) { return }
    val texto = o.optString("text", "").trim()
    val (v, frames) = VozPerfil.vetor(json)
    if (texto.isEmpty() || frames < 35) return            // ruido curto
    if (VozPerfil.perfil(ctx) != null) {
      val (sim, ok) = VozPerfil.verificar(ctx, v, frames)
      VozPerfil.registrar(ctx, "conversa: " + "%.2f".format(sim) + (if (ok) " ✓" else " ✗") + " · ${frames / 100.0}s")
      if (!ok) { evento("ignorado", "%.2f".format(sim)); return }
    }
    if (falando || respostaAtiva) calar()
    mandarAudio(audio)
  }

  /* 16 kHz -> 24 kHz (PCM16 mono) e manda como um turno */
  private fun mandarAudio(pcm16k: ByteArray) {
    val w = ws ?: return
    val n = pcm16k.size / 2
    val out = ByteArray((n * 3 / 2) * 2)
    for (i in 0 until n * 3 / 2) {
      val pos = i * 2.0 / 3.0
      val a = pos.toInt().coerceAtMost(n - 1); val b = (a + 1).coerceAtMost(n - 1); val f = pos - a
      val sa = ((pcm16k[2 * a + 1].toInt() shl 8) or (pcm16k[2 * a].toInt() and 0xff)).toShort().toInt()
      val sb = ((pcm16k[2 * b + 1].toInt() shl 8) or (pcm16k[2 * b].toInt() and 0xff)).toShort().toInt()
      val s = (sa + (sb - sa) * f).toInt()
      out[2 * i] = (s and 0xff).toByte(); out[2 * i + 1] = ((s shr 8) and 0xff).toByte()
    }
    var p = 0
    while (p < out.size) {
      val t = minOf(48000, out.size - p)
      w.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", Base64.encodeToString(out, p, t, Base64.NO_WRAP)).toString())
      p += t
    }
    w.send("{\"type\":\"input_audio_buffer.commit\"}")
    w.send("{\"type\":\"response.create\"}")
    evento("estado", "pensando")
  }

  companion object { private val FIM = ByteArray(1) }
}
