package io.github.axeldelaro.opensnap.daemon

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.axeldelaro.opensnap.CallActivity
import io.github.axeldelaro.opensnap.MainActivity
import io.github.axeldelaro.opensnap.R
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * Rich Native Android 13 / 14 / 15+ Notification Engine:
 * - Ultra-discrete `IMPORTANCE_MIN` persistent daemon tunnel notification
 * - `NotificationCompat.MessagingStyle` with cryptographic Identicon avatar & sender Person
 * - Inline Direct Reply (`RemoteInput`) without opening the app
 * - Quick Action *"Marquer comme lu"*
 * - Dedicated High-Priority Channels for Messages/Photos, Voice Notes, Friend Requests (*Accepter* / *Refuser*),
 *   and Full-Screen Incoming WebRTC Audio/Video Calls (`fullScreenIntent`).
 */
object NotificationHelper {

    const val CHANNEL_DAEMON_SILENT = "opensnap_daemon_silent"
    const val CHANNEL_MESSAGES_HIGH = "opensnap_messages_high"
    const val CHANNEL_VOICE_HIGH = "opensnap_voice_high"
    const val CHANNEL_FRIENDS_HIGH = "opensnap_friends_high"
    const val CHANNEL_CALLS_URGENT = "opensnap_calls_urgent"

    const val DAEMON_NOTIFICATION_ID = 9001
    const val CALL_NOTIFICATION_ID = 9999
    const val KEY_DIRECT_REPLY_TEXT = "opensnap_key_direct_reply_text"
    const val EXTRA_OPEN_CHAT_PEER_HEX = "extra_open_chat_peer_hex"

    data class CachedLine(
        val text: String,
        val timestampMs: Long,
        val isSelf: Boolean
    )

    private val conversationHistory = ConcurrentHashMap<String, MutableList<CachedLine>>()

    fun ensureChannelsCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val daemonChannel = NotificationChannel(
            CHANNEL_DAEMON_SILENT,
            context.getString(R.string.daemon_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Maintient la connexion chiffrée temps réel en arrière-plan sans polling"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }

        val messagesChannel = NotificationChannel(
            CHANNEL_MESSAGES_HIGH,
            context.getString(R.string.messages_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications instantanées de messages et snaps photos E2EE"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 180L, 90L, 180L)
            enableLights(true)
            lightColor = Color.YELLOW
        }

        val voiceChannel = NotificationChannel(
            CHANNEL_VOICE_HIGH,
            context.getString(R.string.voice_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications instantanées de messages vocaux E2EE"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 250L, 100L, 120L)
        }

        val friendsChannel = NotificationChannel(
            CHANNEL_FRIENDS_HIGH,
            context.getString(R.string.friends_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Demandes d'amis entrantes vérifiées cryptographiquement"
            enableVibration(true)
        }

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val callsChannel = NotificationChannel(
            CHANNEL_CALLS_URGENT,
            context.getString(R.string.calls_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Appels audio et vidéo P2P WebRTC chiffrés entrants"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 800L, 400L, 800L, 400L, 800L)
            setSound(
                ringtoneUri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        nm.createNotificationChannels(
            listOf(daemonChannel, messagesChannel, voiceChannel, friendsChannel, callsChannel)
        )
    }

    /**
     * Builds the ultra-discrete `PRIORITY_MIN` notification required by Android for the persistent push daemon.
     */
    fun buildSilentDaemonNotification(
        context: Context,
        connectedRelays: Int,
        totalRelays: Int,
        transportLabel: String
    ): Notification {
        ensureChannelsCreated(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (connectedRelays > 0) {
            "Tunnel E2EE actif • $connectedRelays/$totalRelays relais WSS ($transportLabel)"
        } else {
            "En attente de réseau • Reconconnexion instantanée armée"
        }

        return NotificationCompat.Builder(context, CHANNEL_DAEMON_SILENT)
            .setSmallIcon(R.drawable.ic_stat_opensnap)
            .setContentTitle("OpenSnap Protection Temps Réel")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pendingOpen)
            .build()
    }

    /**
     * Displays a rich `MessagingStyle` notification with Direct Reply (`RemoteInput`) and *"Marquer comme lu"*.
     */
    fun showIncomingMessageNotification(
        context: Context,
        myDisplayName: String,
        peerEd25519PubHex: String,
        peerX25519PubHex: String,
        peerRoutingPubHex: String,
        peerRatchetPubHex: String?,
        peerDisplayName: String,
        messageId: String,
        displayBody: String,
        isVoiceMessage: Boolean,
        isSelfReplyEcho: Boolean = false
    ) {
        if (!canPostNotifications(context)) return
        ensureChannelsCreated(context)

        val history = conversationHistory.getOrPut(peerEd25519PubHex) { mutableListOf() }
        synchronized(history) {
            history.add(CachedLine(displayBody, System.currentTimeMillis(), isSelfReplyEcho))
            while (history.size > 6) {
                history.removeAt(0)
            }
        }

        val avatarIcon = IconCompat.createWithBitmap(
            renderAvatarBitmap(peerEd25519PubHex, peerDisplayName)
        )
        val friendPerson = Person.Builder()
            .setName(peerDisplayName)
            .setKey(peerEd25519PubHex)
            .setIcon(avatarIcon)
            .build()

        val selfPerson = Person.Builder()
            .setName(myDisplayName.ifBlank { "Moi" })
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(selfPerson)
            .setGroupConversation(false)

        synchronized(history) {
            history.forEach { line ->
                messagingStyle.addMessage(
                    line.text,
                    line.timestampMs,
                    if (line.isSelf) selfPerson else friendPerson
                )
            }
        }

        val notifId = notificationIdForPeer(peerEd25519PubHex)

        // Open directly into this friend's 24h conversation
        val openChatIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_CHAT_PEER_HEX, peerEd25519PubHex)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Direct Reply (RemoteInput) action — requires FLAG_MUTABLE on Android 12+ so OS can attach reply text!
        val remoteInput = RemoteInput.Builder(KEY_DIRECT_REPLY_TEXT)
            .setLabel("Répondre en chiffré à $peerDisplayName...")
            .build()

        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DIRECT_REPLY
            putExtra(DaemonIpcContract.KEY_PEER_PUB_HEX, peerEd25519PubHex)
            putExtra(DaemonIpcContract.KEY_PEER_X25519_HEX, peerX25519PubHex)
            putExtra(DaemonIpcContract.KEY_PEER_ROUTING_HEX, peerRoutingPubHex)
            putExtra(DaemonIpcContract.KEY_PEER_RATCHET_HEX, peerRatchetPubHex)
            putExtra(DaemonIpcContract.KEY_PEER_DISPLAY_NAME, peerDisplayName)
        }
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Répondre",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        // Quick Action: "Marquer comme lu"
        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(DaemonIpcContract.KEY_PEER_PUB_HEX, peerEd25519PubHex)
            putExtra(DaemonIpcContract.KEY_PEER_X25519_HEX, peerX25519PubHex)
            putExtra(DaemonIpcContract.KEY_PEER_ROUTING_HEX, peerRoutingPubHex)
            putExtra(DaemonIpcContract.KEY_PEER_RATCHET_HEX, peerRatchetPubHex)
            putExtra(DaemonIpcContract.KEY_PACKET_ID, messageId)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 2,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val markReadAction = NotificationCompat.Action.Builder(
            android.R.drawable.checkbox_on_background,
            "Marquer comme lu",
            markReadPendingIntent
        ).build()

        val channelId = if (isVoiceMessage) CHANNEL_VOICE_HIGH else CHANNEL_MESSAGES_HIGH

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_opensnap)
            .setLargeIcon(renderAvatarBitmap(peerEd25519PubHex, peerDisplayName))
            .setStyle(messagingStyle)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(isSelfReplyEcho)
            .setContentIntent(contentPendingIntent)
            .addAction(replyAction)
            .addAction(markReadAction)
            .build()

        NotificationManagerCompat.from(context).notify(notifId, notification)
    }

    /**
     * Displays an instant Friend Request notification with inline *Accepter* & *Refuser* action buttons.
     */
    fun showFriendRequestNotification(
        context: Context,
        peerEd25519PubHex: String,
        peerX25519PubHex: String,
        peerRoutingPubHex: String,
        peerOpenSnapTag: String,
        peerDisplayName: String
    ) {
        if (!canPostNotifications(context)) return
        ensureChannelsCreated(context)

        val notifId = notificationIdForPeer("req:$peerEd25519PubHex")

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            notifId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_ACCEPT_FRIEND
            putExtra(DaemonIpcContract.KEY_PEER_PUB_HEX, peerEd25519PubHex)
            putExtra(DaemonIpcContract.KEY_PEER_X25519_HEX, peerX25519PubHex)
            putExtra(DaemonIpcContract.KEY_PEER_ROUTING_HEX, peerRoutingPubHex)
            putExtra(DaemonIpcContract.KEY_PEER_DISPLAY_NAME, peerDisplayName)
        }
        val acceptPending = PendingIntent.getBroadcast(
            context,
            notifId + 10,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REJECT_FRIEND
            putExtra(DaemonIpcContract.KEY_PEER_PUB_HEX, peerEd25519PubHex)
        }
        val rejectPending = PendingIntent.getBroadcast(
            context,
            notifId + 11,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_FRIENDS_HIGH)
            .setSmallIcon(R.drawable.ic_stat_opensnap)
            .setLargeIcon(renderAvatarBitmap(peerEd25519PubHex, peerDisplayName))
            .setContentTitle("Nouvelle demande d'ami OpenSnap")
            .setContentText("$peerDisplayName ($peerOpenSnapTag) souhaite vous ajouter")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_input_add, "Accepter", acceptPending)
            .addAction(android.R.drawable.ic_delete, "Refuser", rejectPending)
            .build()

        NotificationManagerCompat.from(context).notify(notifId, notif)
    }

    /**
     * Triggers a high-priority Full-Screen Incoming WebRTC Audio/Video Call notification (`fullScreenIntent`).
     */
    fun showIncomingCallFullScreenNotification(
        context: Context,
        peerEd25519PubHex: String,
        peerX25519PubHex: String,
        peerRoutingPubHex: String,
        peerDisplayName: String,
        callSignalJson: String,
        isVideoCall: Boolean
    ) {
        if (!canPostNotifications(context)) return
        ensureChannelsCreated(context)

        val callIntent = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DaemonIpcContract.KEY_PEER_PUB_HEX, peerEd25519PubHex)
            putExtra(DaemonIpcContract.KEY_PEER_X25519_HEX, peerX25519PubHex)
            putExtra(DaemonIpcContract.KEY_PEER_ROUTING_HEX, peerRoutingPubHex)
            putExtra(DaemonIpcContract.KEY_PEER_DISPLAY_NAME, peerDisplayName)
            putExtra(DaemonIpcContract.KEY_WEBRTC_SIGNAL_JSON, callSignalJson)
        }
        val fullScreenPending = PendingIntent.getActivity(
            context,
            CALL_NOTIFICATION_ID,
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DECLINE_CALL
            putExtra(DaemonIpcContract.KEY_PEER_PUB_HEX, peerEd25519PubHex)
            putExtra(DaemonIpcContract.KEY_PEER_X25519_HEX, peerX25519PubHex)
            putExtra(DaemonIpcContract.KEY_PEER_ROUTING_HEX, peerRoutingPubHex)
            putExtra(DaemonIpcContract.KEY_WEBRTC_SIGNAL_JSON, callSignalJson)
        }
        val declinePending = PendingIntent.getBroadcast(
            context,
            CALL_NOTIFICATION_ID + 1,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val kindLabel = if (isVideoCall) "Appel Vidéo P2P Chiffré" else "Appel Vocal P2P Chiffré"
        val notif = NotificationCompat.Builder(context, CHANNEL_CALLS_URGENT)
            .setSmallIcon(R.drawable.ic_stat_opensnap)
            .setLargeIcon(renderAvatarBitmap(peerEd25519PubHex, peerDisplayName))
            .setContentTitle(peerDisplayName)
            .setContentText(kindLabel)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .addAction(android.R.drawable.ic_menu_call, "Répondre", fullScreenPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Refuser", declinePending)
            .build()

        NotificationManagerCompat.from(context).notify(CALL_NOTIFICATION_ID, notif)
    }

    fun clearPeerNotification(context: Context, peerEd25519PubHex: String) {
        conversationHistory.remove(peerEd25519PubHex)
        NotificationManagerCompat.from(context).cancel(notificationIdForPeer(peerEd25519PubHex))
    }

    fun clearFriendRequestNotification(context: Context, peerEd25519PubHex: String) {
        NotificationManagerCompat.from(context).cancel(notificationIdForPeer("req:$peerEd25519PubHex"))
    }

    fun clearCallNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(CALL_NOTIFICATION_ID)
    }

    fun notificationIdForPeer(key: String): Int =
        10_000 + ((key.hashCode() and 0x7FFFFFFF) % 800_000)

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Generates a high-DPI circular Identicon avatar bitmap derived deterministically from `ed25519PubHex`.
     */
    fun renderAvatarBitmap(ed25519PubHex: String, displayName: String, sizePx: Int = 144): Bitmap {
        val digest = CryptoEngine.sha256(ed25519PubHex.encodeToByteArray())
        val hue = ((digest[0].toInt() and 0xFF) * 360f) / 255f
        val bgColor = Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.85f))

        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        val radius = sizePx / 2f
        canvas.drawCircle(radius, radius, radius, circlePaint)

        val initials = displayName.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "OS" }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#090B10")
            textSize = sizePx * 0.40f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val textY = radius - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initials, radius, textY, textPaint)
        return bmp
    }
}
