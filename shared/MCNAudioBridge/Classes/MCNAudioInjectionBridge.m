#import "MCNAudioInjectionBridge.h"

#import <WebRTC/RTCAudioSource.h>
#import <WebRTC/RTCAudioTrack.h>
#import <WebRTC/RTCAudioDevice.h>
#import <WebRTC/RTCPeerConnectionFactory.h>
#import <WebRTC/RTCRtpSender.h>
#import <WebRTC/RTCMediaStreamTrack.h>

#import <AudioToolbox/AudioToolbox.h>

static const double kSampleRate = 48000.0;
static const int kFramesPerBuffer = 480;      // 10ms @ 48kHz
static const int kBufferBytes = 480 * 2;      // PCM16 mono = 960 bytes
static const NSTimeInterval kTimerInterval = 0.01; // 10ms

#pragma mark - MCNInjectionAudioDevice (private)

/**
 * Dispositivo de audio personalizado que implementa RTCAudioDevice.
 * En lugar de capturar del microfono, lee frames de la cola del bridge
 * y los entrega al ADM nativo de WebRTC via deliverRecordedData.
 */
@interface MCNInjectionAudioDevice : NSObject <RTCAudioDevice>
@property (nonatomic, weak) MCNAudioInjectionBridge *bridge;
@property (nonatomic, weak) id<RTCAudioDeviceDelegate> deviceDelegate;
@property (nonatomic, assign) BOOL deviceIsInitialized;
@property (nonatomic, assign) BOOL deviceIsRecording;
@property (nonatomic, assign) BOOL deviceIsPlaying;
@property (nonatomic, assign) BOOL deviceIsPlayoutInitialized;
@property (nonatomic, assign) BOOL deviceIsRecordingInitialized;
@end

@implementation MCNInjectionAudioDevice

// --- Input properties ---
- (double)deviceInputSampleRate { return kSampleRate; }
- (NSTimeInterval)inputIOBufferDuration { return kTimerInterval; }
- (NSInteger)inputNumberOfChannels { return 1; }
- (NSTimeInterval)inputLatency { return 0.001; }

// --- Output properties ---
- (double)deviceOutputSampleRate { return kSampleRate; }
- (NSTimeInterval)outputIOBufferDuration { return kTimerInterval; }
- (NSInteger)outputNumberOfChannels { return 1; }
- (NSTimeInterval)outputLatency { return 0.001; }

// --- State ---
- (BOOL)isInitialized { return self.deviceIsInitialized; }
- (BOOL)isPlayoutInitialized { return self.deviceIsPlayoutInitialized; }
- (BOOL)isPlaying { return self.deviceIsPlaying; }
- (BOOL)isRecordingInitialized { return self.deviceIsRecordingInitialized; }
- (BOOL)isRecording { return self.deviceIsRecording; }

// --- Lifecycle ---

- (BOOL)initializeWithDelegate:(id<RTCAudioDeviceDelegate>)delegate {
    self.deviceDelegate = delegate;
    self.deviceIsInitialized = YES;
    NSLog(@"[MCNAudioDevice] initializeWithDelegate");
    return YES;
}

- (BOOL)terminateDevice {
    self.deviceDelegate = nil;
    self.deviceIsInitialized = NO;
    NSLog(@"[MCNAudioDevice] terminateDevice");
    return YES;
}

// --- Playout (no-op: solo necesitamos inyeccion de grabacion) ---

- (BOOL)initializePlayout {
    self.deviceIsPlayoutInitialized = YES;
    return YES;
}

- (BOOL)startPlayout {
    self.deviceIsPlaying = YES;
    return YES;
}

- (BOOL)stopPlayout {
    self.deviceIsPlaying = NO;
    return YES;
}

// --- Recording ---

- (BOOL)initializeRecording {
    self.deviceIsRecordingInitialized = YES;
    return YES;
}

- (BOOL)startRecording {
    self.deviceIsRecording = YES;
    NSLog(@"[MCNAudioDevice] startRecording");
    return YES;
}

- (BOOL)stopRecording {
    self.deviceIsRecording = NO;
    NSLog(@"[MCNAudioDevice] stopRecording");
    return YES;
}

@end

#pragma mark - MCNAudioInjectionBridge

@interface MCNAudioInjectionBridge ()
@property (nonatomic, strong) MCNInjectionAudioDevice *audioDevice;
@property (nonatomic, strong) RTCPeerConnectionFactory *injectionFactory;
@property (nonatomic, strong) RTCAudioTrack *injectionTrack;
@property (nonatomic, strong) RTCMediaStreamTrack *originalTrack;
@property (nonatomic, strong) NSMutableArray<NSData *> *frameQueue;
@property (nonatomic, strong) NSLock *queueLock;
@property (nonatomic, strong) NSTimer *deliveryTimer;
@property (nonatomic, assign) double sampleTime;
@property (nonatomic, assign) BOOL isActive;
@property (nonatomic, assign) BOOL isDelivering;
@end

@implementation MCNAudioInjectionBridge

- (instancetype)init {
    self = [super init];
    if (self) {
        _frameQueue = [NSMutableArray new];
        _queueLock = [NSLock new];
        _isActive = NO;
        _isDelivering = NO;
        _sampleTime = 0;
    }
    return self;
}

#pragma mark - Activation

- (BOOL)activateWithSender:(id)sender {
    @try {
        id<RTCRtpSender> rtpSender = (id<RTCRtpSender>)sender;

        // Guardar track original para restaurar despues
        self.originalTrack = rtpSender.track;

        // Crear dispositivo de audio personalizado
        MCNInjectionAudioDevice *device = [[MCNInjectionAudioDevice alloc] init];
        device.bridge = self;
        self.audioDevice = device;

        // Crear factory con nuestro dispositivo (NO captura del mic)
        self.injectionFactory = [[RTCPeerConnectionFactory alloc]
            initWithEncoderFactory:nil
                    decoderFactory:nil
                       audioDevice:device];

        // Crear audio source y track
        RTCAudioSource *source = [self.injectionFactory audioSourceWithConstraints:nil];
        self.injectionTrack = [self.injectionFactory audioTrackWithSource:source
                                                                 trackId:@"translated_audio_0"];
        self.injectionTrack.isEnabled = YES;

        // Reemplazar track del mic con el de inyeccion en el sender RTP
        rtpSender.track = self.injectionTrack;

        self.isActive = YES;
        NSLog(@"[MCNAudioBridge] Injection activated - original track saved, injection track set");
        return YES;
    } @catch (NSException *exception) {
        NSLog(@"[MCNAudioBridge] Error activating: %@", exception);
        [self dispose];
        return NO;
    }
}

- (void)deactivateWithSender:(id)sender {
    @try {
        [self stop];

        id<RTCRtpSender> rtpSender = (id<RTCRtpSender>)sender;

        // Restaurar track original del mic
        if (self.originalTrack) {
            rtpSender.track = self.originalTrack;
            NSLog(@"[MCNAudioBridge] Original track restored");
        }

        self.isActive = NO;
        self.injectionTrack = nil;
        self.originalTrack = nil;
        self.injectionFactory = nil;
        self.audioDevice = nil;

        NSLog(@"[MCNAudioBridge] Injection deactivated");
    } @catch (NSException *exception) {
        NSLog(@"[MCNAudioBridge] Error deactivating: %@", exception);
    }
}

#pragma mark - Audio Data

- (void)pushAudioData:(NSData *)data sampleRate:(int)sampleRate channels:(int)channels {
    if (!self.isActive) return;
    if (data.length == 0) return;
    if (sampleRate != 48000 || channels != 1) {
        // Solo aceptamos 48kHz mono (el resampling se hace en Kotlin)
        NSLog(@"[MCNAudioBridge] Ignoring data: expected 48kHz mono, got %dHz %dch", sampleRate, channels);
        return;
    }

    // Dividir en frames de 10ms y encolar
    NSUInteger offset = 0;
    while (offset < data.length) {
        NSUInteger chunkSize = MIN(kBufferBytes, data.length - offset);
        NSData *chunk = [data subdataWithRange:NSMakeRange(offset, chunkSize)];

        [self.queueLock lock];
        // Limitar cola (~500ms max = 50 frames de 10ms)
        if (self.frameQueue.count > 50) {
            [self.frameQueue removeObjectAtIndex:0];
        }
        [self.frameQueue addObject:chunk];
        [self.queueLock unlock];

        offset += chunkSize;
    }
}

#pragma mark - Delivery Control

- (void)start {
    if (self.isDelivering) return;
    self.isDelivering = YES;
    self.sampleTime = 0;

    __weak typeof(self) weakSelf = self;
    self.deliveryTimer = [NSTimer scheduledTimerWithTimeInterval:kTimerInterval
                                                         repeats:YES
                                                           block:^(NSTimer *timer) {
        [weakSelf deliverNextFrame];
    }];

    NSLog(@"[MCNAudioBridge] Delivery timer started (%.0fms interval)", kTimerInterval * 1000);
}

- (void)stop {
    if (!self.isDelivering) return;
    self.isDelivering = NO;

    [self.deliveryTimer invalidate];
    self.deliveryTimer = nil;

    [self.queueLock lock];
    [self.frameQueue removeAllObjects];
    [self.queueLock unlock];

    NSLog(@"[MCNAudioBridge] Delivery timer stopped");
}

- (void)dispose {
    [self stop];
    self.isActive = NO;
    self.injectionTrack = nil;
    self.originalTrack = nil;
    self.audioDevice.deviceDelegate = nil;
    self.audioDevice = nil;
    self.injectionFactory = nil;
    NSLog(@"[MCNAudioBridge] Disposed");
}

#pragma mark - Audio Frame Delivery

/**
 * Entrega el siguiente frame de audio al ADM nativo de WebRTC.
 * Llamado cada 10ms por el timer.
 */
- (void)deliverNextFrame {
    id<RTCAudioDeviceDelegate> delegate = self.audioDevice.deviceDelegate;
    if (!delegate || !self.audioDevice.deviceIsRecording) return;

    // Obtener bloque de entrega del delegate ADM
    RTCAudioDeviceDeliverRecordedDataBlock deliverBlock = delegate.deliverRecordedData;
    if (!deliverBlock) return;

    // Obtener siguiente frame de la cola (o silencio)
    NSData *frame = nil;
    [self.queueLock lock];
    if (self.frameQueue.count > 0) {
        frame = self.frameQueue[0];
        [self.frameQueue removeObjectAtIndex:0];
    }
    [self.queueLock unlock];

    // Preparar buffer de audio (10ms PCM16 mono = 960 bytes)
    uint8_t buffer[kBufferBytes];
    memset(buffer, 0, kBufferBytes); // Silencio por defecto

    if (frame && frame.length > 0) {
        NSUInteger copyLen = MIN(frame.length, (NSUInteger)kBufferBytes);
        memcpy(buffer, frame.bytes, copyLen);
    }

    // Construir AudioBufferList con nuestros datos PCM
    AudioBufferList bufferList;
    bufferList.mNumberBuffers = 1;
    bufferList.mBuffers[0].mNumberChannels = 1;
    bufferList.mBuffers[0].mDataByteSize = kBufferBytes;
    bufferList.mBuffers[0].mData = buffer;

    // Construir AudioTimeStamp
    AudioTimeStamp timestamp = {0};
    timestamp.mSampleTime = self.sampleTime;
    timestamp.mFlags = kAudioTimeStampSampleTimeValid;
    self.sampleTime += kFramesPerBuffer;

    AudioUnitRenderActionFlags actionFlags = 0;

    // Entregar audio al ADM nativo de WebRTC.
    // inputData contiene nuestros datos pre-llenados, renderBlock es nil.
    deliverBlock(&actionFlags, &timestamp, 0, kFramesPerBuffer, &bufferList, NULL, NULL);
}

@end
