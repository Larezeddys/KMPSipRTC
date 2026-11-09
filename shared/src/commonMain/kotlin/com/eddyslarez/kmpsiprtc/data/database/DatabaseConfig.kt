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

    /**
     * Solo escritorio: nombre de la carpeta de datos dentro del directorio estándar del SO
     * (`%APPDATA%`, `~/Library/Application Support`, `$XDG_DATA_HOME`). Con `null` se usa
     * `KMPSipRTC` + [brandSuffix].
     */
    var desktopAppDataName: String? = null
}
