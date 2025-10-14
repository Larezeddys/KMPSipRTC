package com.eddyslarez.kmpsiprtc.data.models

import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.utils.generateId
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime


/**
 * Gestor de historial de llamadas
 *
 * @author Eddys Larez
 */
class CallHistoryManager {

    private val _callLogs = mutableListOf<CallLog>()
    val callLogs: List<CallLog> get() = _callLogs.toList()
    val TAG= "CallHistoryManager"
    fun addCallLog(
        callData: CallData,
        type: CallTypes,
        endTime: Long? = null
    ) {
        val duration = if (endTime != null && callData.startTime > 0) {
            ((endTime - callData.startTime) / 1000).toInt()
        } else {
            0
        }

        val callLog = CallLog(
            id = callData.callId.ifEmpty { generateId() },
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
        log.d(TAG) { "callLogs $callLog" }
        _callLogs.add(0, callLog)
    }

    fun getAllCallLogs(): List<CallLog> = callLogs

    fun getMissedCalls(): List<CallLog> {
        return callLogs.filter { it.callType == CallTypes.MISSED }
    }

    fun getIncomingCalls(): List<CallLog> {
        return callLogs.filter { it.direction == CallDirections.INCOMING }
    }

    fun getOutgoingCalls(): List<CallLog> {
        return callLogs.filter { it.direction == CallDirections.OUTGOING }
    }

    fun getCallLogsForNumber(phoneNumber: String): List<CallLog> {
        return callLogs.filter {
            it.to == phoneNumber || it.from == phoneNumber
        }
    }

    fun getCallsByType(type: CallTypes): List<CallLog> {
        return callLogs.filter { it.callType == type }
    }

    fun clearCallLogs() {
        _callLogs.clear()
    }

    fun getCallStatistics(): CallStatistics {
        return CallStatistics(
            totalCalls = callLogs.size,
            missedCalls = callLogs.count { it.callType == CallTypes.MISSED },
            successfulCalls = callLogs.count { it.callType == CallTypes.SUCCESS },
            declinedCalls = callLogs.count { it.callType == CallTypes.DECLINED },
            abortedCalls = callLogs.count { it.callType == CallTypes.ABORTED },
            incomingCalls = callLogs.count { it.direction == CallDirections.INCOMING },
            outgoingCalls = callLogs.count { it.direction == CallDirections.OUTGOING },
            totalDuration = callLogs.sumOf { it.duration }
        )
    }

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

    private fun formatStartDate(timestamp: Long): String {
        val instant = Instant.fromEpochMilliseconds(timestamp)
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())

        return localDateTime.dayOfMonth.toString().padStart(2, '0') +
                ".${localDateTime.monthNumber.toString().padStart(2, '0')}" +
                ".${localDateTime.year} " +
                localDateTime.hour.toString().padStart(2, '0') +
                ":${localDateTime.minute.toString().padStart(2, '0')}"
    }

    private fun formatPhoneNumber(phoneNumber: String): String {
        return phoneNumber
    }
}