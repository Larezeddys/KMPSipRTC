# KMPSipRTC escritorio: endurecimiento (Fase 3)

**Fecha:** 2026-11-06 · **Ámbito:** target `desktop` (JVM) de `shared`; los cambios en `commonMain`
mantienen el comportamiento de Android/iOS salvo donde se indica.

## 1. Empaquetado y webrtc-java

- webrtc-java pasa de 0.14.0 a **0.19.0**, la misma versión que usa la app (Gradle resolvía a 0.19.0
  igualmente; ahora se compila contra la API que se ejecuta).
- La librería declara solo `api("dev.onvoid.webrtc:webrtc-java:0.19.0")`, sin classifier nativo. Antes
  elegía el nativo según el SO del equipo que compilaba y no tenía arm64 para Windows/Linux.
- Se quita `compose.desktop.currentOs`: el único uso era `WindowsManager.desktop.kt`, que ahora usa AWT.
  La app sigue aportando el runtime de Compose de su plataforma.
- Cambio de 0.19 con efecto: una `PeerConnectionFactory` no admite a la vez micrófono del ADM y
  `CustomAudioSource`. La inyección de audio de traducción usa ahora una factoría propia (sección 12).

### Nativos en la app

La app (`composeApp/build.gradle.kts`, `jvmMain`) declara el classifier del SO/arquitectura destino.
Classifiers publicados para 0.19.0: `windows-x86_64`, `windows-aarch64`, `macos-x86_64`,
`macos-aarch64`, `linux-x86_64`, `linux-aarch64` (y `linux-aarch32`).

```kotlin
// Sustituye los runtimeOnly("dev.onvoid.webrtc:webrtc-java:...:<classifier>") de cada rama del when.
val webrtcJavaVersion = libs.versions.webrtc.java.get()
val webrtcClassifiers = listOf(
    "windows-x86_64", "windows-aarch64",
    "macos-x86_64", "macos-aarch64",
    "linux-x86_64", "linux-aarch64",
)
// -PwebrtcNativeTarget=windows-aarch64 para empaquetar otra arquitectura; "all" incluye todos.
val webrtcNativeTarget = (project.findProperty("webrtcNativeTarget") as String?) ?: run {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> System.getProperty("os.arch").lowercase()
    }
    when {
        os.contains("win") -> "windows-$arch"
        os.contains("mac") -> "macos-$arch"
        else -> "linux-$arch"
    }
}
val selectedWebrtcClassifiers =
    if (webrtcNativeTarget == "all") webrtcClassifiers else listOf(webrtcNativeTarget)
selectedWebrtcClassifiers.forEach { classifier ->
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:$webrtcJavaVersion:$classifier")
}
```

El cargador nativo de webrtc-java elige en tiempo de ejecución el nativo de la máquina, así que
incluir varios es seguro (solo aumenta el tamaño del instalador).

### Fallo de carga nativa

`DesktopWebRtcManager.initialize()` ya no imprime la traza ni se marca como inicializado si falla la
factoría: captura `UnsatisfiedLinkError`, `ExceptionInInitializerError`, `NoClassDefFoundError` y el
resto, y publica un `SipEngineInitError`:

- `NativeLibraryUnavailable(platform, message, cause)`: falta el nativo para `os.name/os.arch`.
- `AudioDeviceModuleFailed(message, cause)`.
- `FactoryCreationFailed(message, cause)`.

Se consulta con `KmpSipRtc.getEngineInitError()`, `KmpSipRtc.observeEngineInitError()`,
`WebRtcManager.getEngineInitError()` y llega como `SipEvent.EngineInitFailed(error)` por
`observeEvents()`.

## 2. Audio

- **Modelo único** sobre WebRTC. En llamada, la lista sale del ADM de la factoría
  (`getPlayoutDevices`/`getRecordingDevices`), que es el mismo módulo que reproduce y captura; sin
  ADM se usa `MediaDevices`. El id estable es el descriptor del dispositivo
  (`AudioDevice.descriptor`; GUID del endpoint en Windows, UID de CoreAudio en macOS, nombre de
  PulseAudio en Linux).
- **Selección por id** con `selectAudioInputDeviceById` / `selectAudioOutputDeviceById` (nuevos en
  `WebRtcManager`, `SipCoreManager` y `KmpSipRtc`). Los métodos `...ByName` siguen funcionando y
  aceptan también ids: se resuelve por id, nombre exacto, nombre sin mayúsculas y nombre equivalente
  (sin prefijos `Default - `/`Communication - `/`Port `, sin sufijos ALSA, y JavaSound recortado a 31
  caracteres). Así las selecciones guardadas antes por nombre se siguen aplicando.
- `changeInputDevice`/`changeOutputDevice` devuelven `false` si el dispositivo no existe o el ADM lo
  rechaza. En llamada, el cambio detiene, cambia y reinicia la reproducción o la captura (el ADM no
  admite cambiar con el audio inicializado).
- **Conexión en caliente:** `DesktopAudioDevices` escucha `MediaDevices.addDeviceChangeListener` y,
  como red de seguridad, consulta cada 10 s (cada 2 s si el aviso no está disponible). Si el
  dispositivo activo desaparece se pasa al del sistema; si el elegido vuelve, se reaplica.
- La librería no persiste nada: la selección vive en memoria del gestor (sobrevive a
  `dispose()`/`initialize()`) y la app guarda el id.
- `AudioOptions` explícito: `echoCancellation`, `noiseSuppression`, `autoGainControl` y
  `highpassFilter` a `true`.
- **Tono de llamada y aviso de llamada en espera** suenan en la salida elegida. Emparejamiento con
  JavaSound: entre los mezcladores con líneas de salida se elige el de mayor parecido de nombre con el
  dispositivo de WebRTC (mínimo: la mitad de las palabras en común); si no hay pareja, la salida del
  sistema. En Windows y macOS los nombres suelen coincidir; en Linux JavaSound ve ALSA y WebRTC
  PulseAudio, así que normalmente suena en la salida por defecto.

## 3. Ciclo de vida

- El BYE (remoto y limpieza de llamada) ya no destruye factoría y ADM: llama a
  `WebRtcManager.releaseCallResources()`. En escritorio solo cierra la conexión; en Android/iOS la
  implementación por defecto sigue llamando a `dispose()`.
- Una factoría y un ADM por sesión del gestor. `closePeerConnection()` libera senders, pista y fuente
  de audio local, inyección, vídeo y pantalla. `dispose()` libera además factoría y ADM, sin el
  `Thread.sleep(200)` anterior, y el gestor se puede volver a inicializar (el scope ya no se cancela).
- `SipAudioManager.dispose()` (se llama al terminar cada llamada) cancela sus tareas pero no su scope:
  antes, tras la primera llamada dejaban de aplicarse los cambios de dispositivo y el guardado de tonos.
  Afecta a todas las plataformas.
- Al conectar la llamada se habilita el micro respetando el mute (antes se reactivaba siempre).

## 4. Seguridad

- **Logs:** `SipLogConfig.minLevel` (por defecto `WARN`) y `SipLogConfig.logFullSipMessages` (por
  defecto `false`). El logger de escritorio filtra por nivel y pasa todo por `LogRedactor`: oculta
  `Authorization`/`Proxy-Authorization`, `response=` de Digest, `Bearer`, y valores de `password`,
  `token`, `pushToken`, `access_token`, `pn-prid`, `pn-param`, `credential`… Sin
  `logFullSipMessages`, un mensaje SIP completo se reduce a `[SIP <método|código> cseq=… call#<hash>]`.
  Ya no se registran valores de cabeceras del WebSocket ni la carga de los push. Los loggers de
  Android/iOS no cambian.
- **Contraseña SIP en reposo (escritorio):** AES-256-GCM (`enc:v1:` + base64 de IV y texto cifrado)
  con una clave aleatoria por instalación en `<datos>/keys/sip-secrets.key`, solo legible por el
  usuario (0600 en POSIX; ACL del propietario en Windows). No se usa el llavero del SO: protege copias
  o respaldos de la base de datos sin la carpeta `keys`, no frente a un proceso del mismo usuario.
  Las filas en claro se cifran al arrancar la base y al leer una cuenta. Si la clave se pierde, la
  contraseña se lee vacía y hay que volver a iniciar sesión. En Android/iOS no cambia nada.
- `DatabaseDiagnosticTool` usa marcadores evidentes (`placeholder-not-a-secret`) en lugar de
  credenciales de prueba.

## 5. Hilos y red

- `KmpSipRtcThreading.dispatcherOverride` permite fijar el dispatcher del trabajo interno de
  `KmpSipRtc` y de la entrega de sus callbacks. Por defecto: `Dispatchers.Main` en Android/iOS (como
  antes) y `Dispatchers.Default` en escritorio (antes el EDT de Swing). Fijarlo antes del primer
  `getInstance()`.
- `DesktopWebSocket`: `isOpen`, `isConnecting` y `lastPingSentTime` son `@Volatile`.
- En escritorio, `RegistrationGuardianManager` y `SipReconnectionManager` comparten el
  `NetworkManager` del núcleo (antes había tres bucles, y el de reconexión ni siquiera estaba
  inicializado, así que su escucha de red nunca disparaba). Ahora la caída/vuelta de red y la
  detección de suspensión del SO llegan al gestor de reconexión. En Android/iOS se mantiene una
  instancia por gestor.

## 6. Carpetas de datos

`DesktopDataDirs` resuelve la carpeta estándar: Windows `%APPDATA%\<App>`, macOS
`~/Library/Application Support/<App>`, Linux `$XDG_DATA_HOME/<app>` o `~/.local/share/<app>` (en
minúsculas y con guiones). `<App>` es `DatabaseConfig.desktopAppDataName` o, si es `null`,
`KMPSipRTC` + `DatabaseConfig.brandSuffix` (p. ej. `KMPSipRTC-mcn`).

| dato | antes | ahora |
|---|---|---|
| base de datos (+ `-wal`, `-shm`) | `~/.kmpsiprtc<sufijo>/sip_database.db` | `<datos>/sip_database.db` |
| Matrix | `~/.mcn-softphone/matrix` | `<datos>/matrix` |
| grabaciones | `~/CallRecordings` | `<datos>/recordings` |
| clave de cifrado | — | `<datos>/keys/sip-secrets.key` |

Las rutas antiguas se mueven una sola vez al primer uso. Si el destino ya existe, gana el destino y
lo antiguo no se toca; si el movimiento falla, se deshace y se sigue usando la ruta antigua. Matrix y
grabaciones no estaban separados por marca: los recoge la primera marca que arranque.

## 7. Grabación

webrtc-java no ofrece un punto de escucha del micrófono sin sustituir el transporte de audio de la
llamada (`AudioDeviceModule.setAudioSink` reemplaza el callback del ADM). La grabación local sigue
con JavaSound, pero ahora en el mezclador equivalente a la entrada elegida (mismo emparejamiento que
el tono) y escribe silencio mientras el micro está silenciado. El audio remoto sigue llegando por
`AudioTrackSink` de la pista remota.

## 8. API pública

Añadido:

- `WebRtcManager.releaseCallResources()`, `getEngineInitError()`, `selectAudioInputDeviceById()`,
  `selectAudioOutputDeviceById()` (todos con implementación por defecto).
- `KmpSipRtc.selectAudioInputDeviceById()`, `selectAudioOutputDeviceById()`, `getEngineInitError()`,
  `observeEngineInitError()`; `SipCoreManager.selectAudio{Input,Output}DeviceById()`.
- `SipEngineInitError` y `SipEvent.EngineInitFailed`.
- `SipLogConfig`, `SipLogLevel`, `LogRedactor`, `KmpSipRtcThreading`.
- `DatabaseConfig.desktopAppDataName`.
- `MatrixRoom.highlightCount` (por defecto 0; sección 11).
- Escritorio: `DesktopAudioDevices` (`devices`, `refresh()`, `startMonitoring()`/`stopMonitoring()`,
  `openCaptureLine()`, `openRenderLine()`), `DesktopAudioDeviceInfo`, `DesktopAudioDeviceList`,
  `DesktopDataDirs`, `DesktopWebRtcManager.audioDevicesFlow()`,
  `DesktopAudioController.selectInputByQuery()/selectOutputByQuery()/restorePreferences()`,
  `DesktopCallRecorder.setLocalMuted()`.
- `RegistrationGuardianManager` y `SipReconnectionManager` aceptan un `sharedNetworkManager` opcional.

Cambiado sin romper firmas:

- `DesktopWindowManager` queda mínimo (solo `bringToFront`); notificaciones y diálogo de llamada los
  gestiona la app.
- `DesktopAudioController.setActiveRoute()` aplica el dispositivo en el ADM y `startForCall()` ya no
  impone Bluetooth/auriculares sobre la elección del usuario (eso queda en
  `refreshWithBluetoothPriority()` cuando no hay elección).

## 9. Migración para apps

1. Declarar el nativo de webrtc-java por SO/arquitectura (fragmento de la sección 1).
2. Guardar como id de dispositivo `DesktopAudioDeviceInfo.id` (o `AudioDevice.descriptor`) y
   aplicarlo con `selectAudio{Input,Output}DeviceById`. Las selecciones antiguas por nombre siguen
   resolviéndose.
3. Subir `SipLogConfig.minLevel` a `DEBUG` (y `logFullSipMessages`) solo en depuración o cuando el
   usuario active la grabación de logs SIP.
4. Si la app actualizaba la UI desde callbacks de `KmpSipRtc` asumiendo el hilo de Swing, cambiar a
   `withContext(Dispatchers.Main)` o fijar `KmpSipRtcThreading.dispatcherOverride = Dispatchers.Main`.
5. Mostrar al usuario `SipEngineInitError.NativeLibraryUnavailable` en lugar de fallar en silencio.
6. Opcional: `DatabaseConfig.desktopAppDataName` antes de abrir la base para elegir el nombre de la
   carpeta de datos (cambiarlo después deja los datos en la carpeta anterior).

## 10. Pruebas

`shared/src/desktopTest`: emparejamiento de nombres de dispositivos, redacción de logs y filtro de
nivel, cifrado y clave de contraseñas, resolución y migración de carpetas de datos, conversión del PCM
inyectado (`PcmInjectionPipelineTest`) (`./gradlew :shared:desktopTest`). `shared/src/commonTest`:
`MatrixHighlightCountsTest` (`./gradlew :shared:allTests` o el test de la plataforma).

## 11. Menciones: `highlight_count` del servidor

- Trixnity 4.22.7 solo guarda `notification_count` (`Room.unreadMessageCount`, que ya era
  `MatrixRoom.unreadCount`); `highlight_count` no llega a su `Room`. `MatrixManager` se suscribe a
  `client.api.sync` y toma `rooms.join[*].unread_notifications.highlight_count` de cada respuesta;
  las salas abandonadas se olvidan.
- Una sincronización incremental solo trae las salas que cambiaron, así que el mapa se guarda en
  `<matrix>/highlight_counts.json` y se recarga al abrir el cliente. Se borra con el resto del almacén
  al cerrar sesión. Mientras una sala no haya pasado por ninguna sincronización, su valor es 0.
- `MatrixRoom.highlightCount: Int = 0` (campo nuevo al final, sin romper llamadas existentes).
- La app aplica la regla de la consola web (`map-room.ts`):
  `highlight > 0 || (total > 0 && mención personal sin leer en lo cargado)`. La segunda parte cubre
  también las salas cuyo contador aún no se conoce.

## 12. Inyección de audio de traducción en escritorio

- Por qué una segunda factoría: la factoría de la llamada queda fijada a micrófono del ADM al crear la
  pista del micro (`createAudioSource`) y rechaza `createAudioTrack(…, CustomAudioSource)`. Cambiar de
  factoría a mitad de llamada exigiría otra `RTCPeerConnection` y renegociar (re-INVITE).
- Cómo: al pedir `setLocalAudioEnabled(false)` se crea (una vez por gestor) una factoría con
  `HeadlessAudioDeviceModule`, que no abre dispositivos; de ella salen un `CustomAudioSource` y su
  `AudioTrack`. Se detiene la captura del ADM principal (`stopRecording`) y se cambia la pista del
  sender de audio con `RTCRtpSender.replaceTrack` (nativo, sin la comprobación de modo de la factoría).
  Con `setLocalAudioEnabled(true)` vuelve la pista del micro y se reabre la captura
  (`initRecording` + `startRecording`). Parar el ADM es obligatorio: WebRTC entrega lo capturado a todos
  los senders de su factoría y dos productores en el mismo sender abortan el proceso.
- Formato: `injectLocalAudio` acepta PCM entero little-endian intercalado de 8, 16, 24 o 32 bits, a
  cualquier frecuencia y número de canales (la app envía 24 kHz mono 16 bits). Se convierte a mono,
  se remuestrea a 48 kHz con interpolación lineal continua entre llamadas y se trocea en bloques
  exactos de 10 ms (WebRTC descarta bloques de otro tamaño), en el orden de bytes de la plataforma que
  pide `pushAudio`.
- Un pacer empuja un bloque cada 10 ms según el reloj real; sin audio pendiente envía silencio para que
  el RTP no se corte (algunas centralitas cuelgan por inactividad RTP). Con el usuario en silencio
  (`setMuted(true)`) empuja silencio; la pista inyectada no se habilita ni deshabilita después del
  `replaceTrack`, porque pertenece a otra factoría y sus avisos llegarían en otro hilo.
- Si algo falla al activar, se vuelve a poner la pista del micro, se reabre la captura y el micro
  queda deshabilitado (como pedía la llamada); `setLocalAudioEnabled(true)` lo recupera.
- Cambiar de micrófono durante la inyección cambia el dispositivo sin reabrir la captura.
- WebRTC puede reabrir la captura del ADM al reiniciar el envío (re-INVITE de retener/reanudar o el
  propio `replaceTrack`). Mientras se inyecta, la captura se vuelve a cerrar tras `replaceTrack` y al
  terminar cada negociación SDP (oferta, respuesta, descripción remota y local).
- No verificado en ejecución: que WebRTC acepte en `replaceTrack` una pista de otra factoría.
- App: `RealtimeTranslationManager` y `RealtimeTranscriptionManager` remuestrean a 24 kHz también con
  razones no enteras (antes 44,1/32 kHz pasaban sin remuestrear) y mezclan a mono si llegan varios
  canales.
