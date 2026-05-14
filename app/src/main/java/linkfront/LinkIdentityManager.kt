package com.linkfront

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.chaquo.python.PyObject
import com.chaquo.python.Python

// Manages the user's cryptographic identity and username
class LinkIdentityManager(private val context: Context) {
    private val py = Python.getInstance()
    private val linkModule = py.getModule("linkfront")
    
    var identity: PyObject? = null
        private set
        
    var username by mutableStateOf("")
        private set
        
    var fingerprint by mutableStateOf("Unknown")
        private set

    init {
        val prefs = context.getSharedPreferences("link_identity", Context.MODE_PRIVATE)
        val savedUsername = prefs.getString("my_username", "User") ?: "User"
        username = sanitizeUsername(savedUsername).ifEmpty { "User" }
        
        val privateKeyHex = prefs.getString("private_key_hex", null)
        if (privateKeyHex != null) {
            identity = linkModule["Identity"]?.call(privateKeyHex)
        } else {
            val newId = linkModule["Identity"]?.call()
            identity = newId
            val newPrivateKeyHex = newId?.callAttr("get_private_key_hex")?.toString()
            prefs.edit(commit = true) {
                putString("private_key_hex", newPrivateKeyHex)
            }
        }
        fingerprint = calculateFingerprint()
    }

    // Change the display name and save it to preferences
    fun updateUsername(newName: String): Boolean {
        val sanitized = sanitizeUsername(newName)
        if (sanitized.isEmpty()) return false
        
        username = sanitized
        context.getSharedPreferences("link_identity", Context.MODE_PRIVATE).edit {
            putString("my_username", sanitized)
        }
        return true
    }

    // Clean up a username string to prevent illegal characters or spoofing
    fun sanitizeUsername(name: String): String {
        // Remove control characters and whitespace
        var sanitized = name.trim().filter { it.code >= 32 }
        
        // Limit length
        if (sanitized.length > 32) sanitized = sanitized.take(32)
        
        // Prevent names that look like fingerprints
        val fingerprintRegex = Regex("^([0-9A-F]{4}:){3}[0-9A-F]{4}.*", RegexOption.IGNORE_CASE)
        if (fingerprintRegex.matches(sanitized)) {
            return "User_" + sanitized.take(4)
        }
        
        return sanitized
    }

    // Generate the human-readable fingerprint for a public key
    private fun calculateFingerprint(key: ByteArray? = null): String {
        return if (key == null) {
            identity?.callAttr("get_fingerprint")?.toString() ?: "UNKNOWN"
        } else {
            linkModule.callAttr("get_fingerprint", key).toString()
        }
    }

    fun getFingerprint(key: ByteArray? = null): String = calculateFingerprint(key)
    
    fun getPublicKeyBytes(): ByteArray? {
        return identity?.callAttr("get_public_key_bytes")?.toJava(ByteArray::class.java)
    }

    fun getEncryptionPrivateKeyBytes(): PyObject? {
        return identity?.callAttr("get_encryption_private_key_bytes")
    }
}
