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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.linkfront.ui.theme.LinkFrontTheme
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.webrtc.SessionDescription

class MainActivity : ComponentActivity() {
    
    private var webrtcManager by mutableStateOf<WebRTCManager?>(null)
    private var pendingRequest by mutableStateOf<PairingRequest?>(null)
    private val messages = mutableStateListOf<Message>()
    private var connectionState by mutableStateOf("Disconnected")

    data class Message(val text: String, val isMe: Boolean)

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
        var showChat by remember { mutableStateOf(false) }

        if (connectionState == "Secure Session Ready") {
            showChat = true
        }

        if (showChat) {
            ChatScreen(webrtcManager)
        } else {
            PairingScreen(webrtcManager)
        }
    }

    @Composable
    fun PairingScreen(webrtcManager: WebRTCManager?) {
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

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Link P2P Signaling", style = MaterialTheme.typography.headlineMedium)
                Text(text = "State: $connectionState", color = MaterialTheme.colorScheme.primary)
                
                Spacer(modifier = Modifier.height(16.dp))

                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "Connection QR",
                        modifier = Modifier.size(300.dp)
                    )
                }

                Button(
                    onClick = {
                        webrtcManager?.getConnectionQrData { qrData ->
                            val encoder = BarcodeEncoder()
                            qrBitmap = encoder.encodeBitmap(qrData, BarcodeFormat.QR_CODE, 512, 512)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("1. Generate Offer QR") }

                Button(
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

    @Composable
    fun ChatScreen(webrtcManager: WebRTCManager?) {
        var textInput by remember { mutableStateOf("") }

        Scaffold(
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
                            messages.add(Message(textInput, true))
                            textInput = ""
                        }
                    }) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        ) { innerPadding ->
            LazyColumn(
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
    fun ChatBubble(message: Message) {
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

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
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
        webrtcManager = WebRTCManager(this, { username, fingerprint, idKey, callback ->
            pendingRequest = PairingRequest(username, fingerprint, idKey, callback)
        }, { msg ->
            runOnUiThread { messages.add(Message(msg, false)) }
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
