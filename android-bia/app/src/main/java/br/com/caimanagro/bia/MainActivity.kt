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
  private lateinit var voz: TextView
  private lateinit var cadastrar: Button

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48); setBackgroundColor(0xFF0E0505.toInt()) }
    val tit = TextView(this).apply { text = "Bia"; textSize = 40f; setTextColor(0xFFF7F4EC.toInt()); gravity = Gravity.CENTER }
    estado = TextView(this).apply { textSize = 16f; setTextColor(0xFFE8B54A.toInt()); gravity = Gravity.CENTER; setPadding(0, 24, 0, 24) }
    botao = Button(this).apply { setOnClickListener { alternar() } }
    val abrir = Button(this).apply { text = "Abrir a Bia agora"; setOnClickListener { Abrir.bia(this@MainActivity) } }
    val ajuda = TextView(this).apply {
      textSize = 13f; setTextColor(0xFFC9C3B0.toInt()); gravity = Gravity.CENTER; setPadding(0, 32, 0, 0)
      text = "Com a escuta ligada, diga \"Bia\" e ela abre já ouvindo.\nA palavra é reconhecida no próprio celular; nada é enviado antes de a Bia abrir.\nPara a Bia abrir por cima de outros apps, permita \"Aparecer sobre outros apps\"."
    }
    /* 06/11: biometria de voz — so a voz do chefe abre a Bia (e so ela abre com o celular bloqueado) */
    voz = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFF4E6.toInt()); gravity = Gravity.CENTER; setPadding(0, 40, 0, 12) }
    cadastrar = Button(this).apply { setOnClickListener { startActivity(Intent(this@MainActivity, CadastroVoz::class.java)) } }
    val ajuste = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    ajuste.addView(Button(this).apply { text = "Mais rígida"; setOnClickListener { VozPerfil.ajustarLimiar(this@MainActivity, 0.05); atualizar() } })
    ajuste.addView(Button(this).apply { text = "Mais solta"; setOnClickListener { VozPerfil.ajustarLimiar(this@MainActivity, -0.05); atualizar() } })
    val apagar = Button(this).apply { text = "Apagar minha voz"; setOnClickListener { VozPerfil.apagar(this@MainActivity); atualizar() } }
    l.addView(tit); l.addView(estado); l.addView(botao); l.addView(abrir); l.addView(voz); l.addView(cadastrar); l.addView(ajuste); l.addView(apagar); l.addView(ajuda)
    setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(0xFF0E0505.toInt()); addView(l) })
    pedirPermissoes()
    atualizar()
  }

  override fun onResume() {
    super.onResume()
    /* app aberto: reinicia a escuta com o microfone liberado (o Android so da microfone a servico iniciado com o app na tela) */
    if (getSharedPreferences("bia", MODE_PRIVATE).getBoolean("ligado", false))
      try { startForegroundService(Intent(this, OuvirService::class.java).setAction("reiniciar")) } catch (_: Exception) {}
    atualizar()
  }

  private fun pedirPermissoes() {
    val faltam = mutableListOf<String>()
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) faltam += Manifest.permission.RECORD_AUDIO
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) faltam += Manifest.permission.POST_NOTIFICATIONS
    if (faltam.isNotEmpty()) requestPermissions(faltam.toTypedArray(), 1)
    if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    /* 06/11 18h50: sem isso a economia de bateria da Samsung encerra a escuta com o app fechado */
    else if (!getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
      try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
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
    val pf = getSharedPreferences("bia", MODE_PRIVATE)
    val vivo = System.currentTimeMillis() - pf.getLong("ouvindo_em", 0) < 90_000
    val bat = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    estado.text = if (!ligado) "Escuta desligada"
      else (if (pf.getBoolean("mic_mudo", false)) "⚠️ O Android pausou o microfone. Deixe o app aberto alguns segundos."
        else if (vivo) "✅ Ouvindo a palavra \"Bia\"" else "Ligando a escuta…") +
        (if (!bat) "\n⚠️ Bateria: libere a Bia (Configurações › Aplicativos › Bia › Bateria › Sem restrições)" else "")
    botao.text = if (ligado) "Desligar a escuta" else "Ligar a escuta"
    val tem = VozPerfil.perfil(this) != null
    val ult = VozPerfil.ultima(this)
    voz.text = if (tem) "🔒 Voz cadastrada — só você abre a Bia, inclusive com o celular bloqueado.\nRigidez: ${"%.2f".format(VozPerfil.limiar(this))}" + (if (ult.isNotEmpty()) "\nÚltima chamada: $ult" else "")
      else "Voz não cadastrada: a Bia só abre com o celular desbloqueado.\nCadastre a sua voz para ela abrir com o celular bloqueado e só para você."
    cadastrar.text = if (tem) "Cadastrar minha voz de novo" else "Cadastrar minha voz"
  }
}
