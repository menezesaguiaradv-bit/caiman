package br.com.caimanagro.bia

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.vosk.Model
import org.vosk.SpeakerModel
import org.vosk.android.StorageService

/* 06/11: os modelos do Vosk (portugues + locutor) carregados UMA vez no app e usados pela escuta do "Bia" e pela
   conversa ao vivo (antes cada um carregava o seu). */
object Modelos {
  @Volatile var pt: Model? = null
  @Volatile var spk: SpeakerModel? = null
  private val esperando = mutableListOf<(String?) -> Unit>()
  private var carregando = false
  private val main = Handler(Looper.getMainLooper())

  /** chama pronto(null) quando os modelos estao prontos, ou pronto(erro) */
  fun carregar(ctx: Context, pronto: (String?) -> Unit) {
    synchronized(this) {
      if (pt != null) { main.post { pronto(null) }; return }
      esperando += pronto
      if (carregando) return
      carregando = true
    }
    val app = ctx.applicationContext
    StorageService.unpack(app, "model-pt", "model", { m ->
      Thread {
        val s = try { SpeakerModel(VozPerfil.caminhoModelo(app)) } catch (e: Exception) { null }
        val l = synchronized(this) { pt = m; spk = s; carregando = false; esperando.toList().also { esperando.clear() } }
        main.post { l.forEach { it(null) } }
      }.start()
    }, { e ->
      val l = synchronized(this) { carregando = false; esperando.toList().also { esperando.clear() } }
      main.post { l.forEach { it(e.message ?: "erro") } }
    })
  }
}
