package com.eddyslarez.kmpsiprtc.services.conference

import android.content.Intent

/**
 * Bridge global para que el host (composeApp) entregue el `Intent` que devuelve
 * el system dialog de MediaProjection al SDK de LiveKit, sin acoplar la SIP lib
 * al ciclo de vida de la Activity.
 *
 * Flujo:
 *  1. Usuario aprieta "Compartir pantalla" en la UI.
 *  2. composeApp (MainActivity) lanza `MediaProjectionManager.createScreenCaptureIntent()`.
 *  3. Al recibir RESULT_OK, llama [setPendingMediaProjectionData] con el `result.data`.
 *  4. ConferenceLiveKitManager.android lee este Intent y lo pasa al SDK.
 *
 * El Intent solo vive hasta que se detiene el screen share — entonces se limpia.
 */
object LkScreenCaptureBridge {
    @Volatile
    var pendingMediaProjectionData: Intent? = null
        private set

    fun setPendingMediaProjectionData(intent: Intent?) {
        pendingMediaProjectionData = intent
    }
}
