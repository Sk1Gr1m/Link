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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var sdpInput by remember { mutableStateOf("") }
        val scrollState = rememberScrollState()

        val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
            if (result.contents != null) {
                webrtcManager?.processScannedQr(result.contents)
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
                Text(text = "Link P2P Identity & Signaling", style = MaterialTheme.typography.headlineMedium)
                
                Spacer(modifier = Modifier.height(16.dp))

                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "My QR Code",
                        modifier = Modifier.size(200.dp)
                    )
                }

                Button(
                    onClick = {
                        val qrData = webrtcManager?.getMyQrData()
                        if (qrData != null) {
                            val encoder = BarcodeEncoder()
                            qrBitmap = encoder.encodeBitmap(qrData, BarcodeFormat.QR_CODE, 512, 512)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Show My Identity QR") }

                Button(
                    onClick = {
                        val options = ScanOptions()
                        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        options.setPrompt("Scan a peer's Identity QR")
                        scanLauncher.launch(options)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Scan Peer Identity QR") }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("WebRTC Signaling (Manual Exchange)", style = MaterialTheme.typography.titleMedium)
                
                Button(
                    onClick = {
                        webrtcManager?.createOffer { sdp ->
                            copyToClipboard("WebRTC Offer", sdp)
                            Toast.makeText(this@MainActivity, "Offer copied to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("1. Create & Copy Offer") }

                OutlinedTextField(
                    value = sdpInput,
                    onValueChange = { sdpInput = it },
                    label = { Text("Paste Peer SDP (Offer/Answer)") },
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                )

                Button(
                    onClick = {
                        if (sdpInput.contains("typ: offer", ignoreCase = true) || sdpInput.contains("\"type\":\"offer\"", ignoreCase = true) || sdpInput.contains("v=0", ignoreCase = true)) {
                            // Assume it's an offer if it looks like SDP
                            val type = if (sdpInput.contains("offer", ignoreCase = true)) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
                            
                            if (type == SessionDescription.Type.OFFER) {
                                webrtcManager?.handleRemoteSdp(sdpInput, type) { answerSdp ->
                                    copyToClipboard("WebRTC Answer", answerSdp)
                                    Toast.makeText(this@MainActivity, "Answer copied! Send it back.", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                webrtcManager?.handleRemoteSdp(sdpInput, type)
                                Toast.makeText(this@MainActivity, "Remote Answer Set!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = sdpInput.isNotEmpty()
                ) { Text("2. Process Pasted SDP") }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        webrtcManager?.sendEncrypted("Hello from secure P2P link!")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Test: Send Encrypted 'Hello'") }
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
        webrtcManager = WebRTCManager(this) { username, fingerprint, idKey, callback ->
            pendingRequest = PairingRequest(username, fingerprint, idKey, callback)
        }
    }

    data class PairingRequest(
        val username: String,
        val fingerprint: String,
        val identityKey: ByteArray,
        val onResponse: (Boolean) -> Unit
    )
}
