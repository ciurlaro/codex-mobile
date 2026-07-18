package io.github.ciurlaro.codexmobile.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

class CodexForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? =
        TODO("Step 05: define binding only after visible-Activity execution proves insufficient")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        TODO("Step 05: validate explicit actions, publish notification, and own active work")
}
