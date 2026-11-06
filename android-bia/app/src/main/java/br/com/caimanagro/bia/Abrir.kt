package br.com.caimanagro.bia

import android.content.Context
import android.content.Intent

/* Abre a Bia. 05/11: em vez do Chrome, abre a tela Conversa do proprio app, ja em conversa ao vivo.
   06/11: voz=true quando a biometria de voz reconheceu o chefe; so assim a Conversa abre por cima da tela bloqueada. */
object Abrir {
  const val URL = "https://menezesaguiaradv-bit.github.io/caiman/bia.html?ouvir=1"
  const val URL_AO_VIVO = "https://menezesaguiaradv-bit.github.io/caiman/bia.html?aovivo=1"
  fun bia(ctx: Context, voz: Boolean = false) {
    ctx.startActivity(Intent(ctx, Conversa::class.java).putExtra("voz", voz)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
  }
}
