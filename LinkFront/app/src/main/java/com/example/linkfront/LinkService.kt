package com.example.linkfront

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

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
        
        val notification = notificationHelper.getServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
        }

        initManager()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            Log.d(tag, "Stop signal received via notification")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(tag, "Network available, triggering DHT refresh")
                webrtcManager?.publishMyAddress()
            }

            override fun onLost(network: Network) {
                Log.d(tag, "Network lost")
            }
        })
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
