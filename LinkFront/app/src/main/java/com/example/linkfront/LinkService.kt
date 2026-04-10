package com.example.linkfront

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.content.pm.ServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class LinkService : Service() {
    private val tag = "LinkService"
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private lateinit var notificationHelper: NotificationHelper
    
    var webrtcManager: WebRTCManager? = null
        private set

    var connectionRequestListener: ((String, String, ByteArray, (Boolean) -> Unit) -> Unit)? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): LinkService = this@LinkService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Service Creating")
        notificationHelper = NotificationHelper(this)
        initManager()
    }

    private fun initManager() {
        val database = AppDatabase.getDatabase(this)
        webrtcManager = WebRTCManager(
            context = this,
            messageDao = database.messageDao(),
            peerDao = database.peerDao(),
            onConnectionRequest = { username, fingerprint, idKey, callback ->
                val listener = connectionRequestListener
                if (listener != null) {
                    listener(username, fingerprint, idKey, callback)
                } else {
                    notificationHelper.showPairingNotification(username)
                }
            },
            onMessageReceived = { message ->
                notificationHelper.showChatNotification(webrtcManager?.peerUsername ?: "New Message", message)
            },
            onStateChanged = { state ->
                Log.d(tag, "WebRTC State: $state")
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        Log.d(tag, "Service Destroyed")
    }
}
