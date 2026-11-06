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

  fun perfil(c: Context): DoubleArray? = p(c).getString("voz_perfil", null)?.let { s ->
    try { val a = JSONArray(s); DoubleArray(a.length()) { a.getDouble(it) } } catch (e: Exception) { null }
  }
  fun limiar(c: Context): Double = p(c).getFloat("voz_limiar", 0.45f).toDouble()
  fun ajustarLimiar(c: Context, delta: Double) {
    val n = (limiar(c) + delta).coerceIn(0.20, 0.85)
    p(c).edit().putFloat("voz_limiar", n.toFloat()).apply()
  }
  fun ultima(c: Context): String = p(c).getString("voz_ultima", "") ?: ""
  fun registrar(c: Context, txt: String) { p(c).edit().putString("voz_ultima", txt).apply() }
  fun apagar(c: Context) { p(c).edit().remove("voz_perfil").remove("voz_ultima").apply() }

  fun salvar(c: Context, amostras: List<DoubleArray>): Pair<Double, Double> {
    val dim = amostras[0].size
    val media = DoubleArray(dim)
    for (a in amostras) { val u = unit(a); for (i in 0 until dim) media[i] += u[i] / amostras.size }
    val sims = amostras.map { cos(it, media) }
    /* limiar automatico: um pouco abaixo da frase cadastrada mais "diferente" (mas entre 0,30 e 0,65) */
    val lim = (sims.minOrNull()!! - 0.12).coerceIn(0.30, 0.65)
    p(c).edit().putString("voz_perfil", JSONArray(media.toList()).toString()).putFloat("voz_limiar", lim.toFloat()).apply()
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
