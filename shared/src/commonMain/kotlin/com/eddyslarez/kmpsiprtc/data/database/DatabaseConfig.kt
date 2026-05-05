package com.eddyslarez.kmpsiprtc.data.database

/**
 * Configuración global de la base de datos.
 * Debe configurarse ANTES de acceder a DatabaseManager.
 */
object DatabaseConfig {
    /**
     * Sufijo de marca para separar bases de datos por brand.
     * Ejemplo: "-mcn", "-kompaas"
     */
    var brandSuffix: String = ""
}
