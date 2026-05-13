package com.linkfront

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class LinkService : Service() {
    private val tag = "LinkService"
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private lateinit var notificationHelper: NotificationHelper
    private var multicastLock: WifiManager.MulticastLock? = null
    
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

        acquireMulticastLock()
        
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
        
        // Use a notification immediately on start for Android 12 compliance
        val notification = notificationHelper.getServiceNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start foreground service: ${e.message}")
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
                val caps = connectivityManager.getNetworkCapabilities(network)
                val type = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                    else -> "Other"
                }
                
                Log.d(tag, "Network available ($type), triggering DHT refresh")
                
                // When network changes, our IP likely changed. Refresh immediately.
                scope.launch {
                    webrtcManager?.publishMyAddress()
                }
                
                // Re-acquire lock if needed when network changes
                acquireMulticastLock()
            }

            override fun onLost(network: Network) {
                Log.w(tag, "Network connection lost!")
                webrtcManager?.onStateChanged?.invoke("Offline")
            }
        })
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("LinkDHTLock")
                multicastLock?.setReferenceCounted(true)
            }
            multicastLock?.acquire()
            Log.d(tag, "Multicast lock acquired")
        } catch (e: Exception) {
            Log.e(tag, "Failed to acquire multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(tag, "Multicast lock released")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error releasing multicast lock: ${e.message}")
        }
    }

    private fun initManager() {
        val database = AppDatabase.getDatabase(this)
        val identityManager = LinkIdentityManager(this)
        val myFingerprint = identityManager.fingerprint
        val dhtCachePath = File(filesDir, "dht_neighbors.pkl").absolutePath

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
        
        // Initialize DHT and publish address immediately
        scope.launch {
            val dhtNode = Python.getInstance().getModule("linkfront.dht_node")
            dhtNode.callAttr("initialize_dht", myFingerprint, dhtCachePath)
            
            // Revert to aggressive immediate publish (like in commit 78a4650e)
            // The SignalingClient heartbeat will ensure it keeps trying
            webrtcManager?.publishMyAddress()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMulticastLock()
        webrtcManager?.destroy()
        job.cancel()
        Log.d(tag, "Service Destroyed")
    }
}
