package io.github.axeldelaro.opensnap.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.crypto.HardwareKeystoreManager
import io.github.axeldelaro.opensnap.data.db.DeliveryState
import io.github.axeldelaro.opensnap.data.db.FriendStatus
import io.github.axeldelaro.opensnap.data.db.MessageContentType
import io.github.axeldelaro.opensnap.data.db.MessageEntity
import io.github.axeldelaro.opensnap.data.db.OpenSnapDatabase
import io.github.axeldelaro.opensnap.data.ttl.TtlPurgeEngine
import io.github.axeldelaro.opensnap.network.protocol.ChatTextPayload
import io.github.axeldelaro.opensnap.network.protocol.PacketType
import io.github.axeldelaro.opensnap.network.protocol.ReceiptPayload
import io.github.axeldelaro.opensnap.network.protocol.WebRtcSignalPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Handles inline Notification actions directly inside `:network_daemon` WITHOUT launching the UI:
 * - `ACTION_DIRECT_REPLY` (`RemoteInput` instant encrypted reply)
 * - `ACTION_MARK_READ` (sends E2EE `OPENED` receipt + clears badge)
 * - `ACTION_ACCEPT_FRIEND` / `ACTION_REJECT_FRIEND`
 * - `ACTION_DECLINE_CALL`
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DIRECT_REPLY = "io.github.axeldelaro.opensnap.ACTION_DIRECT_REPLY"
        const val ACTION_MARK_READ = "io.github.axeldelaro.opensnap.ACTION_MARK_READ"
        const val ACTION_ACCEPT_FRIEND = "io.github.axeldelaro.opensnap.ACTION_ACCEPT_FRIEND"
        const val ACTION_REJECT_FRIEND = "io.github.axeldelaro.opensnap.ACTION_REJECT_FRIEND"
        const val ACTION_DECLINE_CALL = "io.github.axeldelaro.opensnap.ACTION_DECLINE_CALL"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handleAction(appContext, intent)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleAction(context: Context, intent: Intent) {
        val identity = HardwareKeystoreManager.loadIdentitySession(context) ?: return
        val dao = OpenSnapDatabase.getInstance(context).dao()
        val nowMs = System.currentTimeMillis()

        when (intent.action) {
            ACTION_DIRECT_REPLY -> {
                val remoteInputBundle = RemoteInput.getResultsFromIntent(intent) ?: return
                val replyText = remoteInputBundle
                    .getCharSequence(NotificationHelper.KEY_DIRECT_REPLY_TEXT)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (replyText.isEmpty()) return

                val peerHex = intent.getStringExtra(DaemonIpcContract.KEY_PEER_PUB_HEX) ?: return
                val friend = dao.getFriendByPubKey(peerHex) ?: return
                val msgId = CryptoEngine.randomHex(16)

                // 1. Persist outgoing 24h message in SQLCipher
                val messageEntity = MessageEntity(
                    messageId = msgId,
                    peerEd25519PubHex = peerHex,
                    isOutgoing = true,
                    contentType = MessageContentType.TEXT,
                    textContent = replyText,
                    deliveryStatus = DeliveryState.SENT,
                    sentAtMs = nowMs,
                    expiresAtMs = nowMs + TtlPurgeEngine.TTL_24H_MS
                )
                dao.insertMessage(messageEntity)
                dao.clearFriendUnreadCount(peerHex)
                dao.updateFriendConversationSummary(
                    ed25519PubHex = peerHex,
                    preview = "Vous : $replyText",
                    activityAtMs = nowMs,
                    unreadDelta = 0
                )

                // 2. Dispatch E2EE message packet via `:network_daemon`
                val bodyJson = CryptoEngine.canonicalJson.encodeToString(
                    ChatTextPayload(text = replyText)
                )
                OpenSnapDaemonService.enqueueOutboundViaIntent(
                    context,
                    Bundle().apply {
                        putString(DaemonIpcContract.KEY_PEER_PUB_HEX, peerHex)
                        putString(DaemonIpcContract.KEY_PEER_X25519_HEX, friend.x25519PubHex)
                        putString(DaemonIpcContract.KEY_PEER_ROUTING_HEX, friend.routingPubHex)
                        putString(DaemonIpcContract.KEY_PEER_RATCHET_HEX, friend.peerRatchetX25519PubHex)
                        putString(DaemonIpcContract.KEY_PACKET_TYPE, PacketType.CHAT_TEXT)
                        putString(DaemonIpcContract.KEY_PACKET_ID, msgId)
                        putString(DaemonIpcContract.KEY_BODY_JSON, bodyJson)
                    }
                )

                // 3. Echo reply cleanly inside MessagingStyle notification
                NotificationHelper.showIncomingMessageNotification(
                    context = context,
                    myDisplayName = identity.displayName,
                    peerEd25519PubHex = peerHex,
                    peerX25519PubHex = friend.x25519PubHex,
                    peerRoutingPubHex = friend.routingPubHex,
                    peerRatchetPubHex = friend.peerRatchetX25519PubHex,
                    peerDisplayName = friend.displayName,
                    messageId = msgId,
                    displayBody = replyText,
                    isVoiceMessage = false,
                    isSelfReplyEcho = true
                )
            }

            ACTION_MARK_READ -> {
                val peerHex = intent.getStringExtra(DaemonIpcContract.KEY_PEER_PUB_HEX) ?: return
                val friend = dao.getFriendByPubKey(peerHex)
                val unread = dao.getUnreadIncomingMessages(peerHex, nowMs)
                unread.forEach { dao.updateMessageDeliveryStatus(it.messageId, DeliveryState.OPENED) }
                dao.clearFriendUnreadCount(peerHex)
                NotificationHelper.clearPeerNotification(context, peerHex)

                if (friend != null && unread.isNotEmpty()) {
                    val receiptJson = CryptoEngine.canonicalJson.encodeToString(
                        ReceiptPayload(
                            messageIds = unread.map { it.messageId },
                            status = DeliveryState.OPENED
                        )
                    )
                    OpenSnapDaemonService.enqueueOutboundViaIntent(
                        context,
                        Bundle().apply {
                            putString(DaemonIpcContract.KEY_PEER_PUB_HEX, peerHex)
                            putString(DaemonIpcContract.KEY_PEER_X25519_HEX, friend.x25519PubHex)
                            putString(DaemonIpcContract.KEY_PEER_ROUTING_HEX, friend.routingPubHex)
                            putString(DaemonIpcContract.KEY_PEER_RATCHET_HEX, friend.peerRatchetX25519PubHex)
                            putString(DaemonIpcContract.KEY_PACKET_TYPE, PacketType.RECEIPT)
                            putString(DaemonIpcContract.KEY_PACKET_ID, CryptoEngine.randomHex(16))
                            putString(DaemonIpcContract.KEY_BODY_JSON, receiptJson)
                        }
                    )
                }
            }

            ACTION_ACCEPT_FRIEND -> {
                val peerHex = intent.getStringExtra(DaemonIpcContract.KEY_PEER_PUB_HEX) ?: return
                val friend = dao.getFriendByPubKey(peerHex) ?: return
                dao.updateFriendStatus(peerHex, FriendStatus.ACCEPTED, nowMs)
                dao.updateFriendConversationSummary(
                    ed25519PubHex = peerHex,
                    preview = "Ami accepté • Tunnel E2EE ouvert",
                    activityAtMs = nowMs,
                    unreadDelta = 0
                )
                NotificationHelper.clearFriendRequestNotification(context, peerHex)

                OpenSnapDaemonService.enqueueOutboundViaIntent(
                    context,
                    Bundle().apply {
                        putString(DaemonIpcContract.KEY_PEER_PUB_HEX, peerHex)
                        putString(DaemonIpcContract.KEY_PEER_X25519_HEX, friend.x25519PubHex)
                        putString(DaemonIpcContract.KEY_PEER_ROUTING_HEX, friend.routingPubHex)
                        putString(DaemonIpcContract.KEY_PACKET_TYPE, PacketType.FRIEND_ACCEPT)
                        putString(DaemonIpcContract.KEY_PACKET_ID, CryptoEngine.randomHex(16))
                        putString(DaemonIpcContract.KEY_BODY_JSON, "{}")
                        putBoolean("sync_vault_after", true)
                    }
                )
            }

            ACTION_REJECT_FRIEND -> {
                val peerHex = intent.getStringExtra(DaemonIpcContract.KEY_PEER_PUB_HEX) ?: return
                dao.deleteFriend(peerHex)
                NotificationHelper.clearFriendRequestNotification(context, peerHex)
            }

            ACTION_DECLINE_CALL -> {
                NotificationHelper.clearCallNotification(context)
                val peerHex = intent.getStringExtra(DaemonIpcContract.KEY_PEER_PUB_HEX) ?: return
                val friend = dao.getFriendByPubKey(peerHex) ?: return
                val sigJson = intent.getStringExtra(DaemonIpcContract.KEY_WEBRTC_SIGNAL_JSON) ?: return
                val incomingSig = runCatching {
                    CryptoEngine.canonicalJson.decodeFromString<WebRtcSignalPayload>(sigJson)
                }.getOrNull() ?: return

                val hangupJson = CryptoEngine.canonicalJson.encodeToString(
                    WebRtcSignalPayload(
                        callId = incomingSig.callId,
                        signalType = "HANGUP",
                        isVideoCall = incomingSig.isVideoCall
                    )
                )
                OpenSnapDaemonService.enqueueOutboundViaIntent(
                    context,
                    Bundle().apply {
                        putString(DaemonIpcContract.KEY_PEER_PUB_HEX, peerHex)
                        putString(DaemonIpcContract.KEY_PEER_X25519_HEX, friend.x25519PubHex)
                        putString(DaemonIpcContract.KEY_PEER_ROUTING_HEX, friend.routingPubHex)
                        putString(DaemonIpcContract.KEY_PACKET_TYPE, PacketType.WEBRTC_SIGNAL)
                        putString(DaemonIpcContract.KEY_PACKET_ID, CryptoEngine.randomHex(16))
                        putString(DaemonIpcContract.KEY_BODY_JSON, hangupJson)
                    }
                )
            }
        }
    }
}
