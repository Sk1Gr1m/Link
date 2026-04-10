package com.example.linkfront.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.linkfront.MessageDao
import com.example.linkfront.MessageEntity
import com.example.linkfront.WebRTCManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    webrtcManager: WebRTCManager?,
    messageDao: MessageDao,
    fingerprint: String,
    onBack: () -> Unit,
    onClearHistory: () -> Unit
) {
    var textInput by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = textInput.isNotEmpty()) {
        textInput = ""
    }

    val messages by messageDao.getMessagesForPeer(fingerprint).collectAsState(initial = emptyList())
    val sortedMessages = remember(messages) { messages.sortedByDescending { it.id } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var messageToDelete by remember { mutableStateOf<MessageEntity?>(null) }

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

    LaunchedEffect(webrtcManager?.connectionStatus, fingerprint) {
        if (fingerprint == "SELF") return@LaunchedEffect
        while (webrtcManager?.connectionStatus == "Disconnected") {
            webrtcManager.connectToPeerViaDHT(fingerprint)
            kotlinx.coroutines.delay(30000)
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
                actions = {
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
                        onLongClick = { messageToDelete = msg }
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
fun ChatBubble(
    message: MessageEntity,
    onLongClick: () -> Unit
) {
    if (message.text.isEmpty()) return

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
    val textColor = if (isMe) Color.White else MaterialTheme.colorScheme.onSecondaryContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
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
            tonalElevation = if (isMe) 2.dp else 0.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor
                )
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                    modifier = Alignment.End.let { Modifier.align(it) }
                )
            }
        }
    }
}
