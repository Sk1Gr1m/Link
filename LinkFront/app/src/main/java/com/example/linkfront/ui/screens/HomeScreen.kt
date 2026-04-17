package com.example.linkfront.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.linkfront.PeerDao
import com.example.linkfront.PeerEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    peerDao: PeerDao,
    onNewConnection: () -> Unit,
    onChatSelected: (String) -> Unit,
    onProfileClick: () -> Unit
) {
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
