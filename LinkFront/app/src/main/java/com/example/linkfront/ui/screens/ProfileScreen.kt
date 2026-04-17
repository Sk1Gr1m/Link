package com.example.linkfront.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.linkfront.MainActivity
import com.example.linkfront.WebRTCManager
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    webrtcManager: WebRTCManager?,
    pendingRequest: MainActivity.PairingRequest?,
    onPendingRequestResolved: () -> Unit,
    onBack: () -> Unit
) {
    var tempUsername by remember { mutableStateOf(webrtcManager?.myUsername ?: "") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrLabel by remember { mutableStateOf<String?>(null) }
    var dhtStatus by remember { mutableStateOf("Checking DHT...") }
    val scrollState = rememberScrollState()

    LaunchedEffect(webrtcManager) {
        if (webrtcManager == null) return@LaunchedEffect
        while (true) {
            val statusJson = webrtcManager.getDhtStatus()
            if (statusJson != null) {
                try {
                    val obj = org.json.JSONObject(statusJson)
                    val isConnected = obj.getBoolean("is_connected")
                    val neighbors = obj.getInt("neighbor_count")
                    dhtStatus = if (isConnected) "DHT Connected ($neighbors nodes)" else "DHT Disconnected"
                } catch (e: Exception) {
                    android.util.Log.e("ProfileScreen", "Status JSON error: ${e.message}")
                }
            }
            kotlinx.coroutines.delay(5000)
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            webrtcManager?.processScannedQr(result.contents) { answerQr ->
                // If the scanned QR was an Offer, this callback provides the Answer QR
                val encoder = BarcodeEncoder()
                try {
                    qrBitmap = encoder.encodeBitmap(answerQr, BarcodeFormat.QR_CODE, 512, 512)
                    qrLabel = "Answer QR (Let Peer A scan this)"
                } catch (e: Exception) {
                    android.util.Log.e("ProfileScreen", "Failed to encode Answer QR: ${e.message}")
                }
            }
        }
    }

    if (pendingRequest != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Pairing Request") },
            text = { Text("Accept connection from ${pendingRequest.username}?\n\nFingerprint: ${pendingRequest.fingerprint}") },
            confirmButton = {
                Button(onClick = {
                    pendingRequest.onResponse.invoke(true)
                    onPendingRequestResolved()
                }) { Text("Accept") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingRequest.onResponse.invoke(false)
                    onPendingRequestResolved()
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
                onValueChange = { 
                    // Allow typing, but we will sanitize on save
                    tempUsername = it 
                },
                label = { Text("Your Username") },
                placeholder = { Text("Enter a safe username") },
                supportingText = {
                    Text("Max 32 characters. No control characters. Cannot look like a fingerprint.")
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    if (tempUsername != webrtcManager?.myUsername) {
                        TextButton(onClick = { 
                            val success = webrtcManager?.updateUsername(tempUsername) ?: false
                            if (!success) {
                                // Reset to current valid username if update failed
                                tempUsername = webrtcManager?.myUsername ?: ""
                            }
                        }) {
                            Text("Save")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Fingerprint: ${webrtcManager?.myFingerprint ?: "Unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = dhtStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (dhtStatus.contains("Connected")) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
            )

            Text(
                text = "Connection: ${webrtcManager?.connectionStatus ?: "Unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = when(webrtcManager?.connectionStatus) {
                    "Connected" -> Color(0xFF4CAF50)
                    "Verifying..." -> Color(0xFFFF9800)
                    else -> MaterialTheme.colorScheme.secondary
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            if (qrBitmap != null) {
                Text(qrLabel ?: "Connection QR", style = MaterialTheme.typography.titleMedium)
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
                        try {
                            qrBitmap = encoder.encodeBitmap(qrData, BarcodeFormat.QR_CODE, 512, 512)
                            qrLabel = "Offer QR (Peer scans this)"
                        } catch (e: Exception) {
                            android.util.Log.e("ProfileScreen", "Failed to encode Offer QR: ${e.message}")
                        }
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

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = {
                    webrtcManager?.renewConnection()
                    qrBitmap = null
                    qrLabel = null
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Renew Session / Reset Connection")
            }
        }
    }
}
