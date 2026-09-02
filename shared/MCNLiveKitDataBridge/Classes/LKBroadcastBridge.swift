import Foundation
import LiveKitClient
#if os(iOS)
import ReplayKit
#endif

/// Puente para compartir la PANTALLA COMPLETA del iPhone via Broadcast Upload Extension.
///
/// Mismo motivo que LKDataPublisher: `ScreenShareCaptureOptions` es `@objc` pero su init
/// NO lo esta (ScreenShareCaptureOptions.swift:37-48), asi que el header ObjC que consume
/// cinterop declara `- (instancetype)init SWIFT_UNAVAILABLE` y deja las propiedades
/// readonly. Desde Kotlin no hay forma de pedir useBroadcastExtension = true, y sin ese
/// flag LocalParticipant.swift:339-344 cae siempre en createInAppScreenShareTrack.
///
/// Todo se expone como `static func` (no computed property) a proposito: asi el header
/// generado trae solo `+ (BOOL)isConfigured` y cinterop no genera funcion y propiedad
/// homonimas.
@objc public final class LKBroadcastBridge: NSObject {

    @objc public static func appGroupIdentifier() -> String? {
        Bundle.main.infoDictionary?["RTCAppGroupIdentifier"] as? String
    }

    @objc public static func screenSharingExtension() -> String? {
        Bundle.main.infoDictionary?["RTCScreenSharingExtension"] as? String
    }

    /// Sin esto el track se publicaria y se quedaria negro para siempre.
    @objc public static func isConfigured() -> Bool {
        guard let group = appGroupIdentifier(), !group.isEmpty,
              let ext = screenSharingExtension(), !ext.isEmpty,
              FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) != nil
        else { return false }
        return true
    }

    /// Publica/despublica el track de broadcast. OJO: vuelve enseguida, NO espera a que el
    /// usuario pulse "Iniciar transmision" en la hoja del sistema. Ese momento llega por la
    /// notificacion Darwin iOS_BroadcastStarted.
    @objc(setBroadcastScreenShareWithRoom:enabled:fps:maxWidth:maxHeight:completion:)
    public static func setBroadcastScreenShare(
        room: Room,
        enabled: Bool,
        fps: Int,
        maxWidth: Int32,
        maxHeight: Int32,
        completion: @escaping (NSError?) -> Void
    ) {
        Task { @MainActor in
            do {
                let options = ScreenShareCaptureOptions(
                    dimensions: Dimensions(width: maxWidth, height: maxHeight),
                    fps: fps,
                    useBroadcastExtension: true
                )
                _ = try await room.localParticipant.set(
                    source: .screenShareVideo,
                    enabled: enabled,
                    captureOptions: options
                )
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    /// PLAN B. El SDK abre la hoja con el selector PRIVADO "buttonPressed:" y un
    /// `responds(to:)` que si falla no hace nada, ni error ni log
    /// (Track/Support/Extensions.swift:110-116). Esto usa sendActions, que es lo que
    /// LiveKit adopto en versiones posteriores. No se llama en el camino normal.
    @objc public static func showPicker() {
        Task { @MainActor in
            let view = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 44, height: 44))
            view.preferredExtension = screenSharingExtension()
            view.showsMicrophoneButton = false
            if let button = view.subviews.compactMap({ $0 as? UIButton }).first {
                button.sendActions(for: .touchUpInside)
            } else {
                RPSystemBroadcastPickerView.show(for: screenSharingExtension(), showsMicrophoneButton: false)
            }
        }
    }

    // LKSampleHandler.swift:54 y :68 POSTEAN iOS_BroadcastStarted / iOS_BroadcastStopped
    // (DarwinNotificationCenter.swift:19-35), pero el SDK no las escucha nunca: no hay un
    // solo CFNotificationCenterAddObserver en Sources/LiveKit. Sin esto la app no se entera
    // ni de que arranco la transmision ni de que el usuario la paro desde el Centro de Control.
    //
    // `self` en un metodo static es el METATIPO y Unmanaged<Instance: AnyObject> no lo
    // acepta ("requires that 'LKBroadcastBridge.Type' be a class type"). Hace falta un
    // objeto real como token de observador.
    private static let observerToken = NSObject()
    private static var onStarted: (() -> Void)?
    private static var onStopped: (() -> Void)?
    private static var observing = false

    @objc(startObservingWithOnStarted:onStopped:)
    public static func startObserving(
        onStarted started: @escaping () -> Void,
        onStopped stopped: @escaping () -> Void
    ) {
        onStarted = started
        onStopped = stopped
        guard !observing else { return }
        observing = true
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        for name in ["iOS_BroadcastStarted", "iOS_BroadcastStopped"] {
            CFNotificationCenterAddObserver(
                center,
                Unmanaged.passUnretained(observerToken).toOpaque(),
                { _, _, cfName, _, _ in
                    guard let raw = cfName?.rawValue as String? else { return }
                    DispatchQueue.main.async {
                        if raw == "iOS_BroadcastStarted" {
                            LKBroadcastBridge.onStarted?()
                        } else {
                            LKBroadcastBridge.onStopped?()
                        }
                    }
                },
                name as CFString,
                nil,
                .deliverImmediately
            )
        }
    }

    @objc public static func stopObserving() {
        onStarted = nil
        onStopped = nil
        guard observing else { return }
        observing = false
        CFNotificationCenterRemoveEveryObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            Unmanaged.passUnretained(observerToken).toOpaque()
        )
    }
}