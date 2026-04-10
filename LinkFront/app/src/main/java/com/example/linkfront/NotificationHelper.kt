package com.example.linkfront

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class NotificationHelper(private val context: Context) {

    companion object {
        const val SERVICE_CHANNEL_ID = "LINK_SERVICE_CHANNEL"
        const val CHAT_CHANNEL_ID = "CHAT_MESSAGES"
        const val SERVICE_NOTIFICATION_ID = 1
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Link Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the secure messaging engine running in the background"
            }

            val chatChannel = NotificationChannel(
                CHAT_CHANNEL_ID,
                "Chat Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming secure messages"
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(chatChannel)
        }
    }

    fun getServiceNotification(): Notification {
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setContentTitle("Link is active")
            .setContentText("Maintaining secure connections...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    fun showChatNotification(sender: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, CHAT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(sender)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    fun showPairingNotification(sender: String) {
        showChatNotification("Connection Request", "New secure request from $sender")
    }
}
