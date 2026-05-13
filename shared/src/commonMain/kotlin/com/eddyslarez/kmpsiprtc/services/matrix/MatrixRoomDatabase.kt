package com.eddyslarez.kmpsiprtc.services.matrix

import androidx.room.RoomDatabase
import net.folivo.trixnity.client.store.repository.room.TrixnityRoomDatabase

/**
 * Construye un `RoomDatabase.Builder<TrixnityRoomDatabase>` con el nombre/ruta
 * adecuados a cada plataforma. Trixnity 4.22.7 expone `TrixnityRoomDatabase`
 * con `@ConstructedBy(TrixnityRoomDatabaseConstructor::class)`, y Room KMP
 * genera los `actual object` por target dentro del propio jar de Trixnity
 * (`trixnity-client-repository-room-{androidx,iosArm64,iosSimulatorArm64,iosX64,desktop}`)
 * → solo necesitamos pasarle el nombre/ruta de la DB.
 *
 * Patrón derivado del Database.android/ios/jvm.kt existente del proyecto.
 */
internal expect fun matrixRoomDatabaseBuilder(basePath: String): RoomDatabase.Builder<TrixnityRoomDatabase>
