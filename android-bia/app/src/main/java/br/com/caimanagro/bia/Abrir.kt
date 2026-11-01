package br.com.caimanagro.bia

import android.content.Context
import android.content.Intent
import android.net.Uri

/* Abre a Bia ja ouvindo. Se o app "Bia" (instalado pelo Chrome) existir, o Android abre nele; senao abre no Chrome. */
object Abrir {
  const val URL = "https://menezesaguiaradv-bit.github.io/caiman/bia.html?ouvir=1"
  fun bia(ctx: Context) {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(i)
  }
}
