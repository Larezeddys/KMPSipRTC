package com.eddyslarez.kmpsiprtc.services.matrix

data class MatrixRoom(
    val id: String,
    val name: String,
    /**
     * URL HTTPS resuelta del avatar de la room (incluye `?access_token=`).
     * null si el room no tiene avatar configurado. Se resuelve a partir del
     * `avatar_url` en `mxc://...` del state event m.room.avatar (o, para DMs
     * sin avatar de room, del avatar del hero).
     */
    val avatarUrl: String?,
    val isDirect: Boolean,
    val isEncrypted: Boolean,
    val unreadCount: Int,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null,
    val members: List<String> = emptyList(),
)

/**
 * Estado de una llamada Matrix nativa (m.call.*). Reactivado: las llamadas 1:1
 * pueden ir por VoIP nativo de Matrix además de por LiveKit (el router unificado
 * decide la ruta). Ver [MatrixCallManager].
 */
data class MatrixCall(
    val callId: String,
    val roomId: String,
    val isVideo: Boolean,
    val state: MatrixCallState,
    val remoteSdp: String? = null,
    val localSdp: String? = null,
    val participants: List<String> = emptyList()
)

enum class MatrixCallState {
    IDLE,
    INVITING,
    RINGING,
    CONNECTING,
    CONNECTED,
    ENDED,
    ERROR
}

sealed class MatrixConnectionState {
    object Disconnected : MatrixConnectionState()
    object Initialized : MatrixConnectionState()
    object Connecting : MatrixConnectionState()
    object Connected : MatrixConnectionState()
    data class Error(val message: String) : MatrixConnectionState()
}

/**
 * Estado de entrega de un mensaje propio (estilo "enviado / recibido / leído").
 */
enum class MessageDelivery {
    /** Optimista local: aún no confirmado por el servidor. */
    SENDING,
    /** Confirmado por el servidor (eco de sync recibido). */
    SENT,
    /** Falló el envío. */
    FAILED,
    /** Al menos otro participante envió un read receipt sobre este mensaje. */
    READ,
}

/**
 * Agregación de una reacción emoji concreta sobre un mensaje.
 * @param count número total de usuarios que reaccionaron con este emoji
 * @param reactedByMe true si el usuario actual reaccionó con este emoji
 * @param reactionEventIds ids de los eventos m.reaction (para poder redactarlos al quitar la reacción)
 */
data class ReactionInfo(
    val count: Int,
    val reactedByMe: Boolean,
    val reactionEventIds: List<String> = emptyList(),
)

data class MatrixMessage(
    val id: String,
    val roomId: String,
    val senderId: String,
    val senderDisplayName: String?,
    val content: String,
    val timestamp: Long,
    val type: MessageType,
    /**
     * URL HTTPS resuelta de la media (si es un mensaje IMAGE/VIDEO/AUDIO/FILE).
     * Apunta al endpoint `/_matrix/media/v3/download/...` con el access token
     * adjunto (`?access_token=...`). Para tipo TEXT siempre es null.
     */
    val mediaUrl: String? = null,
    /**
     * URI `mxc://` original de la media. Permite descargar los bytes vía el
     * MediaService de Trixnity (que respeta el cache Okio). null para TEXT.
     */
    val mxcUrl: String? = null,
    /**
     * Nombre original del archivo (sólo para tipos no-TEXT). Útil para mostrar
     * "📎 informe.pdf" o como caption de la imagen.
     */
    val fileName: String? = null,
    /** mxc/URL resuelta del avatar del remitente (para pintar la burbuja). */
    val senderAvatarUrl: String? = null,
    /** eventId del mensaje al que este responde (m.in_reply_to), o null. */
    val replyToEventId: String? = null,
    /** eventId raíz del thread al que pertenece (rel_type m.thread), o null. */
    val threadRootId: String? = null,
    /** true si el mensaje fue editado (tiene un m.replace aplicado). */
    val isEdited: Boolean = false,
    /** Reacciones agregadas: emoji -> info. */
    val reactions: Map<String, ReactionInfo> = emptyMap(),
    /** Estado de entrega para mensajes propios. */
    val deliveryState: MessageDelivery = MessageDelivery.SENT,
    /** userIds que enviaron read receipt sobre este mensaje (estilo "visto por"). */
    val readBy: List<String> = emptyList(),
)

enum class MessageType {
    TEXT,
    NOTICE,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    STICKER,
    LOCATION,

    /** Evento m.call.invite mostrado en el timeline del chat. */
    CALL_INVITE,

    /** Evento m.call.answer mostrado en el timeline del chat. */
    CALL_ANSWER,

    /** Evento m.call.hangup mostrado en el timeline del chat. */
    CALL_HANGUP,
}

/**
 * Presencia de un usuario (online/offline/unavailable).
 */
enum class MatrixPresence { ONLINE, OFFLINE, UNAVAILABLE }

data class MatrixUserPresence(
    val userId: String,
    val presence: MatrixPresence,
    val lastActiveAgo: Long? = null,
    val statusMessage: String? = null,
    val currentlyActive: Boolean? = null,
)

/**
 * Miembro de una sala con su display name resuelto, avatar y power level.
 */
data class MatrixMember(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val membership: String = "join",
    val powerLevel: Long = 0,
)

/**
 * Perfil global de un usuario (fuera del contexto de una sala).
 */
data class MatrixUserProfile(
    val userId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
)
