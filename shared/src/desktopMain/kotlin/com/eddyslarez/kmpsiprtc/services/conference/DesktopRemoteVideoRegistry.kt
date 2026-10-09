package com.eddyslarez.kmpsiprtc.services.conference

import com.eddyslarez.kmpsiprtc.services.livekit.LiveKitParticipantInfo
import com.eddyslarez.kmpsiprtc.services.livekit.LiveKitTrackSource
import dev.onvoid.webrtc.media.video.VideoTrack

/**
 * Relaciona receptores WebRTC con publicaciones del SFU por SID y MSID/MID.
 * Nunca asigna una pista al primer participante ni crea participantes ficticios.
 * Lo llaman tanto el hilo nativo de WebRTC como el lector del WebSocket.
 */
internal class DesktopRemoteVideoRegistry {
    private data class Receiver(val track: VideoTrack, val streams: List<String>, val mid: String?)
    private data class Binding(val mid: String?, val stream: String, val nativeId: String) {
        val participantSid: String? get() = stream.substringBefore('|').takeIf { it.startsWith("PA_") }
        val trackSid: String? get() = stream.substringAfter('|', "").takeIf { it.startsWith("TR_") }
            ?: nativeId.takeIf { it.startsWith("TR_") }
    }
    private val participants = linkedMapOf<String, LiveKitParticipantInfo>()
    private val receivers = linkedMapOf<String, Receiver>()
    private var bindings: List<Binding> = emptyList()
    private var hasOffer = false

    @Synchronized fun updateParticipant(info: LiveKitParticipantInfo) {
        val previous = participants[info.identity]
        if (info.state == 3 && previous?.sid != info.sid) return
        // Al desconectar/reemplazar una sesión o despublicar, soltar también el receptor.
        // Despublicar invalida handles, pero puede reutilizar el mismo receptor/MID
        // al volver a publicar sin que WebRTC emita otro OnTrack.
        if (info.state == 3 || (previous != null && previous.sid != info.sid)) {
            receivers.entries.removeAll { (_, receiver) -> resolve(receiver)?.first?.sid == previous?.sid }
        }
        if (info.state == 3) participants.remove(info.identity) else participants[info.identity] = info
    }

    @Synchronized fun updateOffer(sdp: String) {
        bindings = parseBindings(sdp)
        hasOffer = true
        receivers.entries.removeAll { (_, receiver) -> findBinding(receiver) == null }
    }

    @Synchronized fun added(track: VideoTrack, streams: List<String>, mid: String?) {
        val previous = receivers[track.id]
        val receiver = Receiver(track, streams.ifEmpty { previous?.streams.orEmpty() }, mid ?: previous?.mid)
        if (!hasOffer || findBinding(receiver) != null) receivers[track.id] = receiver
    }

    @Synchronized fun removed(nativeId: String) { receivers.remove(nativeId) }

    @Synchronized fun clear() {
        participants.clear()
        receivers.clear()
        bindings = emptyList()
        hasOffer = false
    }

    @Synchronized fun handles(): List<LkVideoTrackHandle> = receivers.values.mapNotNull { receiver ->
        val (participant, publication) = resolve(receiver) ?: return@mapNotNull null
        if (publication.muted) return@mapNotNull null
        LkVideoTrackHandle(participant.identity, publication.sid, receiver.track,
            publication.source == LiveKitTrackSource.SCREEN_SHARE.value)
    }.distinctBy { Triple(it.participantIdentity, it.isScreenShare, it.trackSid) }
        .distinctBy { it.participantIdentity to it.isScreenShare }

    private fun findBinding(receiver: Receiver): Binding? =
        bindings.firstOrNull { receiver.mid != null && it.mid == receiver.mid } ?:
        bindings.firstOrNull { it.nativeId == receiver.track.id } ?:
        bindings.firstOrNull { it.stream in receiver.streams }

    private fun resolve(receiver: Receiver): Pair<LiveKitParticipantInfo, com.eddyslarez.kmpsiprtc.services.livekit.LiveKitTrackInfo>? {
        val binding = findBinding(receiver)
        val stream = receiver.streams.firstOrNull { it.contains('|') }
        val trackSid = binding?.trackSid ?: stream?.substringAfter('|')?.takeIf { it.startsWith("TR_") }
            ?: receiver.track.id.takeIf { it.startsWith("TR_") } ?: return null
        val participantSid = binding?.participantSid ?: stream?.substringBefore('|')?.takeIf { it.startsWith("PA_") }
        participants.values.forEach { participant ->
            if (participantSid != null && participant.sid != participantSid) return@forEach
            participant.tracks.firstOrNull { it.sid == trackSid && it.type == 1 &&
                (it.source == LiveKitTrackSource.CAMERA.value || it.source == LiveKitTrackSource.SCREEN_SHARE.value)
            }?.let { return participant to it }
        }
        return null
    }

    private fun parseBindings(sdp: String): List<Binding> {
        val result = mutableListOf<Binding>()
        val sections = sdp.replace("\r", "").split("\nm=")
        sections.drop(1).forEach { section ->
            val lines = section.lines()
            if (!lines.first().startsWith("video ") || lines.first().split(' ').getOrNull(1) == "0" ||
                lines.any { it == "a=inactive" || it == "a=recvonly" }) return@forEach
            val mid = lines.firstOrNull { it.startsWith("a=mid:") }?.substringAfter(':')
            val msids = lines.filter { it.startsWith("a=msid:") }.map { it.substringAfter("a=msid:") }
                .ifEmpty { lines.filter { it.startsWith("a=ssrc:") && it.contains(" msid:") }.map { it.substringAfter(" msid:") } }
            msids.distinct().forEach { msid ->
                val parts = msid.trim().split(Regex("\\s+"))
                if (parts.size >= 2) result += Binding(mid, parts[0], parts[1])
            }
        }
        return result
    }
}
