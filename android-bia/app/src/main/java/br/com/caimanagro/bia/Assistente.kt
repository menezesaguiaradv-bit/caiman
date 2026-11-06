package br.com.caimanagro.bia

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/* 05/11 (ele: "queria que ela fosse tipo o ok google do android"): a Friday aparece em Configuracoes > Apps >
   Apps padrao > Assistente digital. Escolhida la, o gesto de assistente do Android (segurar o botao Inicio, ou
   arrastar do canto de baixo da tela, ou segurar a tecla lateral se configurada) abre a Friday ja ouvindo, no lugar
   do Google. A escuta da palavra "Friday", se estiver ligada, solta o microfone antes. */
class Assistente : Activity() {
  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    Abrir.bia(this)
    finish()
  }
}
