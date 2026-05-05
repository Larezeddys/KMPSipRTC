package com.eddyslarez.kmpsiprtc.services.livekit

/**
 * Codificador/decodificador protobuf manual para el protocolo de signaling de LiveKit.
 *
 * Solo implementa los ~8 tipos de mensaje necesarios para publicar/suscribir audio.
 * Formato wire de protobuf:
 *   - Tag = (field_number << 3) | wire_type
 *   - wire_type 0 = varint
 *   - wire_type 2 = length-delimited (string, bytes, nested message)
 */
object LiveKitProto {

    // ======================= ENCODING =======================

    /**
     * Codifica SignalRequest con un SessionDescription en campo "offer" (field 1)
     */
    fun encodeOffer(sdp: String, type: String = "offer"): ByteArray {
        val sessionDesc = encodeSessionDescription(sdp, type)
        return encodeSignalRequest(fieldNumber = 1, value = sessionDesc)
    }

    /**
     * Codifica SignalRequest con un SessionDescription en campo "answer" (field 2)
     */
    fun encodeAnswer(sdp: String, type: String = "answer"): ByteArray {
        val sessionDesc = encodeSessionDescription(sdp, type)
        return encodeSignalRequest(fieldNumber = 2, value = sessionDesc)
    }

    /**
     * Codifica SignalRequest con un TrickleRequest (field 3)
     * @param candidateInit JSON string del ICE candidate
     * @param target 0 = PUBLISHER, 1 = SUBSCRIBER
     */
    fun encodeTrickle(candidateInit: String, target: Int): ByteArray {
        val trickle = encodeTrickleRequest(candidateInit, target)
        return encodeSignalRequest(fieldNumber = 3, value = trickle)
    }

    /**
     * Codifica SignalRequest con un AddTrackRequest (field 4)
     * @param cid Client-side track ID
     * @param name Nombre del track (ej: "microphone")
     * @param trackType 1 = AUDIO, 2 = VIDEO
     * @param source 2 = MICROPHONE, 1 = CAMERA
     */
    fun encodeAddTrack(
        cid: String,
        name: String = "microphone",
        trackType: Int = LiveKitTrackType.AUDIO.value,
        source: Int = LiveKitTrackSource.MICROPHONE.value
    ): ByteArray {
        val addTrack = buildByteArray {
            // field 1: cid (string)
            writeTag(1, 2)
            writeString(cid)
            // field 2: name (string)
            writeTag(2, 2)
            writeString(name)
            // field 3: type (enum/varint)
            writeTag(3, 0)
            writeVarint(trackType.toLong())
            // field 5: source (enum/varint)
            writeTag(5, 0)
            writeVarint(source.toLong())
        }
        return encodeSignalRequest(fieldNumber = 4, value = addTrack)
    }

    /**
     * Codifica SignalRequest con LeaveRequest (field 8)
     */
    fun encodeLeave(): ByteArray {
        // LeaveRequest vacio
        val leave = ByteArray(0)
        return encodeSignalRequest(fieldNumber = 8, value = leave)
    }

    // ======================= DECODING =======================

    /**
     * Decodifica un SignalResponse recibido del servidor LiveKit
     */
    fun decodeSignalResponse(bytes: ByteArray): LiveKitSignalMessage {
        val reader = ProtoReader(bytes)

        while (reader.hasMore()) {
            val tag = reader.readTag()
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                // field 1: JoinResponse
                1 -> {
                    if (wireType != 2) { reader.skip(wireType); continue }
                    val data = reader.readBytes()
                    return LiveKitSignalMessage.Join(decodeJoinResponse(data))
                }
                // field 2: Answer (SessionDescription)
                2 -> {
                    if (wireType != 2) { reader.skip(wireType); continue }
                    val data = reader.readBytes()
                    return LiveKitSignalMessage.Answer(decodeSessionDescription(data))
                }
                // field 3: Offer (SessionDescription)
                3 -> {
                    if (wireType != 2) { reader.skip(wireType); continue }
                    val data = reader.readBytes()
                    return LiveKitSignalMessage.Offer(decodeSessionDescription(data))
                }
                // field 4: Trickle
                4 -> {
                    if (wireType != 2) { reader.skip(wireType); continue }
                    val data = reader.readBytes()
                    return LiveKitSignalMessage.Trickle(decodeTrickle(data))
                }
                // field 5: Update (ParticipantUpdate)
                5 -> {
                    if (wireType != 2) { reader.skip(wireType); continue }
                    val data = reader.readBytes()
                    return LiveKitSignalMessage.ParticipantUpdated(decodeParticipantUpdate(data))
                }
                // field 6: TrackPublished
                6 -> {
                    if (wireType != 2) { reader.skip(wireType); continue }
                    val data = reader.readBytes()
                    return LiveKitSignalMessage.TrackPublished(decodeTrackPublished(data))
                }
                // field 8: Leave
                8 -> {
                    if (wireType != 2) { reader.skip(wireType); continue }
                    val data = reader.readBytes()
                    return decodeLeave(data)
                }
                else -> {
                    reader.skip(wireType)
                }
            }
        }

        return LiveKitSignalMessage.Unknown(-1)
    }

    // ======================= INTERNAL ENCODING =======================

    private fun encodeSignalRequest(fieldNumber: Int, value: ByteArray): ByteArray {
        return buildByteArray {
            writeTag(fieldNumber, 2)
            writeBytes(value)
        }
    }

    private fun encodeSessionDescription(sdp: String, type: String): ByteArray {
        return buildByteArray {
            // field 1: type (string)
            writeTag(1, 2)
            writeString(type)
            // field 2: sdp (string)
            writeTag(2, 2)
            writeString(sdp)
        }
    }

    private fun encodeTrickleRequest(candidateInit: String, target: Int): ByteArray {
        return buildByteArray {
            // field 1: candidateInit (string)
            writeTag(1, 2)
            writeString(candidateInit)
            // field 2: target (enum/varint)
            if (target != 0) {
                writeTag(2, 0)
                writeVarint(target.toLong())
            }
        }
    }

    // ======================= INTERNAL DECODING =======================

    private fun decodeJoinResponse(data: ByteArray): LiveKitJoinResponse {
        val reader = ProtoReader(data)
        var room: LiveKitRoom? = null
        var participantSid = ""
        var participantIdentity = ""
        var participantName = ""
        val otherParticipants = mutableListOf<LiveKitParticipantInfo>()
        val iceServers = mutableListOf<LiveKitIceServer>()
        var subscriberPrimary = false
        var serverVersion = ""

        while (reader.hasMore()) {
            val tag = reader.readTag()
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                // field 1: Room (message)
                1 -> {
                    if (wireType == 2) {
                        val roomData = reader.readBytes()
                        room = decodeRoom(roomData)
                    } else reader.skip(wireType)
                }
                // field 2: ParticipantInfo (message) - local participant
                2 -> {
                    if (wireType == 2) {
                        val partData = reader.readBytes()
                        val info = decodeParticipantInfo(partData)
                        participantSid = info.sid
                        participantIdentity = info.identity
                        participantName = info.name
                    } else reader.skip(wireType)
                }
                // field 3: other_participants (repeated ParticipantInfo)
                3 -> {
                    if (wireType == 2) {
                        val partData = reader.readBytes()
                        otherParticipants.add(decodeParticipantInfo(partData))
                    } else reader.skip(wireType)
                }
                // field 4: server_version (string)
                4 -> {
                    if (wireType == 2) serverVersion = reader.readString()
                    else reader.skip(wireType)
                }
                // field 5: ice_servers (repeated message)
                5 -> {
                    if (wireType == 2) {
                        val iceData = reader.readBytes()
                        iceServers.add(decodeIceServer(iceData))
                    } else reader.skip(wireType)
                }
                // field 8: subscriber_primary (bool/varint)
                8 -> {
                    if (wireType == 0) subscriberPrimary = reader.readVarint() != 0L
                    else reader.skip(wireType)
                }
                else -> reader.skip(wireType)
            }
        }

        return LiveKitJoinResponse(
            room = room,
            participantSid = participantSid,
            participantIdentity = participantIdentity,
            participantName = participantName,
            otherParticipants = otherParticipants,
            iceServers = iceServers,
            subscriberPrimary = subscriberPrimary,
            serverVersion = serverVersion
        )
    }

    private fun decodeRoom(data: ByteArray): LiveKitRoom {
        val reader = ProtoReader(data)
        var sid = ""
        var name = ""
        var numParticipants = 0

        while (reader.hasMore()) {
            val tag = reader.readTag()
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> if (wireType == 2) sid = reader.readString() else reader.skip(wireType)
                2 -> if (wireType == 2) name = reader.readString() else reader.skip(wireType)
                6 -> if (wireType == 0) numParticipants = reader.readVarint().toInt() else reader.skip(wireType)
                else -> reader.skip(wireType)
            }
        }

        return LiveKitRoom(sid, name, numParticipants)
    }

    private fun decodeIceServer(data: ByteArray): LiveKitIceServer {
        val reader = ProtoReader(data)
        val urls = mutableListOf<String>()
        var username = ""
        var credential = ""

        while (reader.hasMore()) {
            val tag = reader.readTag()
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> if (wireType == 2) urls.add(reader.readString()) else reader.skip(wireType)
                2 -> if (wireType == 2) username = reader.readString() else reader.skip(wireType)
                3 -> if (wireType == 2) credential = reader.readString() else reader.skip(wireType)
                else -> reader.skip(wireType)
            }
        }

        return LiveKitIceServer(urls, username, credential)
    }

    private fun decodeSessionDescription(data: ByteArray): LiveKitSessionDescription {
        val reader = ProtoReader(data)
        var type = ""
        var sdp = ""

        while (reader.hasMore()) {
            val tag = reader.readTag()
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> if (wireType == 2) type = reader.readString() else reader.skip(wireType)
                2 -> if (wireType == 2) sdp = reader.readString() else reader.skip(wireType)
                else -> reader.skip(wireType)
            }
        }

        return LiveKitSessionDescription(type, sdp)
    }

    private fun decodeTrickle(data: ByteArray): LiveKitTrickle {
        val reader = ProtoReader(data)
        var candidateInit = ""
        var target = 0

        while (reader.hasMore()) {
            val tag = reader.readTag()
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> if (wireType == 2) candidateInit = reader.readString() else reader.skip(wireType)
                2 -> if (wireType == 0) target = reader.readVarint().toInt() else reader.skip(wireType)
                else -> reader.skip(wireType)
            }
        }

        return LiveKitTrickle(candidateInit, target)
    }

    private fun decodeTrackPublished(data: ByteArray): LiveKitTrackPublished {
        val reader = ProtoReader(data)
        var cid = ""
        var trackSid = ""
        var trackName = ""

        while (reader.hasMore()) {
            val tag = reader.readTag()
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> if (wireType == 2) cid = reader.readString() else reader.skip(wireType)
                // field 2: TrackInfo (message)
                2 -> {
                    if (wireType == 2) {
                        val trackData = reader.readBytes()
                        val trackReader = ProtoReader(trackData)
                        while (trackReader.hasMore()) {
                            val tTag = trackReader.readTag()
                            val tField = tTag shr 3
                            val tWire = tTag and 0x07
                            when (tField) {
                                1 -> if (tWire == 2) trackSid = trackReader.readString() else trackReader.skip(tWire)
                                3 -> if (tWire == 2) trackName = trackReader.readString() else trackReader.skip(tWire)
                                else -> trackReader.skip(tWire)
                            }
                        }
                    } else reader.skip(wireType)
                }
                else -> reader.skip(wireType)
            }
        }

        return LiveKitTrackPublished(cid, trackSid, trackName)
    }

    private fun decodeLeave(data: ByteArray): LiveKitSignalMessage.Leave {
        val reader = ProtoReader(data)
        var canReconnect = false
        var reason = 0

        while (reader.hasMore()) {
            val tag = reader.readTag()
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> if (wireType == 0) canReconnect = reader.readVarint() != 0L else reader.skip(wireType)
                2 -> if (wireType == 0) reason = reader.readVarint().toInt() else reader.skip(wireType)
                else -> reader.skip(wireType)
            }
        }

        return LiveKitSignalMessage.Leave(canReconnect, reason)
    }

    /**
     * Decodifica ParticipantInfo del protocolo LiveKit.
     * Proto fields: 1=sid, 2=identity, 3=state, 4=tracks, 5=metadata, 6=joined_at, 7=name, 9=permission, 10=region, 11=is_publisher
     */
    private fun decodeParticipantInfo(data: ByteArray): LiveKitParticipantInfo {
        val reader = ProtoReader(data)
        var sid = ""
        var identity = ""
        var name = ""
        var state = 0
        var isPublisher = false

        while (reader.hasMore()) {
            val tag = reader.readTag()
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> if (wireType == 2) sid = reader.readString() else reader.skip(wireType)
                2 -> if (wireType == 2) identity = reader.readString() else reader.skip(wireType)
                3 -> if (wireType == 0) state = reader.readVarint().toInt() else reader.skip(wireType)
                7 -> if (wireType == 2) name = reader.readString() else reader.skip(wireType)
                11 -> if (wireType == 0) isPublisher = reader.readVarint() != 0L else reader.skip(wireType)
                else -> reader.skip(wireType)
            }
        }

        return LiveKitParticipantInfo(
            sid = sid,
            identity = identity,
            name = name.ifEmpty { identity },
            state = state,
            isPublisher = isPublisher,
        )
    }

    /**
     * Decodifica ParticipantUpdate (field 5 de SignalResponse).
     * Proto: message UpdateParticipants { repeated ParticipantInfo participants = 1; }
     */
    private fun decodeParticipantUpdate(data: ByteArray): LiveKitParticipantUpdate {
        val reader = ProtoReader(data)
        val participants = mutableListOf<LiveKitParticipantInfo>()

        while (reader.hasMore()) {
            val tag = reader.readTag()
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> {
                    if (wireType == 2) {
                        participants.add(decodeParticipantInfo(reader.readBytes()))
                    } else reader.skip(wireType)
                }
                else -> reader.skip(wireType)
            }
        }

        return LiveKitParticipantUpdate(participants)
    }

    // ======================= PROTOBUF PRIMITIVES =======================

    /**
     * Builder para construir mensajes protobuf byte a byte
     */
    private class ProtoWriter {
        private val buffer = mutableListOf<Byte>()

        fun writeTag(fieldNumber: Int, wireType: Int) {
            writeVarint(((fieldNumber shl 3) or wireType).toLong())
        }

        fun writeVarint(value: Long) {
            var v = value
            while (v > 0x7F) {
                buffer.add(((v and 0x7F) or 0x80).toByte())
                v = v ushr 7
            }
            buffer.add((v and 0x7F).toByte())
        }

        fun writeString(value: String) {
            val bytes = value.encodeToByteArray()
            writeVarint(bytes.size.toLong())
            buffer.addAll(bytes.toList())
        }

        fun writeBytes(value: ByteArray) {
            writeVarint(value.size.toLong())
            buffer.addAll(value.toList())
        }

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }

    /**
     * Reader para decodificar mensajes protobuf
     */
    class ProtoReader(private val data: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean = pos < data.size

        fun readTag(): Int = readVarint().toInt()

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos++].toLong() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0L) break
                shift += 7
                if (shift > 63) throw IllegalStateException("Varint demasiado largo")
            }
            return result
        }

        fun readBytes(): ByteArray {
            val length = readVarint().toInt()
            if (length < 0 || pos + length > data.size) {
                throw IllegalStateException("Longitud invalida: $length (pos=$pos, size=${data.size})")
            }
            val result = data.copyOfRange(pos, pos + length)
            pos += length
            return result
        }

        fun readString(): String = readBytes().decodeToString()

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint() // varint
                1 -> pos += 8     // 64-bit
                2 -> {            // length-delimited
                    val len = readVarint().toInt()
                    pos += len
                }
                5 -> pos += 4     // 32-bit
                else -> throw IllegalStateException("Wire type desconocido: $wireType")
            }
        }
    }

    private inline fun buildByteArray(block: ProtoWriter.() -> Unit): ByteArray {
        val writer = ProtoWriter()
        writer.block()
        return writer.toByteArray()
    }
}
