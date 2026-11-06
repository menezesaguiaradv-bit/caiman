package br.com.caimanagro.bia

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/* 06/11: cadastro da voz do chefe. Ele le 6 frases (todas comecam com "Bia", como ele vai chamar); cada frase de
   pelo menos 1 segundo vira uma amostra. A escuta da palavra fica pausada durante o cadastro. */
class CadastroVoz : Activity(), RecognitionListener {
  private val frases = listOf(
    "Bia, é o chefe.",
    "Bia, como está o caixa hoje?",
    "Bia, quanto tem de milho no estoque?",
    "Bia, abre o painel dos currais.",
    "Bia, qual a arroba em São Paulo?",
    "Bia, obrigado, pode parar.",
  )
  private val amostras = mutableListOf<DoubleArray>()
  private lateinit var guia: TextView
  private lateinit var frase: TextView
  private var modelo: Model? = null
  private var spk: SpeakerModel? = null
  private var fala: SpeechService? = null
  private val h = Handler(Looper.getMainLooper())

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48); setBackgroundColor(0xFF0E0505.toInt()) }
    guia = TextView(this).apply { textSize = 16f; setTextColor(0xFFE8B54A.toInt()); gravity = Gravity.CENTER; text = "Preparando…" }
    frase = TextView(this).apply { textSize = 26f; setTextColor(0xFFFFF4E6.toInt()); gravity = Gravity.CENTER; setPadding(0, 40, 0, 40) }
    val cancelar = Button(this).apply { text = "Cancelar"; setOnClickListener { finish() } }
    l.addView(guia); l.addView(frase); l.addView(cancelar)
    setContentView(l)
    servico("pausar")
    Thread {
      try { spk = SpeakerModel(VozPerfil.caminhoModelo(this)) } catch (e: Exception) { runOnUiThread { guia.text = "Não consegui carregar a biometria: ${e.message}" }; return@Thread }
      runOnUiThread {
        StorageService.unpack(this, "model-pt", "model", { m -> modelo = m; h.postDelayed({ comecar() }, 800) },
          { e -> guia.text = "Não consegui carregar o modelo de voz: ${e.message}" })
      }
    }.start()
  }

  private fun comecar() {
    val m = modelo ?: return; val s = spk ?: return
    try {
      val r = Recognizer(m, 16000f, s)
      fala = SpeechService(r, 16000f).also { it.startListening(this) }
      mostrar()
    } catch (e: Exception) { guia.text = "Microfone ocupado. Feche a Bia e tente de novo." }
  }

  private fun mostrar() {
    guia.text = "Leia em voz alta, no seu jeito normal (${amostras.size + 1} de ${frases.size}):"
    frase.text = "“${frases[amostras.size]}”"
  }

  private fun ouviu(json: String?) {
    if (amostras.size >= frases.size) return
    val (v, frames) = VozPerfil.vetor(json)
    if (v == null || frames < 100) { if (v != null) guia.text = "Curta demais. Leia a frase inteira:"; return }
    amostras += v
    if (amostras.size < frases.size) { mostrar(); return }
    fala?.stop()
    val (media, lim) = VozPerfil.salvar(this, amostras)
    guia.text = "Pronto! Sua voz está cadastrada."
    frase.text = "Consistência: ${"%.2f".format(media)} · limiar: ${"%.2f".format(lim)}"
    h.postDelayed({ finish() }, 2500)
  }

  override fun onResult(hyp: String?) = ouviu(hyp)
  override fun onFinalResult(hyp: String?) = ouviu(hyp)
  override fun onPartialResult(hyp: String?) {}
  override fun onError(e: Exception?) { guia.text = "Erro no microfone: ${e?.message}" }
  override fun onTimeout() {}

  private fun servico(acao: String) {
    if (getSharedPreferences("bia", MODE_PRIVATE).getBoolean("ligado", false)) {
      try { startService(Intent(this, OuvirService::class.java).setAction(acao)) } catch (e: Exception) {}
    }
  }

  override fun onDestroy() {
    h.removeCallbacksAndMessages(null)
    try { fala?.stop(); fala?.shutdown() } catch (e: Exception) {}
    try { spk?.close() } catch (e: Exception) {}
    try { modelo?.close() } catch (e: Exception) {}
    servico("retomar")
    super.onDestroy()
  }
}
