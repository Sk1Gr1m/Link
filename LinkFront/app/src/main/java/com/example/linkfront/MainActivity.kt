package com.example.linkfront

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.linkfront.ui.screens.ChatScreen
import com.example.linkfront.ui.screens.HomeScreen
import com.example.linkfront.ui.screens.ProfileScreen
import com.example.linkfront.ui.theme.LinkFrontTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private var linkService by mutableStateOf<LinkService?>(null)
    private val webrtcManager get() = linkService?.webrtcManager
    private var pendingRequest by mutableStateOf<PairingRequest?>(null)
    private lateinit var database: AppDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var peerDao: PeerDao

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            val binder = service as LinkService.LocalBinder
            linkService = binder.getService()
            linkService?.connectionRequestListener = { username, fingerprint, idKey, callback ->
                pendingRequest = PairingRequest(username, fingerprint, idKey, callback)
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            linkService = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        
        if (cameraGranted && audioGranted) {
            initWebRTC()
        } else {
            Toast.makeText(this, "Permissions required for WebRTC", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        NotificationHelper(this)
        database = AppDatabase.getDatabase(this)
        messageDao = database.messageDao()
        peerDao = database.peerDao()

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        checkAndRequestPermissions()

        enableEdgeToEdge()
        setContent {
            LinkFrontTheme {
                MainScreen(webrtcManager)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, LinkService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
    }

    @Composable
    fun MainScreen(webrtcManager: WebRTCManager?) {
        val navController = rememberNavController()
        val scope = rememberCoroutineScope()

        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    peerDao = peerDao,
                    onNewConnection = { navController.navigate("profile") },
                    onChatSelected = { fingerprint ->
                        navController.navigate("chat/$fingerprint")
                    },
                    onProfileClick = { navController.navigate("profile") }
                )
            }
            composable("profile") {
                ProfileScreen(
                    webrtcManager = webrtcManager,
                    pendingRequest = pendingRequest,
                    onPendingRequestResolved = { pendingRequest = null },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("chat/{fingerprint}") { backStackEntry ->
                val fingerprint = backStackEntry.arguments?.getString("fingerprint") ?: ""
                ChatScreen(
                    webrtcManager = webrtcManager,
                    messageDao = messageDao,
                    fingerprint = fingerprint,
                    onBack = { navController.popBackStack() },
                    onClearHistory = {
                        scope.launch {
                            messageDao.deleteMessagesForPeer(fingerprint)
                        }
                    }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isEmpty()) {
            initWebRTC()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun initWebRTC() {
        val intent = Intent(this, LinkService::class.java)
        startService(intent)
    }

    data class PairingRequest(
        val username: String,
        val fingerprint: String,
        val identityKey: ByteArray,
        val onResponse: (Boolean) -> Unit
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PairingRequest

            if (username != other.username) return false
            if (fingerprint != other.fingerprint) return false
            if (!identityKey.contentEquals(other.identityKey)) return false
            if (onResponse != other.onResponse) return false

            return true
        }

        override fun hashCode(): Int {
            var result = username.hashCode()
            result = 31 * result + fingerprint.hashCode()
            result = 31 * result + identityKey.contentHashCode()
            result = 31 * result + onResponse.hashCode()
            return result
        }
    }
}
