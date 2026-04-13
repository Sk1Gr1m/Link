package com.example.linkfront

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.chaquo.python.PyObject
import com.chaquo.python.Python

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
        username = prefs.getString("my_username", "UnknownUser") ?: "UnknownUser"
        
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

    fun updateUsername(newName: String) {
        username = newName
        context.getSharedPreferences("link_identity", Context.MODE_PRIVATE).edit {
            putString("my_username", newName)
        }
    }

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
