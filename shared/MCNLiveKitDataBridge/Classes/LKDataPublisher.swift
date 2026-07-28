import Foundation
import LiveKitClient

/// Puente Swift para publicar datos de conferencia (chat, mano levantada) por
/// el canal RELIABLE de LiveKit desde Kotlin/Native.
///
/// `DataPublishOptions(reliable: true)` no se puede construir directamente
/// desde Kotlin/Native: el init designado del struct no esta marcado `@objc`
/// (y al declarar un init propio la clase pierde el `init()` heredado de
/// NSObject), asi que el header de compatibilidad ObjC que Kotlin consume no
/// expone ningun constructor utilizable para ese tipo. Este puente construye
/// `DataPublishOptions` del lado Swift, con acceso completo al SDK, y expone
/// una unica funcion `@objc` con tipos simples que Kotlin si puede llamar.
@objc public final class LKDataPublisher: NSObject {

    @objc(publishReliableWithRoom:data:topic:completion:)
    public static func publishReliable(
        room: Room,
        data: Data,
        topic: String?,
        completion: @escaping (NSError?) -> Void
    ) {
        Task {
            do {
                try await room.localParticipant.publish(
                    data: data,
                    options: DataPublishOptions(topic: topic, reliable: true)
                )
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }
}
