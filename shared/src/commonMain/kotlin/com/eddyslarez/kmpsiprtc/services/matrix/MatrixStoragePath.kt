package com.eddyslarez.kmpsiprtc.services.matrix

/**
 * Devuelve la ruta absoluta en disco donde Matrix puede persistir su
 * estado: cache de media descargada (Okio FileSystem) y, en el futuro,
 * la base de datos de Trixnity Room (si se activa la persistencia de
 * repositorios).
 *
 * Por plataforma:
 *  - Android: `context.filesDir.absolutePath + "/matrix"`
 *  - iOS: `NSDocumentDirectory + "/matrix"`
 *  - JVM Desktop: `System.getProperty("user.home") + "/.mcn-softphone/matrix"`
 *
 * El directorio se crea si no existe. La función es idempotente y rápida —
 * pensada para llamarse en cada `MatrixManager.login()` sin caché manual.
 */
expect fun matrixStoragePath(): String
