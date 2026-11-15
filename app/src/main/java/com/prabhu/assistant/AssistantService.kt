package com.prabhu.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast

/** Listens in the background for "Hey Prabhu" and opens the app when it hears it. */
class AssistantService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var running = false
    private var paused = false
    private var partialWake: Runnable? = null

    // Loose on purpose: a soft voice often comes out as a half-heard word, so "Prabhu" alone is enough.
    private val wake = Regex(
        "(prabhu|prabu|prabhoo|probhu|prabho|prabhav|brabhu|ప్రభు|ప్రభూ|ప్రభ)",
        RegexOption.IGNORE_CASE
    )

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { shutdown(); return START_NOT_STICKY }
            "PAUSE" -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                paused = true
                clearTimers()
                stopRec()
                return START_STICKY
            }
            "RESUME" -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                if (paused) {
                    paused = false
                    main.postDelayed({ listen() }, 700)
                }
                return START_STICKY
            }
        }
        if (running) return START_STICKY
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition is not available on this phone", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        startAsForeground()
        paused = AppState.visible
        if (!paused) listen()
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("prabhu", "Prabhu", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "prabhu")
            .setContentTitle("Prabhu is listening")
            .setContentText("Say \"Hey Prabhu\" to open")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun stopRec() {
        rec?.destroy()
        rec = null
    }

    private fun clearTimers() {
        main.removeCallbacksAndMessages(null)
        partialWake = null
    }

    private fun listen() {
        if (!running || paused) return
        stopRec()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onError(e: Int) { main.post { onRecError(e) } }
            override fun onPartialResults(b: Bundle?) {
                val l = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                if (l.any { wake.containsMatchIn(it) }) main.post { schedulePartialWake() }
            }
            override fun onResults(b: Bundle?) {
                val l = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: ArrayList<String>()
                main.post { onHeard(l) }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Store.lang(this@AssistantService))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        r.startListening(i)
    }

    /** A half-heard "Prabhu" opens the app after a moment, unless the final result arrives first. */
    private fun schedulePartialWake() {
        if (!running || paused || partialWake != null) return
        val t = Runnable { partialWake = null; openApp("") }
        partialWake = t
        main.postDelayed(t, 1000)
    }

    private fun onRecError(e: Int) {
        if (!running || paused) return
        if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            Toast.makeText(this, "Microphone permission needed for Hey Prabhu", Toast.LENGTH_LONG).show()
            shutdown()
            return
        }
        val wait = if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) 1500L else 300L
        main.postDelayed({ listen() }, wait)
    }

    private fun onHeard(list: List<String>) {
        if (!running || paused) return
        partialWake?.let { main.removeCallbacks(it) }
        partialWake = null
        for (text in list) {
            val m = wake.find(text) ?: continue
            val rest = text.substring(m.range.last + 1).trim(' ', ',', '.')
            openApp(if (rest.length > 2) rest else "")
            return
        }
        listen()
    }

    private fun openApp(cmd: String) {
        paused = true
        clearTimers()
        stopRec()
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("wake", true)
            .putExtra("cmd", cmd)
        try { startActivity(i) } catch (e: Exception) { paused = false }
        // If the app could not come to the front, start listening again.
        main.postDelayed({
            if (running && paused && !AppState.visible) { paused = false; listen() }
        }, 5000)
    }

    private fun shutdown() {
        running = false
        clearTimers()
        stopRec()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        stopRec()
        super.onDestroy()
    }
}
