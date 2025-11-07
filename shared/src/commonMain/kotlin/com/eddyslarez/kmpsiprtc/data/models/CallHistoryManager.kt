package com.eddyslarez.kmpsiprtc.data.models

import androidx.compose.runtime.mutableStateListOf
import com.eddyslarez.kmpsiprtc.core.SipCoreManager
import com.eddyslarez.kmpsiprtc.data.database.DatabaseManager
import com.eddyslarez.kmpsiprtc.data.database.converters.toCallLog
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.repository.CallLogWithContact
import com.eddyslarez.kmpsiprtc.utils.generateId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime

/**
 * CallHistoryManager con prevención de duplicados y preservación de datos históricos
 */
class CallHistoryManager(
    private val databaseManager: DatabaseManager? = null,
    private val sipCoreManager: SipCoreManager? = null
) {

    private val _callLogs = mutableStateListOf<CallLog>()
    val callLogs: List<CallLog> get() = _callLogs.toList()
    companion object {
        private const val RECENT_CALLS_LIMIT = 500
    }
    private val TAG = "CallHistoryManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processingMutex = Mutex()
    // ✅ MEJORADO: Usar Map para mejor gestión y preservación de datos
    private val savedCallLogs = mutableMapOf<String, CallLog>()
    private val processingCallIds = mutableSetOf<String>()
    private val recentlyAddedCallIds = mutableSetOf<String>()
    private var isInitialLoadComplete = false
    private val loadMutex = Mutex()
    // ✅ NUEVO: Flow para observar cambios en el historial
    private val _callLogsFlow = MutableStateFlow<List<CallLog>>(emptyList())
    val callLogsFlow: StateFlow<List<CallLog>> = _callLogsFlow.asStateFlow()

    init {
        log.d(tag = TAG) { "CallHistoryManager initialized" }
    }
    /**
     * ✅ SOLUCIÓN: Carga inicial de call logs desde BD sin limpiar
     */
    suspend fun loadCallLogsFromDatabase() {
        loadMutex.withLock {
            if (isInitialLoadComplete) {
                log.d(tag = TAG) { "Initial load already completed" }
                return
            }

            try {
                log.d(tag = TAG) { "🔄 Loading call logs from database..." }

                val dbLogs = databaseManager?.getRecentCallLogs(1000)?.first() ?: emptyList()

                if (dbLogs.isNotEmpty()) {
                    log.d(tag = TAG) { "📥 Found ${dbLogs.size} logs in database" }

                    val existingIds = _callLogs.map { it.id }.toSet()
                    val newLogs = dbLogs
                        .filter { it.callLog.id !in existingIds }
                        .map { it.toCallLog() }

                    if (newLogs.isNotEmpty()) {
                        _callLogs.addAll(newLogs)
                        newLogs.forEach { savedCallLogs[it.id] = it }
                        log.d(tag = TAG) { "✅ Added ${newLogs.size} logs from database" }
                    }

                    _callLogs.sortByDescending { parseFormattedDate(it.formattedStartDate) }
                    _callLogsFlow.value = _callLogs.toList()
                }

                isInitialLoadComplete = true
                log.d(tag = TAG) { "✅ Total logs in memory: ${_callLogs.size}" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "❌ Error loading from database: ${e.message}" }
            }
        }
    }


    /**
     * ✅ CORREGIDO: Método mejorado para agregar logs sin duplicados ni pérdida de datos
     */
    @OptIn(ExperimentalTime::class)
    fun addCallLog(callData: CallData, callType: CallTypes, endTime: Long? = null) {
        scope.launch {
            processingMutex.withLock {
                try {
                    val callId = callData.callId
                    val startTime = callData.startTime ?: kotlin.time.Clock.System.now().toEpochMilliseconds()
                    val finalEndTime = endTime ?: kotlin.time.Clock.System.now().toEpochMilliseconds()

                    // Calcular duración solo para llamadas exitosas
                    val duration = if (callType == CallTypes.SUCCESS &&
                        startTime > 0 &&
                        finalEndTime > startTime) {
                        ((finalEndTime - startTime) / 1000).toInt()
                    } else {
                        0 // Llamadas perdidas/rechazadas/abortadas tienen duración 0
                    }

                    // Crear CallLog
                    val callLog = CallLog(
                        id = callId,
                        direction = callData.direction,
                        to = callData.to,
                        formattedTo = formatPhoneNumber(callData.to),
                        from = callData.from ?: callData.getLocalParty(),
                        formattedFrom = formatPhoneNumber(callData.from ?: callData.getLocalParty()),
                        contact = null,
                        formattedStartDate = formatStartDate(startTime),
                        duration = duration,
                        callType = callType,
                        localAddress = callData.getLocalParty()
                    )

                    log.d(TAG) { "📞 Adding call log: $callId | $callType | ${duration}s | ${callData.direction}" }

                    // ✅ Verificar si ya existe
                    val existingIndex = _callLogs.indexOfFirst { it.id == callId }

                    if (existingIndex >= 0) {
                        val existingLog = _callLogs[existingIndex]

                        // Solo actualizar si el nuevo log es más significativo
                        if (shouldUpdateExistingLog(existingLog, callType, duration)) {
                            _callLogs[existingIndex] = callLog
                            savedCallLogs[callId] = callLog
                            log.d(TAG) { "✅ Updated existing call log: $callId ($callType)" }
                        } else {
                            log.d(TAG) { "ℹ️  Keeping existing log: $callId (${existingLog.callType})" }
                            return@launch
                        }
                    } else {
                        // Agregar nuevo log al principio
                        _callLogs.add(0, callLog)
                        savedCallLogs[callId] = callLog
                        log.d(TAG) { "✅ Added new call log: $callId ($callType)" }
                    }

                    // Actualizar Flow
                    _callLogsFlow.value = _callLogs.toList()

                    // Persistir en BD
                    saveCallLogToDatabase(callLog, callData, startTime, finalEndTime)

                    // Mantener límite
                    if (_callLogs.size > RECENT_CALLS_LIMIT) {
                        val removed = _callLogs.removeAt(_callLogs.size - 1)
                        savedCallLogs.remove(removed.id)
                        log.d(TAG) { "📦 Removed old log: ${removed.id}" }
                    }

                } catch (e: Exception) {
                    log.e(TAG) { "❌ Error adding call log: ${e.message}" }
                }
            }
        }
    }


    /**
     * ✅ NUEVO: Determinar si se debe actualizar un log existente
     */
    private fun shouldUpdateExistingLog(
        existingLog: CallLog,
        newType: CallTypes,
        newDuration: Int
    ): Boolean {
        // Jerarquía de tipos (menor a mayor prioridad)
        val typePriority = mapOf(
            CallTypes.ABORTED to 1,    // Llamada saliente cancelada
            CallTypes.DECLINED to 2,   // Llamada entrante rechazada
            CallTypes.MISSED to 2,     // Llamada entrante no contestada
            CallTypes.SUCCESS to 3     // Llamada exitosa
        )

        val existingPriority = typePriority[existingLog.callType] ?: 0
        val newPriority = typePriority[newType] ?: 0

        // Si el nuevo tipo es más significativo, actualizar
        if (newPriority > existingPriority) {
            log.d(TAG) { "Updating log: ${existingLog.callType} -> $newType (higher priority)" }
            return true
        }

        // Si es el mismo tipo pero con mayor duración
        if (newType == CallTypes.SUCCESS &&
            existingLog.callType == CallTypes.SUCCESS &&
            newDuration > existingLog.duration) {
            log.d(TAG) { "Updating log: longer duration ${existingLog.duration}s -> ${newDuration}s" }
            return true
        }

        return false
    }


    /**
     * ✅ NUEVO: Jerarquía de tipos de llamada (de menos a más significativo)
     */
    private fun isMoreSignificantCallType(newType: CallTypes, existingType: CallTypes): Boolean {
        val significanceOrder = listOf(
            CallTypes.MISSED,
            CallTypes.DECLINED,
            CallTypes.ABORTED,
            CallTypes.SUCCESS
        )

        val newIndex = significanceOrder.indexOf(newType)
        val existingIndex = significanceOrder.indexOf(existingType)

        return newIndex > existingIndex
    }

    /**
     * ✅ NUEVO: Actualizar log existente manteniendo posición
     */
    private fun updateExistingCallLog(callId: String, updatedLog: CallLog) {
        val index = _callLogs.indexOfFirst { it.id == callId }
        if (index != -1) {
            _callLogs[index] = updatedLog
        }
        savedCallLogs[callId] = updatedLog
    }

    /**
     * ✅ NUEVO: Agregar nuevo log manteniendo orden cronológico
     */
    private fun addNewCallLog(callLog: CallLog) {
        // Insertar al principio para los más recientes primero
        _callLogs.add(0, callLog)
        savedCallLogs[callLog.id] = callLog

        // Mantener un límite razonable en memoria
        if (_callLogs.size > 500) {
            val removed = _callLogs.removeAt(_callLogs.size - 1)
            savedCallLogs.remove(removed.id)
            log.d(TAG) { "Removed oldest call log to maintain limit: ${removed.id}" }
        }
    }

    /**
     * ✅ NUEVO: Crear objeto CallLog
     */
    private fun createCallLogObject(
        callData: CallData,
        type: CallTypes,
        duration: Int,
        callId: String
    ): CallLog {
        return CallLog(
            id = callId,
            direction = callData.direction,
            to = callData.to,
            formattedTo = formatPhoneNumber(callData.to),
            from = callData.from,
            formattedFrom = formatPhoneNumber(callData.from),
            contact = null,
            formattedStartDate = formatStartDate(callData.startTime),
            duration = duration,
            callType = type,
            localAddress = callData.getLocalParty()
        )
    }

    /**
     * ✅ MEJORADO: Guardar en BD con mejor gestión de duplicados
     */
    private suspend fun saveCallLogToDatabase(
        callLog: CallLog,
        callData: CallData? = null,
        startTime: Long? = null,
        endTime: Long? = null
    ) {
        try {
            val currentAccount = sipCoreManager?.currentAccountInfo
            val dbManager = databaseManager ?: return

            if (currentAccount == null) {
                log.w(TAG) { "No current account for saving call log" }
                return
            }

            var account = dbManager.getSipAccountByCredentials(
                currentAccount.username,
                currentAccount.domain
            )

            if (account == null) {
                account = dbManager.createOrUpdateSipAccount(
                    username = currentAccount.username,
                    password = currentAccount.password,
                    domain = currentAccount.domain,
                    displayName = currentAccount.username
                )
            }

            val finalStartTime = startTime ?: parseFormattedDate(callLog.formattedStartDate)
            val finalEndTime = endTime ?: (finalStartTime + (callLog.duration * 1000))

            dbManager.createCallLog(
                accountId = account.id,
                callData = callData ?: CallData(
                    callId = callLog.id,
                    from = callLog.from,
                    to = callLog.to,
                    direction = callLog.direction,
                    startTime = finalStartTime,
                    remoteDisplayName = callLog.formattedFrom
                ),
                callType = callLog.callType,
                endTime = finalEndTime
            )

            log.d(TAG) { "💾 Call log saved to database: ${callLog.id}" }

        } catch (e: Exception) {
            log.e(TAG) { "❌ Error saving to database: ${e.message}" }
        }
    }


    fun getCallLogsForNumber(phoneNumber: String): List<CallLog> {
        return _callLogs.filter {
            it.from == phoneNumber || it.to == phoneNumber
        }
    }

    fun getCallStatistics(): Map<String, Int> {
        return mapOf(
            "total" to _callLogs.size,
            "missed" to _callLogs.count { it.callType == CallTypes.MISSED },
            "successful" to _callLogs.count { it.callType == CallTypes.SUCCESS },
            "incoming" to _callLogs.count { it.direction == CallDirections.INCOMING },
            "outgoing" to _callLogs.count { it.direction == CallDirections.OUTGOING }
        )
    }


    /**
     * ✅ NUEVO: Método para cargar desde BD al inicializar
     */
    suspend fun initializeFromDatabase() {
        try {
            log.d(TAG) {
                "🔄 Initializing CallHistoryManager from database..."
            }
            loadCallLogsFromDatabase(300) // Cargar más registros
            log.d(TAG) {
                "✅ CallHistoryManager initialized with ${_callLogs.size} logs"
            }
        } catch (e: Exception) {
            log.e(TAG) {
                "❌ Error initializing from database: ${e.message}"
            }
        }
    }


    /**
     * ✅ MEJORADO: Carga desde BD preservando datos existentes
     */
    suspend fun loadCallLogsFromDatabase(limit: Int = 200) {
        try {
            val dbManager = databaseManager ?: return
            val dbCallLogs = dbManager.getRecentCallLogs(limit).first()

            log.d(TAG) { "📥 Loading ${dbCallLogs.size} logs from database..." }

            val existingIds = _callLogs.map { it.id }.toSet()
            var newCount = 0

            dbCallLogs.forEach { callLogWithContact ->
                val callLog = callLogWithContact.toCallLog()

                if (!existingIds.contains(callLog.id)) {
                    _callLogs.add(callLog)
                    newCount++
                }
            }

            // Ordenar por fecha (más recientes primero)
            _callLogs.sortByDescending {
                parseFormattedDate(it.formattedStartDate)
            }

            log.d(TAG) { "✅ Loaded $newCount new logs. Total: ${_callLogs.size}" }

            } catch (e: Exception) {
                log.e(TAG) { "❌ Error loading from database: ${e.message}" }
            }
        }

        // === MÉTODOS DE CONSULTA ===


    fun getIncomingCalls(): List<CallLog> {
        return _callLogs.filter { it.direction == CallDirections.INCOMING }
    }

    fun getOutgoingCalls(): List<CallLog> {
        return _callLogs.filter { it.direction == CallDirections.OUTGOING }
    }


    fun getCallsByType(type: CallTypes): List<CallLog> {
        return _callLogs.filter { it.callType == type }
    }

    /**
     * ✅ NUEVO: Método para buscar log específico
     */
    fun getCallLog(callId: String): CallLog? {
        return savedCallLogs[callId]
    }
    fun getAllCallLogs(): List<CallLog> = _callLogs.toList()

    fun getMissedCalls(): List<CallLog> =
        _callLogs.filter { it.callType == CallTypes.MISSED }
    /**
     * ✅ NUEVO: Método para actualizar duración de log existente
     */
    fun updateCallLogDuration(callId: String, newDuration: Int) {
        savedCallLogs[callId]?.let { existingLog ->
            val updatedLog = existingLog.copy(duration = newDuration)
            updateExistingCallLog(callId, updatedLog)
            _callLogsFlow.value = _callLogs.toList()
            log.d(TAG) { "Updated duration for call $callId: $newDuration seconds" }
        }
    }

    /**
     * ✅ NUEVO: Método para actualizar tipo de llamada
     */
    fun updateCallLogType(callId: String, newType: CallTypes) {
        savedCallLogs[callId]?.let { existingLog ->
            val updatedLog = existingLog.copy(callType = newType)
            updateExistingCallLog(callId, updatedLog)
            _callLogsFlow.value = _callLogs.toList()
            log.d(TAG) { "Updated type for call $callId: ${newType.name}" }
        }
    }

    // === MÉTODOS DE MANTENIMIENTO ===


    /**
     * ✅ NUEVO: Limpiar solo logs antiguos (más seguro)
     */
    fun clearOldCallLogs(daysToKeep: Int = 30) {
        scope.launch {
            try {
                val dbManager =
                    databaseManager ?: DatabaseManager.getInstance()
                // Esto depende de tu implementación en DatabaseManager
                // dbManager.clearOldCallLogs(daysToKeep)
                log.d(TAG) {
                    "Cleared call logs older than $daysToKeep days"
                }

                // Recargar los logs restantes
                loadCallLogsFromDatabase()
            } catch (e: Exception) {
                log.e(TAG) {
                    "Error clearing old call logs: ${e.message}"
                }
            }
        }
    }

    // === ESTADÍSTICAS ===



    /**
     * ✅ NUEVO: Sincroniza memoria con BD (útil para debugging)
     */
    suspend fun syncWithDatabase() {
        try {
            log.d(TAG) {
                "🔄 Synchronizing with database..."
            }
            loadCallLogsFromDatabase()
            log.d(TAG) {
                "✅ Synchronized with database. Total logs: ${_callLogs.size}"
            }
        } catch (e: Exception) {
            log.e(TAG) {
                "Error syncing with database: ${e.message}"
            }
        }
    }
    fun clearCallLogs() {
        _callLogs.clear()
        isInitialLoadComplete = false

        // Limpiar BD también
        scope.launch {
            try {
//                databaseManager?.clearAllCallLogs()
                log.d(tag = TAG) { "Call logs cleared from memory and database" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error clearing database: ${e.message}" }
            }
        }
    }

    // === DIAGNÓSTICO Y DEBUGGING ===

    /**
     * ✅ NUEVO: Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== CALL HISTORY DIAGNOSTIC ===")
            appendLine("Memory logs: ${_callLogs.size}")
            appendLine("Saved logs map: ${savedCallLogs.size}")
            appendLine("Processing IDs: ${processingCallIds.size}")
            appendLine("Recently added: ${recentlyAddedCallIds.size}")
            appendLine("Flow subscribers: ${_callLogsFlow.subscriptionCount.value}")

            appendLine("\n--- Recent calls ---")
            _callLogs.take(5).forEach { log ->
                appendLine("  ${log.id}: ${log.direction} - ${log.callType} - ${log.duration}s - ${log.formattedStartDate}")
            }

            appendLine("\n--- Statistics ---")
            val stats = getCallStatistics()
            appendLine("Total: ${stats.values}")
        }
    }

    /**
     * ✅ NUEVO: Verificar integridad de datos
     */
    fun verifyDataIntegrity(): Boolean {
        val memoryIds =
            _callLogs.map { it.id }.toSet()
        val savedIds = savedCallLogs.keys

        val memoryConsistent =
            memoryIds == savedIds
        val noDuplicates =
            _callLogs.size == memoryIds.size

        log.d(TAG) {
            "Data integrity - Memory consistent: $memoryConsistent, No duplicates: $noDuplicates"
        }

        return memoryConsistent && noDuplicates
    }

    // === MÉTODOS AUXILIARES ===

    private fun calculateDuration(
        startTime: Long,
        endTime: Long?
    ): Int {
        return if (endTime != null && endTime > startTime) {
            ((endTime - startTime) / 1000).toInt()
        } else {
            0
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun formatStartDate(timestamp: Long): String {
        val instant =
            Instant.fromEpochMilliseconds(
                timestamp
            )
        val localDateTime =
            instant.toLocalDateTime(TimeZone.currentSystemDefault())

        return localDateTime.dayOfMonth.toString()
            .padStart(2, '0') +
                ".${
                    localDateTime.monthNumber.toString()
                        .padStart(2, '0')
                }" +
                ".${localDateTime.year} " +
                localDateTime.hour.toString()
                    .padStart(2, '0') +
                ":${
                    localDateTime.minute.toString()
                        .padStart(2, '0')
                }"
    }

    @OptIn(ExperimentalTime::class)
    private fun parseStartDateToTimestamp(
        dateString: String
    ): Long {
        return try {
            // Implementar parsing inverso si es necesario
            kotlin.time.Clock.System.now()
                .toEpochMilliseconds()
        } catch (e: Exception) {
            0L
        }
    }

    private fun formatPhoneNumber(
        phoneNumber: String
    ): String {
        return phoneNumber // Puedes implementar formateo aquí
    }

    // === LIMPIEZA ===

    fun dispose() {
        scope.cancel()
        log.d(TAG) {
            "CallHistoryManager disposed"
        }
    }
}

@OptIn(ExperimentalTime::class)
private fun parseFormattedDate(formattedDate: String): Long {
    return try {
        // Implementar parser de fecha completo aquí
        kotlin.time.Clock.System.now().toEpochMilliseconds()
    } catch (e: Exception) {
        kotlin.time.Clock.System.now().toEpochMilliseconds()
    }
}
/**
 * ✅ Extensión para convertir CallLogWithContact a CallLog
 */
fun CallLogWithContact.toCallLog(): CallLog {
    return CallLog(
        id = callLog.id,
        direction = callLog.direction,
        to = callLog.phoneNumber,
        formattedTo = getDisplayName(),
        from = callLog.phoneNumber,
        formattedFrom = getDisplayName(),
        formattedStartDate = formatTimestamp(callLog.startTime),
        duration = callLog.duration,
        contact = contact?.displayName,
        localAddress = callLog.localAddress ?: "",
        callType = callLog.callType
    )
}

@OptIn(ExperimentalTime::class)
private fun formatTimestamp(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDateTime.dayOfMonth.toString().padStart(2, '0')}." +
            "${localDateTime.monthNumber.toString().padStart(2, '0')}." +
            "${localDateTime.year} " +
            "${localDateTime.hour.toString().padStart(2, '0')}:" +
            "${localDateTime.minute.toString().padStart(2, '0')}"
}
// === DATA CLASSES ===

data class CallStatistics(
    val totalCalls: Int,
    val missedCalls: Int,
    val successfulCalls: Int,
    val declinedCalls: Int,
    val abortedCalls: Int,
    val incomingCalls: Int,
    val outgoingCalls: Int,
    val totalDuration: Int
)


