import Foundation
import LiveKitClient

/// Exposes LiveKit's Swift-only RemoteAudioTrack.volume setter to Kotlin/Native.
@objc public final class LKRemoteAudioTrackVolumeBridge: NSObject {
    @objc(setRemoteAudioTrackVolume:volume:)
    public static func setRemoteAudioTrackVolume(
        track: RemoteAudioTrack,
        volume: Double
    ) {
        track.volume = volume
    }
}
