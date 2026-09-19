package com.webook.watch.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** 系统 TTS 封装（不需要 WebView） */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    var onDone: (() -> Unit)? = null
    var rate: Float = 0.95f

    val available: Boolean get() = ready

    init {
        try { tts = TextToSpeech(context, this) } catch (e: Exception) { ready = false }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { ready = false; return }
        val t = tts ?: return
        val r = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.getDefault())     // 中文引擎缺失就用系统默认
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone?.invoke() }
            @Deprecated("legacy") override fun onError(utteranceId: String?) { onDone?.invoke() }
        })
        ready = true
    }

    fun speak(text: String) {
        val t = tts ?: return
        if (!ready) return
        t.setSpeechRate(rate)
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, 3) }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, params, "wearbook-page")
    }

    fun stop() { try { tts?.stop() } catch (e: Exception) {} }

    fun shutdown() { stop(); try { tts?.shutdown() } catch (e: Exception) {} }
}
