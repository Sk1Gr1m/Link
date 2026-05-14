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

// Foreground service to maintain DHT and WebRTC connectivity in the background
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
        
        // Ensure manager is initialized if we are starting up
        if (webrtcManager == null) {
            initManager()
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

    // Listen for network changes to update the DHT node's external IP
    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                val type = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                    else -> "Other"
                }
                
                Log.d(tag, "Default Network available ($type), triggering recovery")
                
                // When network changes, our IP changed and DHT might need a boost
                webrtcManager?.handleNetworkChange()
                
                // Re-acquire lock if needed when network changes
                acquireMulticastLock()
            }

            override fun onLost(network: Network) {
                Log.w(tag, "Network connection lost!")
                // Only mark offline if we truly have no internet anymore
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork == null) {
                    webrtcManager?.onStateChanged?.invoke("Offline")
                }
            }
        })
    }

    // Allow receiving UDP broadcast/multicast packets for local discovery
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

    // Initialize the WebRTC manager and start the DHT node
    private fun initManager() {
        if (webrtcManager != null) return

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
            
            // PROACTIVE BOOTSTRAP: Feed trusted peers from DB into DHT
            try {
                val peers = database.peerDao().getAllPeersOnce()
                peers.forEach { peer ->
                    if (peer.lastKnownIp != null && peer.lastKnownPort != 0) {
                        Log.d(tag, "Adding trusted peer ${peer.username} to DHT bootstrap: ${peer.lastKnownIp}")
                        // Signaling port is often a good hint for DHT port if they are neighboring
                        dhtNode.callAttr("add_dht_bootstrap", peer.lastKnownIp, peer.lastKnownPort + 1)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to feed trusted peers to DHT: ${e.message}")
            }
            
            // Revert to aggressive immediate publish (like in commit 78a4650e)
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
