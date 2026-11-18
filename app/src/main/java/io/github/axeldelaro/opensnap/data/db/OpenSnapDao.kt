package io.github.axeldelaro.opensnap.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OpenSnapDao {

    // -------------------- Friends & Whitelist --------------------

    @Query("SELECT * FROM friends WHERE status = 'ACCEPTED' ORDER BY lastActivityAtMs DESC, displayName ASC")
    fun observeAcceptedFriends(): Flow<List<FriendEntity>>

    @Query("SELECT * FROM friends WHERE status = 'PENDING_INCOMING' ORDER BY lastActivityAtMs DESC")
    fun observeIncomingRequests(): Flow<List<FriendEntity>>

    @Query("SELECT * FROM friends WHERE status = 'PENDING_OUTGOING' ORDER BY lastActivityAtMs DESC")
    fun observeOutgoingRequests(): Flow<List<FriendEntity>>

    @Query("SELECT * FROM friends WHERE status = 'BLOCKED' ORDER BY displayName ASC")
    fun observeBlockedFriends(): Flow<List<FriendEntity>>

    @Query("SELECT * FROM friends")
    suspend fun getAllFriendsSnapshot(): List<FriendEntity>

    @Query("SELECT * FROM friends WHERE ed25519PubHex = :ed25519PubHex LIMIT 1")
    suspend fun getFriendByPubKey(ed25519PubHex: String): FriendEntity?

    @Query("SELECT * FROM friends WHERE ed25519PubHex = :ed25519PubHex LIMIT 1")
    fun observeFriendByPubKey(ed25519PubHex: String): Flow<FriendEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFriend(friend: FriendEntity)

    @Query("UPDATE friends SET status = :newStatus, lastActivityAtMs = :nowMs WHERE ed25519PubHex = :ed25519PubHex")
    suspend fun updateFriendStatus(ed25519PubHex: String, newStatus: String, nowMs: Long)

    @Query("UPDATE friends SET isSafetyVerified = :verified WHERE ed25519PubHex = :ed25519PubHex")
    suspend fun updateFriendSafetyVerified(ed25519PubHex: String, verified: Boolean)

    @Query("UPDATE friends SET peerRatchetX25519PubHex = :ratchetPubHex, lastSeenAtMs = :nowMs WHERE ed25519PubHex = :ed25519PubHex")
    suspend fun updateFriendRatchetAndSeen(ed25519PubHex: String, ratchetPubHex: String?, nowMs: Long)

    @Query("UPDATE friends SET lastSeenAtMs = :nowMs WHERE ed25519PubHex = :ed25519PubHex")
    suspend fun touchFriendLastSeen(ed25519PubHex: String, nowMs: Long)

    @Query(
        """
        UPDATE friends
        SET lastMessagePreview = :preview,
            lastActivityAtMs = :activityAtMs,
            unreadCount = unreadCount + :unreadDelta
        WHERE ed25519PubHex = :ed25519PubHex
        """
    )
    suspend fun updateFriendConversationSummary(
        ed25519PubHex: String,
        preview: String,
        activityAtMs: Long,
        unreadDelta: Int
    )

    @Query("UPDATE friends SET unreadCount = 0 WHERE ed25519PubHex = :ed25519PubHex")
    suspend fun clearFriendUnreadCount(ed25519PubHex: String)

    @Query("DELETE FROM friends WHERE ed25519PubHex = :ed25519PubHex")
    suspend fun deleteFriend(ed25519PubHex: String)

    // -------------------- Strict 24-Hour Messages --------------------

    @Query(
        """
        SELECT * FROM messages_24h
        WHERE peerEd25519PubHex = :peerEd25519PubHex
          AND expiresAtMs > :nowMs
        ORDER BY sentAtMs ASC
        """
    )
    fun observeActiveMessages(peerEd25519PubHex: String, nowMs: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages_24h WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query(
        """
        SELECT * FROM messages_24h
        WHERE peerEd25519PubHex = :peerEd25519PubHex
          AND isOutgoing = 0
          AND deliveryStatus != 'OPENED'
          AND isViewOnce = 0
          AND expiresAtMs > :nowMs
        """
    )
    suspend fun getUnreadIncomingMessages(peerEd25519PubHex: String, nowMs: Long): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages_24h SET deliveryStatus = :status WHERE messageId = :messageId")
    suspend fun updateMessageDeliveryStatus(messageId: String, status: String)

    @Query("UPDATE messages_24h SET reactionsJson = :reactionsJson WHERE messageId = :messageId")
    suspend fun updateMessageReactions(messageId: String, reactionsJson: String)

    @Query(
        """
        UPDATE messages_24h
        SET isViewOnceConsumed = 1,
            encryptedMediaPath = NULL,
            mediaKeyHex = NULL,
            textContent = '🔥 Snap Vue unique ouvert'
        WHERE messageId = :messageId
        """
    )
    suspend fun markViewOnceConsumed(messageId: String)

    @Query("SELECT * FROM messages_24h WHERE expiresAtMs <= :nowMs")
    suspend fun getExpiredMessages(nowMs: Long): List<MessageEntity>

    @Query("SELECT * FROM messages_24h WHERE peerEd25519PubHex = :peerEd25519PubHex")
    suspend fun getMessagesForPeer(peerEd25519PubHex: String): List<MessageEntity>

    @Query("DELETE FROM messages_24h WHERE messageId IN (:ids)")
    suspend fun deleteMessagesByIds(ids: List<String>)

    @Query("DELETE FROM messages_24h WHERE peerEd25519PubHex = :peerEd25519PubHex")
    suspend fun deleteAllMessagesForPeer(peerEd25519PubHex: String)

    // -------------------- Forward Secrecy Ratchet Keys --------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRatchetKey(entity: RatchetKeyEntity)

    @Query("SELECT * FROM ratchet_keys WHERE expiresAtMs > :nowMs ORDER BY createdAtMs DESC LIMIT 6")
    suspend fun getActiveRatchetKeys(nowMs: Long): List<RatchetKeyEntity>

    @Query("DELETE FROM ratchet_keys WHERE expiresAtMs <= :nowMs")
    suspend fun deleteExpiredRatchetKeys(nowMs: Long)

    // -------------------- Anti-Replay Nonce Cache --------------------

    @Query("SELECT EXISTS(SELECT 1 FROM seen_packets WHERE packetId = :packetId)")
    suspend fun hasSeenPacket(packetId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordSeenPacket(entity: SeenPacketEntity): Long

    @Query("SELECT COUNT(*) FROM seen_packets WHERE senderEd25519PubHex = :senderHex AND receivedAtMs >= :sinceMs")
    suspend fun countRecentPacketsFromSender(senderHex: String, sinceMs: Long): Int

    @Query("DELETE FROM seen_packets WHERE expiresAtMs <= :nowMs")
    suspend fun deleteExpiredSeenPackets(nowMs: Long)
}
