package io.github.axeldelaro.opensnap.daemon

/**
 * Cross-Process (`:network_daemon` <-> Main UI Process) IPC Protocol Constants.
 */
object DaemonIpcContract {

    // Commands from UI Process -> `:network_daemon`
    const val MSG_REGISTER_UI_CLIENT = 101
    const val MSG_UNREGISTER_UI_CLIENT = 102
    const val MSG_SET_ACTIVE_CHAT_PEER = 103
    const val MSG_DISPATCH_OUTBOUND_PACKET = 104
    const val MSG_RELOAD_IDENTITY = 105
    const val MSG_LOOKUP_SHORT_TAG = 106
    const val MSG_SYNC_VAULT_BACKUP = 107
    const val MSG_RESTORE_VAULT_BACKUP = 108
    const val MSG_FORCE_RECONNECT = 109

    // Events from `:network_daemon` -> UI Process
    const val EVT_DAEMON_STATUS = 201
    const val EVT_PEER_PRESENCE = 202
    const val EVT_TAG_LOOKUP_RESULT = 203
    const val EVT_VAULT_RESTORED = 204
    const val EVT_WEBRTC_CALL_SIGNAL = 205

    // Bundle Keys
    const val KEY_PEER_PUB_HEX = "peer_pub_hex"
    const val KEY_PEER_DISPLAY_NAME = "peer_display_name"
    const val KEY_PEER_X25519_HEX = "peer_x25519_hex"
    const val KEY_PEER_ROUTING_HEX = "peer_routing_hex"
    const val KEY_PEER_RATCHET_HEX = "peer_ratchet_hex"
    const val KEY_PACKET_TYPE = "packet_type"
    const val KEY_PACKET_ID = "packet_id"
    const val KEY_BODY_JSON = "body_json"
    const val KEY_SHORT_TAG = "short_tag"
    const val KEY_REQUEST_ID = "request_id"
    const val KEY_CONTACT_CARD_JSON = "contact_card_json"
    const val KEY_CONNECTED_RELAYS = "connected_relays"
    const val KEY_TOTAL_RELAYS = "total_relays"
    const val KEY_TRANSPORT_LABEL = "transport_label"
    const val KEY_FASTLANE_PEERS_CSV = "fastlane_peers_csv"
    const val KEY_PRESENCE_STATE = "presence_state"
    const val KEY_IS_P2P_DIRECT = "is_p2p_direct"
    const val KEY_RESTORED_COUNT = "restored_count"
    const val KEY_WEBRTC_SIGNAL_JSON = "webrtc_signal_json"
}
