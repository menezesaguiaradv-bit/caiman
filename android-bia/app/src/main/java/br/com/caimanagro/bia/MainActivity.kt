package br.com.caimanagro.bia

/* 31/10/2026 (ele: "quero que faca igual o OK Google. Ela nao precisa ficar ouvindo sempre, mas quando eu disser Bia,
   ela abre e comeca a falar comigo"). Tela unica: liga/desliga a escuta da palavra "Bia" e pede as permissoes
   (microfone, notificacao e "aparecer sobre outros apps", sem a qual o Android nao deixa abrir a Bia com a tela em
   outro app). A palavra e reconhecida NO CELULAR (Vosk, modelo pt offline): nada sai do aparelho ate abrir a Bia. */

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
  private lateinit var estado: TextView
  private lateinit var botao: Button

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48); setBackgroundColor(0xFF06100B.toInt()) }
    val tit = TextView(this).apply { text = "Friday"; textSize = 40f; setTextColor(0xFFF7F4EC.toInt()); gravity = Gravity.CENTER }
    estado = TextView(this).apply { textSize = 16f; setTextColor(0xFFB8944F.toInt()); gravity = Gravity.CENTER; setPadding(0, 24, 0, 24) }
    botao = Button(this).apply { setOnClickListener { alternar() } }
    val abrir = Button(this).apply { text = "Abrir a Friday agora"; setOnClickListener { Abrir.bia(this@MainActivity) } }
    val ajuda = TextView(this).apply {
      textSize = 13f; setTextColor(0xFFC9C3B0.toInt()); gravity = Gravity.CENTER; setPadding(0, 32, 0, 0)
      text = "Com a escuta ligada, diga \"Friday\" e ela abre já ouvindo.\nA palavra é reconhecida no próprio celular; nada é enviado antes de a Friday abrir.\nPara a Friday abrir por cima de outros apps, permita \"Aparecer sobre outros apps\"."
    }
    l.addView(tit); l.addView(estado); l.addView(botao); l.addView(abrir); l.addView(ajuda)
    setContentView(l)
    pedirPermissoes()
    atualizar()
  }

  override fun onResume() { super.onResume(); atualizar() }

  private fun pedirPermissoes() {
    val faltam = mutableListOf<String>()
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) faltam += Manifest.permission.RECORD_AUDIO
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) faltam += Manifest.permission.POST_NOTIFICATIONS
    if (faltam.isNotEmpty()) requestPermissions(faltam.toTypedArray(), 1)
    if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
  }

  private fun alternar() {
    val p = getSharedPreferences("bia", MODE_PRIVATE)
    val ligar = !p.getBoolean("ligado", false)
    p.edit().putBoolean("ligado", ligar).apply()
    val i = Intent(this, OuvirService::class.java)
    if (ligar) startForegroundService(i) else stopService(i)
    atualizar()
  }

  private fun atualizar() {
    val ligado = getSharedPreferences("bia", MODE_PRIVATE).getBoolean("ligado", false)
    estado.text = if (ligado) "Ouvindo a palavra \"Friday\"" else "Escuta desligada"
    botao.text = if (ligado) "Desligar a escuta" else "Ligar a escuta"
  }
}
