package com.example.linkfront

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                // Only auto-navigate if we aren't already in a chat
                if (navController.currentDestination?.route?.startsWith("chat") != true) {
                    navController.navigate("chat/${webrtcManager?.peerFingerprint}")
                }
            }
        }

        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    onNewConnection = { navController.navigate("profile") },
                    onChatSelected = { fingerprint ->
                        navController.navigate("chat/$fingerprint")
                    },
                    onProfileClick = { navController.navigate("profile") }
                )
            }
            composable("profile") {
                ProfileScreen(webrtcManager, onBack = { navController.popBackStack() })
            }
            composable("chat/{fingerprint}") { backStackEntry ->
                val fingerprint = backStackEntry.arguments?.getString("fingerprint") ?: ""
                ChatScreen(webrtcManager, fingerprint, onBack = { navController.popBackStack() })
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    @Suppress("UNUSED_VALUE")
    fun HomeScreen(onNewConnection: () -> Unit, onChatSelected: (String) -> Unit, onProfileClick: () -> Unit) {
        val contactList by peerDao.getAllPeers().collectAsState(initial = emptyList())
        var peerToDelete by remember { mutableStateOf<PeerEntity?>(null) }
        val scope = rememberCoroutineScope()

        val displayContacts = remember(contactList) {
            listOf(PeerEntity("SELF", "My Notes", byteArrayOf())) + contactList
        }

        peerToDelete?.let { peer ->
            AlertDialog(
                onDismissRequest = { peerToDelete = null },
                title = { Text("Delete Connection?") },
                text = { Text("Are you sure you want to remove ${peer.username}? This will delete your shared history and trust.") },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch { peerDao.delete(peer) }
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
                TopAppBar(
                    title = { Text("Link") },
                    actions = {
                        IconButton(onClick = onProfileClick) {
                            Icon(Icons.Default.AccountCircle, contentDescription = "Profile")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = onNewConnection) {
                    Icon(Icons.Default.Add, contentDescription = "New Connection")
                }
            }
        ) { innerPadding ->
            LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                items(displayContacts) { peer ->
                    ListItem(
                        headlineContent = { Text(peer.username) },
                        supportingContent = { 
                            if (peer.fingerprint == "SELF") {
                                Text("Private scratchpad", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("Fingerprint: ${peer.fingerprint.take(16)}...")
                            }
                        },
                        leadingContent = {
                            val icon = if (peer.fingerprint == "SELF") Icons.Default.Settings else Icons.Default.AccountCircle
                            Icon(icon, contentDescription = null)
                        },
                        trailingContent = {
                            if (peer.fingerprint != "SELF") {
                                IconButton(onClick = { peerToDelete = peer }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onChatSelected(peer.fingerprint) },
                                onLongPress = { if (peer.fingerprint != "SELF") { peerToDelete = peer } }
                            )
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    @Suppress("UNUSED_VALUE")
    fun ProfileScreen(webrtcManager: WebRTCManager?, onBack: () -> Unit) {
        var tempUsername by remember { mutableStateOf(webrtcManager?.myUsername ?: "") }
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
                    title = { Text("My Profile") },
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
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(100.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = tempUsername,
                    onValueChange = { tempUsername = it },
                    label = { Text("Your Username") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (tempUsername != webrtcManager?.myUsername) {
                            TextButton(onClick = { webrtcManager?.updateUsername(tempUsername) }) {
                                Text("Save")
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Fingerprint: ${webrtcManager?.getFingerprint() ?: "Unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                if (qrBitmap != null) {
                    Text("Connection QR", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "Connection QR",
                        modifier = Modifier.size(300.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(250.dp)
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

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    fun ChatScreen(webrtcManager: WebRTCManager?, fingerprint: String, onBack: () -> Unit) {
        var textInput by remember { mutableStateOf("") }

        // Example: Intercept back button if there is unsent text
        BackHandler(enabled = textInput.isNotEmpty()) {
            textInput = "" // Clear text on first back press if not empty
        }

        val messages by messageDao.getMessagesForPeer(fingerprint).collectAsState(initial = emptyList())
        val sortedMessages = remember(messages) { messages.sortedByDescending { it.id } }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // Auto-reconnect if disconnected with a retry loop
        LaunchedEffect(webrtcManager?.connectionStatus, fingerprint) {
            if (fingerprint == "SELF") return@LaunchedEffect
            
            while (webrtcManager?.connectionStatus == "Disconnected") {
                webrtcManager.connectToPeerViaDHT(fingerprint)
                kotlinx.coroutines.delay(30000) 
            }
        }

        // 1. Initial scroll to top (which is bottom in reversed layout) ONLY when the chat is opened
        var initialScrollDone by remember(fingerprint) { mutableStateOf(false) }
        LaunchedEffect(messages) {
            if (messages.isNotEmpty() && !initialScrollDone) {
                listState.scrollToItem(0)
                initialScrollDone = true
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            val title = if (fingerprint == "SELF") "My Notes" 
                                       else if (webrtcManager != null) "Chat with ${webrtcManager.peerUsername}" 
                                       else "Secure Chat"
                            val status = if (fingerprint == "SELF") "Local Only" 
                                        else webrtcManager?.connectionStatus ?: "Offline"
                            
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (status == "Connected" || status == "Local Only") Color(0xFF4CAF50) else MaterialTheme.colorScheme.secondary
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
                ) {
                    items(
                        items = sortedMessages,
                        key = { it.id }
                    ) { msg ->
                        ChatBubble(msg)
                    }
                }

                Surface(
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message...") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            maxLines = 4
                        )
                        
                        FloatingActionButton(
                            onClick = {
                                if (textInput.isNotBlank()) {
                                    val messageText = textInput
                                    if (fingerprint == "SELF") {
                                        scope.launch {
                                            messageDao.insert(MessageEntity(peerFingerprint = "SELF", text = messageText, isMe = true))
                                        }
                                    } else {
                                        webrtcManager?.sendEncrypted(messageText)
                                    }
                                    textInput = ""
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ChatBubble(message: MessageEntity) {
        if (message.text.isEmpty()) return // Skip rendering empty/broken messages

        val isMe = message.isMe
        
        val bubbleShape = if (isMe) {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
        } else {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
        }

        val bgColor = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
        val textColor = if (isMe) Color.White else MaterialTheme.colorScheme.onSecondaryContainer

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
        ) {
            Surface(
                color = bgColor,
                shape = bubbleShape,
                tonalElevation = if (isMe) 2.dp else 0.dp,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor
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
