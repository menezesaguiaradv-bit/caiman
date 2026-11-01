package br.com.caimanagro.bia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/* Celular reiniciou: se a escuta estava ligada, liga de novo. */
class Inicio : BroadcastReceiver() {
  override fun onReceive(c: Context, i: Intent) {
    if (i.action == Intent.ACTION_BOOT_COMPLETED && c.getSharedPreferences("bia", Context.MODE_PRIVATE).getBoolean("ligado", false))
      c.startForegroundService(Intent(c, OuvirService::class.java))
  }
}
