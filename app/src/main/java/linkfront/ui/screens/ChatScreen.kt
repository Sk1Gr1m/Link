package com.linkfront.ui.screens

import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.linkfront.MessageDao
import com.linkfront.MessageEntity
import com.linkfront.PeerDao
import com.linkfront.WebRTCManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    webrtcManager: WebRTCManager?,
    messageDao: MessageDao,
    peerDao: PeerDao,
    fingerprint: String,
    onBack: () -> Unit,
    onClearHistory: () -> Unit
) {
    // Screen for messaging a specific peer
    var textInput by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = textInput.isNotEmpty()) {
        textInput = ""
    }

    val messages by messageDao.getMessagesForPeer(fingerprint).collectAsState(initial = emptyList())
    val peer by peerDao.getPeerFlowByFingerprint(fingerprint).collectAsState(initial = null)
    
    val sortedMessages = remember(messages) { messages.sortedByDescending { it.id } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var messageToDelete by remember { mutableStateOf<MessageEntity?>(null) }
    var fullScreenImagePath by remember { mutableStateOf<String?>(null) }

    // Handle image selection from the gallery
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(it)?.use { inputStream ->
                        val bytes = inputStream.readBytes()
                        webrtcManager?.sendImage(fingerprint, bytes)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    if (messageToDelete != null) {
        AlertDialog(
            onDismissRequest = { messageToDelete = null },
            title = { Text("Delete Message?") },
            text = { Text("This message will be permanently removed from your history.") },
            confirmButton = {
                TextButton(onClick = {
                    messageToDelete?.let { msg ->
                        scope.launch {
                            messageDao.deleteMessageById(msg.id)
                        }
                    }
                    messageToDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { messageToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (fullScreenImagePath != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullScreenImagePath = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { fullScreenImagePath = null })
                    },
                contentAlignment = Alignment.Center
            ) {
                val bitmap = remember(fullScreenImagePath) {
                    try {
                        BitmapFactory.decodeFile(fullScreenImagePath)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Full Screen Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }

    // Periodic check for DHT connection status
    var dhtConnected by remember { mutableStateOf(false) }
    LaunchedEffect(webrtcManager, dhtConnected) {
        if (webrtcManager == null) {
            dhtConnected = false
            return@LaunchedEffect
        }
        while (true) {
            val statusJson = webrtcManager.getDhtStatus()
            if (statusJson != null) {
                try {
                    val obj = JSONObject(statusJson)
                    dhtConnected = obj.getBoolean("is_connected")
                } catch (_: Exception) {}
            }
            delay(5000)
        }
    }

    // Handle automatic connection and message resending
    LaunchedEffect(webrtcManager, webrtcManager?.connectionStatus, webrtcManager?.peerFingerprint, fingerprint, dhtConnected) {
        if (fingerprint == "SELF" || webrtcManager == null) return@LaunchedEffect
        
        // 1. Connection Logic
        // Immediate attempt if disconnected
        if (webrtcManager.peerFingerprint != fingerprint || webrtcManager.connectionStatus == "Disconnected") {
            if (dhtConnected) {
                Log.d("ChatScreen", "Triggering connection to $fingerprint (DHT Connected)")
                webrtcManager.connectToPeerViaDHT(fingerprint)
            }
        }

        // 2. Auto-Resend Logic
        if (webrtcManager.peerFingerprint == fingerprint && webrtcManager.connectionStatus == "Connected") {
            val failedMessages = messageDao.getFailedMessagesForPeer(fingerprint)
            if (failedMessages.isNotEmpty()) {
                failedMessages.forEach { msg ->
                    if (msg.messageType == "TEXT") {
                        messageDao.deleteMessageById(msg.id)
                        webrtcManager.sendEncrypted(fingerprint, msg.text)
                    } else if (msg.messageType == "IMAGE" && msg.filePath != null) {
                        val file = File(msg.filePath)
                        if (file.exists()) {
                            messageDao.deleteMessageById(msg.id)
                            webrtcManager.sendImage(fingerprint, file.readBytes())
                        }
                    }
                }
            }
        }

        // 3. Polling loop
        while (true) {
            val currentStatus = webrtcManager.connectionStatus
            val currentPeer = webrtcManager.peerFingerprint
            
            if (currentPeer != fingerprint || currentStatus == "Disconnected") {
                if (dhtConnected) {
                    webrtcManager.connectToPeerViaDHT(fingerprint)
                }
            }
            delay(20000) // Slightly more aggressive polling
        }
    }

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
                        val title = when {
                            fingerprint == "SELF" -> "My Notes"
                            webrtcManager != null && webrtcManager.peerFingerprint == fingerprint && webrtcManager.peerUsername != "Waiting for peer..." -> "Chat with ${webrtcManager.peerUsername}"
                            peer != null -> "Chat with ${peer?.username}"
                            else -> "Secure Chat"
                        }
                        
                        val status = when {
                            fingerprint == "SELF" -> "Local Only"
                            webrtcManager != null && webrtcManager.peerFingerprint == fingerprint -> webrtcManager.connectionStatus
                            dhtConnected -> "Finding Peer..."
                            else -> "DHT Disconnected"
                        }
                        
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (status) {
                                "Connected", "Local Only" -> Color(0xFF4CAF50)
                                "Finding Peer...", "Connecting...", "Verifying..." -> Color(0xFFFF9800)
                                "DHT Disconnected" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.secondary
                            }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (fingerprint != "SELF") {
                        IconButton(onClick = {
                            webrtcManager?.connectToPeerViaDHT(fingerprint)
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reconnect")
                        }
                    }
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.Settings, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Clear History") },
                            onClick = {
                                onClearHistory()
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                        )
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
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
            ) {
                items(items = sortedMessages, key = { it.id }) { msg ->
                    ChatBubble(
                        message = msg,
                        onLongClick = { messageToDelete = msg },
                        onImageClick = { fullScreenImagePath = it },
                        onRetry = {
                            if (msg.messageType == "IMAGE" && msg.filePath != null) {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val bytes = File(msg.filePath).readBytes()
                                        // Delete the old failed entry
                                        messageDao.deleteMessageById(msg.id)
                                        webrtcManager?.sendImage(fingerprint, bytes)
                                    } catch (_: Exception) {}
                                }
                            } else {
                                scope.launch {
                                    messageDao.deleteMessageById(msg.id)
                                    webrtcManager?.sendEncrypted(fingerprint, msg.text)
                                }
                            }
                        }
                    )
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
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Icon(Icons.Default.Add, contentDescription = "Attach Image")
                    }
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
                                webrtcManager?.sendEncrypted(fingerprint, messageText)
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

// Individual message bubble UI
@Composable
fun ChatBubble(
    message: MessageEntity,
    onLongClick: () -> Unit,
    onImageClick: (String) -> Unit,
    onRetry: () -> Unit
) {
    if (message.text.isEmpty() && message.messageType != "IMAGE") return

    val isMe = message.isMe
    val timeString = remember(message.timestamp) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        formatter.format(Date(message.timestamp))
    }
    
    val bubbleShape = if (isMe) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
    }

    val bgColor = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongClick() }
                )
            },
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = bgColor,
            shape = bubbleShape,
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.messageType == "IMAGE" && message.filePath != null) {
                    val bitmap = remember(message.filePath) {
                        try {
                            BitmapFactory.decodeFile(message.filePath)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (bitmap != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.1f))
                                .clickable { message.filePath?.let { onImageClick(it) } }
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Image message",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 250.dp),
                                contentScale = ContentScale.Crop
                            )
                            if (message.transferStatus == "PENDING") {
                                CircularProgressIndicator(
                                    progress = { message.progress / 100f },
                                    modifier = Modifier.size(48.dp),
                                    color = Color.White
                                )
                            }
                        }
                    } else if (message.transferStatus == "PENDING") {
                        CircularProgressIndicator(
                            progress = { message.progress / 100f },
                            modifier = Modifier.size(48.dp).padding(8.dp),
                            color = textColor
                        )
                    } else {
                        Text("[Image Error]", color = textColor)
                    }
                } else {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.6f)
                    )
                    if (isMe) {
                        Spacer(modifier = Modifier.width(6.dp))
                        when (message.transferStatus) {
                            "PENDING" -> CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.5.dp,
                                color = textColor.copy(alpha = 0.6f)
                            )
                            "DELIVERED" -> Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Delivered",
                                modifier = Modifier.size(14.dp),
                                tint = if (isMe) Color(0xFFB9F6CA) else Color(0xFF4CAF50)
                            )
                            "FAILED" -> Icon(
                                Icons.Default.Warning,
                                contentDescription = "Failed",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
