package br.com.caimanagro.bia

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

/* 05/11 22h23 (print dele: a lista "App de assistente digital padrao" da Samsung so mostra Google, ChatGPT, Claude e
   Meta AI): essa lista so aceita quem e um assistente "de sistema" (VoiceInteractionService). Aqui esta o minimo
   que o Android exige: o servico, a sessao (que abre a Friday ja ouvindo e some) e um reconhecedor vazio. */
class Voz : VoiceInteractionService()

class Sessao : VoiceInteractionSessionService() {
  override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
    override fun onShow(args: Bundle?, showFlags: Int) {
      super.onShow(args, showFlags)
      val i = Intent(context, Conversa::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      try { startAssistantActivity(i) } catch (e: Exception) { try { context.startActivity(i) } catch (_: Exception) {} }
      hide()
    }
  }
}

class Reconhece : RecognitionService() {
  override fun onStartListening(intent: Intent?, cb: Callback?) { try { cb?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) {} }
  override fun onCancel(cb: Callback?) {}
  override fun onStopListening(cb: Callback?) {}
}
