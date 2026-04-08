package com.example.linkfront

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.linkfront.ui.theme.LinkFrontTheme
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    
    private var webrtcManager by mutableStateOf<WebRTCManager?>(null)
    private var pendingRequest by mutableStateOf<PairingRequest?>(null)
    private var connectionState by mutableStateOf("Disconnected")
    private lateinit var database: AppDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var peerDao: PeerDao

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

    @Composable
    fun MainScreen(webrtcManager: WebRTCManager?) {
        val navController = rememberNavController()

        if (connectionState == "Secure Session Ready") {
            LaunchedEffect(Unit) {
                navController.navigate("chat")
            }
        }

        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    onNewConnection = { navController.navigate("pairing") },
                    onChatSelected = { navController.navigate("chat") }
                )
            }
            composable("pairing") {
                PairingScreen(webrtcManager, onBack = { navController.popBackStack() })
            }
            composable("chat") {
                ChatScreen(webrtcManager, onBack = { navController.popBackStack() })
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun HomeScreen(onNewConnection: () -> Unit, onChatSelected: () -> Unit) {
        val contactList by peerDao.getAllPeers().collectAsState(initial = emptyList())
        var peerToDelete by remember { mutableStateOf<PeerEntity?>(null) }
        val scope = rememberCoroutineScope()

        if (peerToDelete != null) {
            AlertDialog(
                onDismissRequest = { peerToDelete = null },
                title = { Text("Delete Connection?") },
                text = { Text("Are you sure you want to remove ${peerToDelete?.username}? This will delete your shared history and trust.") },
                confirmButton = {
                    Button(
                        onClick = {
                            val peer = peerToDelete
                            if (peer != null) {
                                scope.launch { peerDao.delete(peer) }
                            }
                            peerToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { peerToDelete = null }) { Text("Cancel") }
                }
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Link") })
            },
            floatingActionButton = {
                FloatingActionButton(onClick = onNewConnection) {
                    Icon(Icons.Default.Add, contentDescription = "New Connection")
                }
            }
        ) { innerPadding ->
            LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                if (contactList.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No contacts yet. Tap + to connect!", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                items(contactList) { peer ->
                    ListItem(
                        headlineContent = { Text(peer.username) },
                        supportingContent = { Text("Fingerprint: ${peer.fingerprint.take(16)}...") },
                        trailingContent = {
                            IconButton(onClick = { peerToDelete = peer }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onChatSelected() },
                                onLongPress = { peerToDelete = peer }
                            )
                        }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun PairingScreen(webrtcManager: WebRTCManager?, onBack: () -> Unit) {
        var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
        val scrollState = rememberScrollState()

        val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
            if (result.contents != null) {
                webrtcManager?.processScannedQr(result.contents) { answerQr ->
                    val encoder = BarcodeEncoder()
                    qrBitmap = encoder.encodeBitmap(answerQr, BarcodeFormat.QR_CODE, 512, 512)
                }
            }
        }

        if (pendingRequest != null) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Pairing Request") },
                text = { Text("Accept connection from ${pendingRequest?.username}?\n\nFingerprint: ${pendingRequest?.fingerprint}") },
                confirmButton = {
                    Button(onClick = {
                        pendingRequest?.onResponse?.invoke(true)
                        pendingRequest = null
                    }) { Text("Accept") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingRequest?.onResponse?.invoke(false)
                        pendingRequest = null
                    }) { Text("Decline") }
                }
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Connect to Peer") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (qrBitmap != null) {
                    Text("Your Connection QR", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "Connection QR",
                        modifier = Modifier.size(300.dp)
                    )
                    Text(
                        text = "Show this to your peer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(300.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No QR Generated", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        webrtcManager?.getConnectionQrData { qrData ->
                            val encoder = BarcodeEncoder()
                            qrBitmap = encoder.encodeBitmap(qrData, BarcodeFormat.QR_CODE, 512, 512)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("1. Generate Offer QR") }

                OutlinedButton(
                    onClick = {
                        val options = ScanOptions()
                        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        options.setPrompt("Scan Peer's QR")
                        scanLauncher.launch(options)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("2. Scan Peer's QR") }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ChatScreen(webrtcManager: WebRTCManager?, onBack: () -> Unit) {
        var textInput by remember { mutableStateOf("") }
        val messages by messageDao.getAllMessages().collectAsState(initial = emptyList())
        val listState = rememberLazyListState()

        // Auto-scroll to bottom when new messages arrive
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            val title = if (webrtcManager != null) "Chat with ${webrtcManager.peerUsername}" else "Secure Chat"
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(connectionState, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            },
            bottomBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Encrypted message...") }
                    )
                    IconButton(onClick = {
                        if (textInput.isNotBlank()) {
                            webrtcManager?.sendEncrypted(textInput)
                            textInput = ""
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        ) { innerPadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(8.dp),
                reverseLayout = false
            ) {
                items(messages) { msg ->
                    ChatBubble(msg)
                }
            }
        }
    }

    @Composable
    fun ChatBubble(message: MessageEntity) {
        val alignment = if (message.isMe) Alignment.CenterEnd else Alignment.CenterStart
        val color = if (message.isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
            Surface(
                color = color,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(4.dp).widthIn(max = 280.dp)
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodyLarge
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

        if (permissionsToRequest.isEmpty()) {
            initWebRTC()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun initWebRTC() {
        webrtcManager = WebRTCManager(this, messageDao, peerDao, { username, fingerprint, idKey, callback ->
            pendingRequest = PairingRequest(username, fingerprint, idKey, callback)
        }, { _ ->
            // UI updates automatically via Room Flow
        }, { state ->
            runOnUiThread { connectionState = state }
        })
    }

    data class PairingRequest(
        val username: String,
        val fingerprint: String,
        val identityKey: ByteArray,
        val onResponse: (Boolean) -> Unit
    )
}
