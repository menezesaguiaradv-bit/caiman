package br.com.caimanagro.bia

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/* 06/11 20h15 (ele: print da barra do Google na tela inicial, "e se colocasse um atalho da Bia igual esse do google"):
   barra da Bia para a tela inicial. Microfone = abre ja em conversa ao vivo; o resto da barra = abre a Bia para
   digitar (sem ligar o microfone). */
class BiaWidget : AppWidgetProvider() {
  override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
    val v = RemoteViews(c.packageName, R.layout.bia_widget)
    val mic = PendingIntent.getActivity(c, 10, Intent(c, Conversa::class.java).putExtra("modo", "aovivo")
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val texto = PendingIntent.getActivity(c, 11, Intent(c, Conversa::class.java).putExtra("modo", "texto")
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    v.setOnClickPendingIntent(R.id.w_mic, mic)
    v.setOnClickPendingIntent(R.id.w_barra, texto)
    for (id in ids) m.updateAppWidget(id, v)
  }
}
