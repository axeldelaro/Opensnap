package io.github.axeldelaro.opensnap.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object FriendStatus {
    const val PENDING_INCOMING = "PENDING_INCOMING"
    const val PENDING_OUTGOING = "PENDING_OUTGOING"
    const val ACCEPTED = "ACCEPTED"
    const val BLOCKED = "BLOCKED"
}

object MessageContentType {
    const val TEXT = "TEXT"
    const val VOICE = "VOICE"
    const val PHOTO = "PHOTO"
}

object DeliveryState {
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val OPENED = "OPENED"
}

@Entity(
    tableName = "friends",
    indices = [
        Index(value = ["openSnapTag"]),
        Index(value = ["routingPubHex"]),
        Index(value = ["status"])
    ]
)
data class FriendEntity(
    @PrimaryKey
    val ed25519PubHex: String,
    val x25519PubHex: String,
    val routingPubHex: String,
    val openSnapTag: String,
    val username: String,
    val displayName: String,
    val status: String,
    val peerRatchetX25519PubHex: String? = null,
    val isSafetyVerified: Boolean = false,
    val lastSeenAtMs: Long = 0L,
    val lastMessagePreview: String = "",
    val lastActivityAtMs: Long = 0L,
    val unreadCount: Int = 0
)

@Entity(
    tableName = "messages_24h",
    indices = [
        Index(value = ["peerEd25519PubHex", "sentAtMs"]),
        Index(value = ["expiresAtMs"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val messageId: String,
    val peerEd25519PubHex: String,
    val isOutgoing: Boolean,
    val contentType: String,
    val textContent: String = "",
    val encryptedMediaPath: String? = null,
    val mediaKeyHex: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val voiceDurationMs: Long = 0L,
    val waveformCsv: String? = null,
    val isViewOnce: Boolean = false,
    val isViewOnceConsumed: Boolean = false,
    val replyToMessageId: String? = null,
    val replyPreviewText: String? = null,
    val reactionsJson: String = "{}",
    val deliveryStatus: String = DeliveryState.SENT,
    val sentAtMs: Long,
    val expiresAtMs: Long
)

@Entity(
    tableName = "ratchet_keys",
    indices = [Index(value = ["expiresAtMs"])]
)
data class RatchetKeyEntity(
    @PrimaryKey
    val ratchetPubHex: String,
    val ratchetPrivHex: String,
    val createdAtMs: Long,
    val expiresAtMs: Long
)

@Entity(
    tableName = "seen_packets",
    indices = [Index(value = ["expiresAtMs"])]
)
data class SeenPacketEntity(
    @PrimaryKey
    val packetId: String,
    val senderEd25519PubHex: String,
    val receivedAtMs: Long,
    val expiresAtMs: Long
)
