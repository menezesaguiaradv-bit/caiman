package br.com.caimanagro.bia

import android.content.Context
import android.content.Intent

/* Abre a Bia. 05/11: em vez do Chrome, abre a tela Conversa do proprio app, ja em conversa ao vivo. */
object Abrir {
  const val URL = "https://menezesaguiaradv-bit.github.io/caiman/bia.html?ouvir=1"
  const val URL_AO_VIVO = "https://menezesaguiaradv-bit.github.io/caiman/bia.html?aovivo=1"
  fun bia(ctx: Context) {
    ctx.startActivity(Intent(ctx, Conversa::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
  }
}
