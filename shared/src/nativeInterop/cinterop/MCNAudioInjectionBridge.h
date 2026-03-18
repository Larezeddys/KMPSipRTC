#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Bridge nativo para inyectar audio PCM en la pipeline de WebRTC iOS.
 *
 * Crea un RTCAudioDevice personalizado que lee de una cola interna
 * en lugar de capturar del microfono. Permite enviar audio traducido
 * al peer remoto (traduccion L->R).
 *
 * Uso desde Kotlin:
 *   1. val bridge = MCNAudioInjectionBridge()
 *   2. bridge.activateWithSender(rtpSender.ios)  // Reemplaza track del mic
 *   3. bridge.start()
 *   4. bridge.pushAudioData(data, 48000, 1)      // PCM16 mono
 *   5. bridge.deactivateWithSender(rtpSender.ios) // Restaura mic
 *   6. bridge.dispose()
 */
@interface MCNAudioInjectionBridge : NSObject

/**
 * Activa la inyeccion: crea factory con audio device custom,
 * crea audio track, y reemplaza el track en el RTP sender dado.
 * @param sender Objeto nativo RTCRtpSender (pasado como id desde Kotlin)
 * @return YES si la activacion fue exitosa
 */
- (BOOL)activateWithSender:(id _Nonnull)sender;

/**
 * Desactiva la inyeccion: detiene entrega, restaura track original en sender.
 * @param sender Objeto nativo RTCRtpSender
 */
- (void)deactivateWithSender:(id _Nonnull)sender;

/**
 * Empuja datos PCM16 LE mono para inyeccion.
 * Los datos se dividen en frames de 10ms y se encolan.
 * @param data Bytes PCM crudos
 * @param sampleRate Frecuencia (48000 esperado; se descarta si no coincide)
 * @param channels Canales (1 esperado)
 */
- (void)pushAudioData:(NSData * _Nonnull)data
           sampleRate:(int)sampleRate
             channels:(int)channels;

/** Inicia el timer de entrega de audio (cada 10ms) */
- (void)start;

/** Detiene el timer de entrega */
- (void)stop;

/** Libera todos los recursos */
- (void)dispose;

/** Si la inyeccion esta activa */
@property (nonatomic, readonly) BOOL isActive;

@end

NS_ASSUME_NONNULL_END
