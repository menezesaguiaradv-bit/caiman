package br.com.caimanagro.bia

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/* 06/11 (ele: "quero que a Bia comece a operar com o celular bloqueado, mas a gente pode pensar em fazer alguma
   biometria vocal para so responder a mim"). Biometria de voz NO CELULAR: o modelo de locutor do Vosk
   (vosk-model-spk-0.4, offline) tira de cada frase uma "impressao da voz" (vetor de 128 numeros). No cadastro ele
   le 6 frases; a media vira o perfil. Ao ouvir "Bia", compara a frase com o perfil (similaridade de cosseno):
   abaixo do limiar, a Bia nao abre. Nada sai do aparelho. */
object VozPerfil {
  private fun p(c: Context) = c.getSharedPreferences("bia", Context.MODE_PRIVATE)

  /* 06/11 19h (ele: "a Bia nao esta reconhecendo minha voz"): o cadastro era so de frases longas (2-3 s) e ele chama
     dizendo so "Bia" (meio segundo): a impressao de um trecho curto sai bem diferente e a nota caia abaixo do limiar.
     Agora ha DOIS perfis: o curto (ele diz "Bia" 4 vezes) para a chamada, e o longo para as frases da conversa. */
  fun perfil(c: Context, curto: Boolean = false): DoubleArray? = p(c).getString(if (curto) "voz_perfil_curto" else "voz_perfil", null)?.let { s ->
    try { val a = JSONArray(s); DoubleArray(a.length()) { a.getDouble(it) } } catch (e: Exception) { null }
  }
  fun limiar(c: Context, curto: Boolean = false): Double = p(c).getFloat(if (curto) "voz_limiar_curto" else "voz_limiar", if (curto) 0.35f else 0.45f).toDouble()
  fun ajustarLimiar(c: Context, delta: Double) {
    for (k in listOf(false, true)) {
      val n = (limiar(c, k) + delta).coerceIn(0.15, 0.85)
      p(c).edit().putFloat(if (k) "voz_limiar_curto" else "voz_limiar", n.toFloat()).apply()
    }
  }
  /** confere a voz: frase curta (menos de 1,2 s) usa o perfil curto, se existir */
  fun verificar(c: Context, v: DoubleArray?, frames: Int): Pair<Double, Boolean> {
    if (v == null) return Pair(-1.0, false)
    val curto = frames < 120 && perfil(c, true) != null
    val pf = perfil(c, curto) ?: perfil(c) ?: return Pair(-1.0, false)
    val sim = cos(v, pf)
    return Pair(sim, sim >= limiar(c, curto))
  }
  fun ultima(c: Context): String = p(c).getString("voz_ultima", "") ?: ""
  /* guarda as 5 ultimas notas (a tela do app mostra, para ele calibrar) */
  fun registrar(c: Context, txt: String) {
    val h = (p(c).getString("voz_ultima", "") ?: "").split("\n").filter { it.isNotBlank() }
    p(c).edit().putString("voz_ultima", (listOf(txt) + h).take(5).joinToString("\n")).apply()
  }
  fun apagar(c: Context) { p(c).edit().remove("voz_perfil").remove("voz_perfil_curto").remove("voz_ultima").apply() }

  fun salvar(c: Context, amostras: List<DoubleArray>, curto: Boolean = false): Pair<Double, Double> {
    val dim = amostras[0].size
    val media = DoubleArray(dim)
    for (a in amostras) { val u = unit(a); for (i in 0 until dim) media[i] += u[i] / amostras.size }
    val sims = amostras.map { cos(it, media) }
    /* limiar automatico: um pouco abaixo da frase cadastrada mais "diferente" (mas entre 0,30 e 0,65) */
    val lim = if (curto) (sims.minOrNull()!! - 0.15).coerceIn(0.20, 0.55) else (sims.minOrNull()!! - 0.12).coerceIn(0.30, 0.65)
    p(c).edit().putString(if (curto) "voz_perfil_curto" else "voz_perfil", JSONArray(media.toList()).toString())
      .putFloat(if (curto) "voz_limiar_curto" else "voz_limiar", lim.toFloat()).apply()
    return Pair(sims.average(), lim)
  }

  fun vetor(json: String?): Pair<DoubleArray?, Int> {
    return try {
      val o = JSONObject(json ?: "{}")
      val a = o.optJSONArray("spk") ?: return Pair(null, 0)
      Pair(DoubleArray(a.length()) { a.getDouble(it) }, o.optInt("spk_frames", 0))
    } catch (e: Exception) { Pair(null, 0) }
  }

  fun unit(a: DoubleArray): DoubleArray { val n = sqrt(a.sumOf { it * it }).takeIf { it > 0 } ?: 1.0; return DoubleArray(a.size) { a[it] / n } }
  fun cos(a: DoubleArray, b: DoubleArray): Double {
    if (a.size != b.size) return 0.0
    var s = 0.0; var na = 0.0; var nb = 0.0
    for (i in a.indices) { s += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    return if (na == 0.0 || nb == 0.0) 0.0 else s / (sqrt(na) * sqrt(nb))
  }

  /* copia o modelo de locutor dos assets para a memoria do app uma vez (a SpeakerModel precisa de um caminho) */
  fun caminhoModelo(c: Context): String {
    val dst = File(c.filesDir, "model-spk")
    val marca = File(dst, ".ok-0.4")
    if (!marca.exists()) { dst.deleteRecursively(); copiar(c, "model-spk", dst); marca.writeText("ok") }
    return dst.absolutePath
  }
  private fun copiar(c: Context, src: String, dst: File) {
    val filhos = c.assets.list(src) ?: emptyArray()
    if (filhos.isEmpty()) {
      dst.parentFile?.mkdirs()
      c.assets.open(src).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
    } else { dst.mkdirs(); for (f in filhos) copiar(c, "$src/$f", File(dst, f)) }
  }
}
