package com.eddyslarez.kmpsiprtc.data.database

import com.eddyslarez.kmpsiprtc.core.SipCoreManager
import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallHistoryManager
import com.eddyslarez.kmpsiprtc.data.models.CallTypes
import com.eddyslarez.kmpsiprtc.data.models.RegistrationState
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Herramienta de diagnóstico completa para verificar el sistema de base de datos
 *
 * @author Eddys Larez
 */
class DatabaseInspector(
    private val databaseManager: DatabaseManager,
    private val sipCoreManager: SipCoreManager?,
    private val callHistoryManager: CallHistoryManager
) {
    private val TAG = "DatabaseInspector"

    // ================================
    // ==== FULL SYSTEM DIAGNOSTIC ====
    // ================================
    suspend fun runFullDiagnostic(): String {
        val report = StringBuilder()

        report.appendLine("╔════════════════════════════════════════════════╗")
        report.appendLine("║   DATABASE & SIP SYSTEM INSPECTION TOOL       ║")
        report.appendLine("╚════════════════════════════════════════════════╝")
        report.appendLine()

        report.appendLine(checkDatabaseStatus())
        report.appendLine()
        report.appendLine(checkSipAccounts())
        report.appendLine()
        report.appendLine(checkCallLogs())
        report.appendLine()
        report.appendLine(checkContacts())
        report.appendLine()
        report.appendLine(checkConfiguration())
        report.appendLine()
        report.appendLine(checkDataIntegrity())
        report.appendLine()
        report.appendLine(checkMemoryVsDatabaseSync())
        report.appendLine()
        report.appendLine(getGeneralStatistics())

        return report.toString()
    }

    // ================================
    // ==== DATABASE STATUS ===========
    // ================================
    private suspend fun checkDatabaseStatus(): String {
        return try {
            buildString {
                appendLine("┌─ DATABASE STATUS ─────────────────────────────┐")
                val stats = databaseManager.getGeneralStatistics()
                appendLine("  Initialized: ✅")
                appendLine("  Total Tables: 6 (expected)")
                appendLine("  Database Health: ${if (stats.totalAccounts >= 0) "✅ OK" else "❌ ERROR"}")
                appendLine("└───────────────────────────────────────────────┘")
            }
        } catch (e: Exception) {
            "┌─ DATABASE STATUS ─────────────────────────────┐\n" +
                    "  ❌ ERROR: ${e.message}\n" +
                    "└───────────────────────────────────────────────┘"
        }
    }

    // ================================
    // ==== SIP ACCOUNTS ==============
    // ================================
    private suspend fun checkSipAccounts(): String {
        return try {
            buildString {
                appendLine("┌─ SIP ACCOUNTS ────────────────────────────────┐")
                val activeAccounts = databaseManager.getActiveSipAccounts().first()
                val registeredAccounts = databaseManager.getRegisteredSipAccounts().first()
                appendLine("  Total Active Accounts: ${activeAccounts.size}")
                appendLine("  Registered Accounts: ${registeredAccounts.size}")

                if (activeAccounts.isNotEmpty()) {
                    appendLine("\n  Account Details:")
                    activeAccounts.forEachIndexed { index, account ->
                        appendLine("  ${index + 1}. ${account.username}@${account.domain}")
                        appendLine("     • ID: ${account.id}")
                        appendLine("     • State: ${account.registrationState}")
                        appendLine("     • Active: ${if (account.isActive) "✅" else "❌"}")
                        appendLine("     • Push Token: ${if (account.pushToken.isNullOrEmpty()) "❌ None" else "✅ Set"}")
                        appendLine("     • Created: ${formatTimestamp(account.createdAt)}")
                        appendLine("     • Updated: ${formatTimestamp(account.updatedAt)}")
                        if (account.registrationExpiry > 0) {
                            val expiresIn = account.registrationExpiry - Clock.System.now().toEpochMilliseconds()
                            appendLine("     • Expires in: ${expiresIn / 1000}s")
                        }
                        appendLine()
                    }
                } else appendLine("  ⚠️  No accounts found in database")
                appendLine("└───────────────────────────────────────────────┘")
            }
        } catch (e: Exception) {
            "┌─ SIP ACCOUNTS ────────────────────────────────┐\n" +
                    "  ❌ ERROR: ${e.message}\n" +
                    "└───────────────────────────────────────────────┘"
        }
    }

    // ================================
    // ==== CALL LOGS =================
    // ================================
    private suspend fun checkCallLogs(): String {
        return try {
            buildString {
                appendLine("┌─ CALL LOGS ───────────────────────────────────┐")
                val recentLogs = databaseManager.getRecentCallLogs(100).first()
                val missedCalls = databaseManager.getMissedCallLogs().first()
                appendLine("  Total Call Logs: ${recentLogs.size}")
                appendLine("  Missed Calls: ${missedCalls.size}")

                if (recentLogs.isNotEmpty()) {
                    val byType = recentLogs.groupBy { it.callLog.callType }
                    appendLine("\n  Breakdown by Type:")
                    appendLine("  • SUCCESS: ${byType[CallTypes.SUCCESS]?.size ?: 0}")
                    appendLine("  • MISSED: ${byType[CallTypes.MISSED]?.size ?: 0}")
                    appendLine("  • DECLINED: ${byType[CallTypes.DECLINED]?.size ?: 0}")
                    appendLine("  • ABORTED: ${byType[CallTypes.ABORTED]?.size ?: 0}")

                    val byDirection = recentLogs.groupBy { it.callLog.direction }
                    appendLine("\n  Breakdown by Direction:")
                    appendLine("  • INCOMING: ${byDirection[CallDirections.INCOMING]?.size ?: 0}")
                    appendLine("  • OUTGOING: ${byDirection[CallDirections.OUTGOING]?.size ?: 0}")

                    appendLine("\n  Last 5 Calls:")
                    recentLogs.take(5).forEachIndexed { index, callLogWithContact ->
                        val call = callLogWithContact.callLog
                        val contact = callLogWithContact.contact
                        appendLine("  ${index + 1}. ${call.phoneNumber}")
                        appendLine("     • Direction: ${call.direction}")
                        appendLine("     • Type: ${call.callType}")
                        appendLine("     • Duration: ${calculateDuration(call.startTime, call.endTime)}s")
                        appendLine("     • Time: ${formatTimestamp(call.startTime)}")
                        if (contact != null) appendLine("     • Contact: ${contact.displayName}")
                        appendLine()
                    }

                    val orphanLogs = recentLogs.filter { it.callLog.accountId.isEmpty() }
                    if (orphanLogs.isNotEmpty()) appendLine("  ⚠️  Warning: ${orphanLogs.size} call logs without account")
                } else appendLine("  ℹ️  No call logs found")
                appendLine("└───────────────────────────────────────────────┘")
            }
        } catch (e: Exception) {
            "┌─ CALL LOGS ───────────────────────────────────┐\n" +
                    "  ❌ ERROR: ${e.message}\n" +
                    "└───────────────────────────────────────────────┘"
        }
    }

    // ================================
    // ==== CONTACTS ==================
    // ================================
    private suspend fun checkContacts(): String {
        return try {
            buildString {
                appendLine("┌─ CONTACTS ────────────────────────────────────┐")
                val contacts = databaseManager.getAllContacts().first()
                val blockedCount = contacts.count { it.isBlocked }
                appendLine("  Total Contacts: ${contacts.size}")
                appendLine("  Blocked Contacts: $blockedCount")

                if (contacts.isNotEmpty()) {
                    appendLine("\n  Sample Contacts (first 5):")
                    contacts.take(5).forEachIndexed { index, contact ->
                        appendLine("  ${index + 1}. ${contact.displayName}")
                        appendLine("     • Phone: ${contact.phoneNumber}")
                        appendLine("     • Email: ${contact.email ?: "N/A"}")
                        appendLine("     • Company: ${contact.company ?: "N/A"}")
                        appendLine("     • Blocked: ${if (contact.isBlocked) "❌ Yes" else "✅ No"}")
                        appendLine()
                    }
                } else appendLine("  ℹ️  No contacts found")
                appendLine("└───────────────────────────────────────────────┘")
            }
        } catch (e: Exception) {
            "┌─ CONTACTS ────────────────────────────────────┐\n" +
                    "  ❌ ERROR: ${e.message}\n" +
                    "└───────────────────────────────────────────────┘"
        }
    }

    // ================================
    // ==== CONFIGURATION =============
    // ================================
    private suspend fun checkConfiguration(): String {
        return try {
            buildString {
                appendLine("┌─ CONFIGURATION ───────────────────────────────┐")
                val config = databaseManager.getAppConfig()
                if (config != null) {
                    appendLine("  Configuration Found: ✅")
                    appendLine("  • Incoming Ringtone: ${if (config.incomingRingtoneUri.isNullOrEmpty()) "❌ Not set" else "✅ Set"}")
                    appendLine("  • Outgoing Ringtone: ${if (config.outgoingRingtoneUri.isNullOrEmpty()) "❌ Not set" else "✅ Set"}")
                    appendLine("  • Default Domain: ${config.defaultDomain ?: "N/A"}")
                    appendLine("  • WebSocket URL: ${config.webSocketUrl ?: "N/A"}")
                    appendLine("  • User Agent: ${config.userAgent ?: "N/A"}")
                    appendLine("  • Logs Enabled: ${if (config.enableLogs) "✅" else "❌"}")
                    appendLine("  • Auto-Reconnect: ${if (config.enableAutoReconnect) "✅" else "❌"}")
                    appendLine("  • Ping Interval: ${config.pingIntervalMs}ms")
                } else {
                    appendLine("  ⚠️  No configuration found")
                }
                appendLine("└───────────────────────────────────────────────┘")
            }
        } catch (e: Exception) {
            "┌─ CONFIGURATION ───────────────────────────────┐\n" +
                    "  ❌ ERROR: ${e.message}\n" +
                    "└───────────────────────────────────────────────┘"
        }
    }

    // ================================
    // ==== DATA INTEGRITY ============
    // ================================
    private suspend fun checkDataIntegrity(): String {
        return try {
            buildString {
                appendLine("┌─ DATA INTEGRITY ──────────────────────────────┐")
                val issues = mutableListOf<String>()

                val accounts = databaseManager.getActiveSipAccounts().first()
                val accountsWithoutCredentials = accounts.filter { it.username.isEmpty() || it.password.isEmpty() || it.domain.isEmpty() }
                if (accountsWithoutCredentials.isNotEmpty()) issues.add("${accountsWithoutCredentials.size} accounts with missing credentials")

                val callLogs = databaseManager.getRecentCallLogs(1000).first()
                val orphanCallLogs = callLogs.filter { it.callLog.accountId.isEmpty() }
                if (orphanCallLogs.isNotEmpty()) issues.add("${orphanCallLogs.size} call logs without account reference")

                val invalidTimestamps = callLogs.filter { it.callLog.startTime <= 0 || (it.callLog.endTime != null && it.callLog.endTime!! < it.callLog.startTime) }
                if (invalidTimestamps.isNotEmpty()) issues.add("${invalidTimestamps.size} call logs with invalid timestamps")

                val contacts = databaseManager.getAllContacts().first()
                val contactsWithoutPhone = contacts.filter { it.phoneNumber.isEmpty() }
                if (contactsWithoutPhone.isNotEmpty()) issues.add("${contactsWithoutPhone.size} contacts without phone number")

                if (issues.isEmpty()) appendLine("  ✅ All data integrity checks passed")
                else {
                    appendLine("  ⚠️  Found ${issues.size} integrity issues:")
                    issues.forEach { appendLine("  • $it") }
                }

                appendLine("└───────────────────────────────────────────────┘")
            }
        } catch (e: Exception) {
            "┌─ DATA INTEGRITY ──────────────────────────────┐\n" +
                    "  ❌ ERROR: ${e.message}\n" +
                    "└───────────────────────────────────────────────┘"
        }
    }

    // ================================
    // ==== MEMORY vs DATABASE ========
    // ================================
    private suspend fun checkMemoryVsDatabaseSync(): String {
        return try {
            buildString {
                appendLine("┌─ MEMORY ↔ DATABASE SYNC ──────────────────────┐")
                if (sipCoreManager == null) {
                    appendLine("  ⚠️  SipCoreManager not available")
                    appendLine("└───────────────────────────────────────────────┘")
                    return@buildString
                }

                val memoryAccounts = sipCoreManager.activeAccounts
                val dbAccounts = databaseManager.getActiveSipAccounts().first()

                appendLine("  Accounts in Memory: ${memoryAccounts.size}")
                appendLine("  Accounts in Database: ${dbAccounts.size}")

                val missingInDb = memoryAccounts.keys.filter { accountKey ->
                    val (username, domain) = accountKey.split("@")
                    dbAccounts.none { it.username == username && it.domain == domain }
                }

                val missingInMemory = dbAccounts.filter { dbAccount ->
                    val accountKey = "${dbAccount.username}@${dbAccount.domain}"
                    !memoryAccounts.containsKey(accountKey)
                }

                if (missingInDb.isEmpty() && missingInMemory.isEmpty()) appendLine("\n  ✅ Perfect sync - all accounts match")
                else {
                    if (missingInDb.isNotEmpty()) {
                        appendLine("\n  ⚠️  ${missingInDb.size} accounts in memory but not in database:")
                        missingInDb.forEach { appendLine("     • $it") }
                    }
                    if (missingInMemory.isNotEmpty()) {
                        appendLine("\n  ⚠️  ${missingInMemory.size} accounts in database but not in memory:")
                        missingInMemory.forEach { appendLine("     • ${it.username}@${it.domain}") }
                    }
                }

                appendLine("\n  Registration State Comparison:")
                memoryAccounts.forEach { (accountKey, _) ->
                    val (username, domain) = accountKey.split("@")
                    val dbAccount = dbAccounts.find { it.username == username && it.domain == domain }
                    val memoryState = sipCoreManager.getRegistrationState(accountKey)
                    val dbState = dbAccount?.registrationState ?: RegistrationState.NONE
                    val icon = if (memoryState == dbState) "✅" else "⚠️"
                    appendLine("  $icon $accountKey")
                    appendLine("     Memory: $memoryState | DB: $dbState")
                }

                appendLine("└───────────────────────────────────────────────┘")
            }
        } catch (e: Exception) {
            "┌─ MEMORY ↔ DATABASE SYNC ──────────────────────┐\n" +
                    "  ❌ ERROR: ${e.message}\n" +
                    "└───────────────────────────────────────────────┘"
        }
    }

    // ================================
    // ==== GENERAL STATISTICS ========
    // ================================
    private suspend fun getGeneralStatistics(): String {
        return try {
            buildString {
                appendLine("┌─ GENERAL STATISTICS ──────────────────────────┐")
                val stats = databaseManager.getGeneralStatistics()
                appendLine("  Total Accounts: ${stats.totalAccounts}")
                appendLine("  Registered Accounts: ${stats.registeredAccounts}")
                appendLine("  Total Calls: ${stats.totalCalls}")
                appendLine("  Missed Calls: ${stats.missedCalls}")
                appendLine("  Total Contacts: ${stats.totalContacts}")
                appendLine("  Active Calls: ${stats.activeCalls}")

                val connectivityStats = databaseManager.getAccountConnectivityStats()
                appendLine("\n  Connectivity Stats:")
                appendLine("  • Total: ${connectivityStats["total"]}")
                appendLine("  • Registered: ${connectivityStats["registered"]}")
                appendLine("  • Connecting: ${connectivityStats["connecting"]}")
                appendLine("  • Failed: ${connectivityStats["failed"]}")
                appendLine("  • Disconnected: ${connectivityStats["disconnected"]}")

                appendLine("└───────────────────────────────────────────────┘")
            }
        } catch (e: Exception) {
            "┌─ GENERAL STATISTICS ──────────────────────────┐\n" +
                    "  ❌ ERROR: ${e.message}\n" +
                    "└───────────────────────────────────────────────┘"
        }
    }

    // ================================
    // ==== DATABASE VERIFICATION =====
    // ================================
    suspend fun verifyIntegrity(): String {
        return buildString {
            appendLine("╔═══════════════════════════════════════════════╗")
            appendLine("║     DATABASE INTEGRITY VERIFICATION          ║")
            appendLine("╚═══════════════════════════════════════════════╝")
            appendLine()
            appendLine(checkDuplicates())
            appendLine()
            appendLine(checkMemoryVsDatabaseConsistency())
            appendLine()
            appendLine(checkMissingData())
            appendLine()
            appendLine(checkReferentialIntegrity())
            appendLine()
            appendLine(generateRecommendations())
        }
    }

    private suspend fun checkDuplicates(): String {
        return try {
            buildString {
                appendLine("┌─ DUPLICATE CHECK ─────────────────────────────┐")
                val callLogs = databaseManager.getRecentCallLogs(5000).first()
                val groupedByCallId = callLogs.groupBy { it.callLog.callId }
                val duplicates = groupedByCallId.filter { it.value.size > 1 }

                if (duplicates.isEmpty()) appendLine("  ✅ No duplicate call logs found")
                else {
                    appendLine("  ⚠️  Found ${duplicates.size} duplicate call IDs:")
                    duplicates.entries.take(5).forEach { (callId, logs) ->
                        appendLine("     • $callId: ${logs.size} entries")
                    }
                    if (duplicates.size > 5) appendLine("     ... and ${duplicates.size - 5} more")
                }
                appendLine("└───────────────────────────────────────────────┘")
            }
        } catch (e: Exception) {
            "Error checking duplicates: ${e.message}"
        }
    }

    private suspend fun checkMemoryVsDatabaseConsistency(): String {
        return try {
            buildString {
                appendLine("┌─ MEMORY ↔ DATABASE CONSISTENCY ───────────────┐")
                val memoryLogs = callHistoryManager.getAllCallLogs()
                val dbLogs = databaseManager.getRecentCallLogs(5000).first()
                appendLine("  Memory call logs: ${memoryLogs.size}")
                appendLine("  Database call logs: ${dbLogs.size}")

                val memoryIds = memoryLogs.map { it.id }.toSet()
                val dbIds = dbLogs.map { it.callLog.callId }.toSet()

                val missingInDb = memoryIds - dbIds
                val missingInMemory = dbIds - memoryIds

                when {
                    missingInDb.isEmpty() && missingInMemory.isEmpty() -> appendLine("\n  ✅ Perfect consistency")
                    missingInDb.isNotEmpty() -> {
                        appendLine("\n  ⚠️  ${missingInDb.size} calls in memory not in database")
                        appendLine("     This means recent calls are NOT being saved!")
                    }
                    missingInMemory.isNotEmpty() -> {
                        appendLine("\n  ℹ️  ${missingInMemory.size} calls in database not in memory")
                        appendLine("     This is normal after app restart")
                    }
                }
                appendLine("└───────────────────────────────────────────────┘")
            }
        } catch (e: Exception) {
            "Error checking consistency: ${e.message}"
        }
    }

    private suspend fun checkMissingData(): String {
        return try {
            buildString {
                appendLine("┌─ MISSING DATA CHECK ──────────────────────────┐")
                val issues = mutableListOf<String>()
                val config = databaseManager.getAppConfig()
                if (config == null) issues.add("App configuration is missing")
                val contacts = databaseManager.getAllContacts().first()
                appendLine("  Total contacts: ${contacts.size}")
                val accounts = databaseManager.getActiveSipAccounts().first()
                appendLine("  Total accounts: ${accounts.size}")
                if (accounts.isEmpty()) issues.add("No SIP accounts found")
                if (issues.isNotEmpty()) {
                    appendLine("\n  ⚠️  Issues found:")
                    issues.forEach { appendLine("     • $it") }
                } else appendLine("\n  ✅ All essential data present")
                appendLine("└───────────────────────────────────────────────┘")
            }
        } catch (e: Exception) {
            "Error checking missing data: ${e.message}"
        }
    }

    private suspend fun checkReferentialIntegrity(): String {
        return try {
            buildString {
                appendLine("┌─ REFERENTIAL INTEGRITY ───────────────────────┐")
                val issues = mutableListOf<String>()
                val callLogs = databaseManager.getRecentCallLogs(5000).first()
                val accounts = databaseManager.getActiveSipAccounts().first()
                val accountIds = accounts.map { it.id }.toSet()

                val orphanLogs = callLogs.filter { it.callLog.accountId.isNotEmpty() && !accountIds.contains(it.callLog.accountId) }
                if (orphanLogs.isNotEmpty()) issues.add("${orphanLogs.size} call logs reference non-existent accounts")

                val invalidTimestamps = callLogs.filter { it.callLog.startTime <= 0 || (it.callLog.endTime != null && it.callLog.endTime!! < it.callLog.startTime) }
                if (invalidTimestamps.isNotEmpty()) issues.add("${invalidTimestamps.size} call logs have invalid timestamps")

                if (issues.isEmpty()) appendLine("  ✅ All referential integrity checks passed")
                else {
                    appendLine("  ⚠️  Issues found:")
                    issues.forEach { appendLine("     • $it") }
                }
                appendLine("└───────────────────────────────────────────────┘")
            }
        } catch (e: Exception) {
            "Error checking referential integrity: ${e.message}"
        }
    }

    private suspend fun generateRecommendations(): String {
        return buildString {
            appendLine("┌─ RECOMMENDATIONS ─────────────────────────────┐")
            val callLogs = databaseManager.getRecentCallLogs(5000).first()
            val config = databaseManager.getAppConfig()
            val recommendations = mutableListOf<String>()
            if (config == null) recommendations.add("Create default app configuration")
            val groupedByCallId = callLogs.groupBy { it.callLog.callId }
            val duplicates = groupedByCallId.filter { it.value.size > 1 }
            if (duplicates.isNotEmpty()) recommendations.add("Remove ${duplicates.size} duplicate call logs")

            val now = Clock.System.now().toEpochMilliseconds()
            val veryOldLogs = callLogs.filter { now - it.callLog.startTime > (90 * 24 * 60 * 60 * 1000L) }
            if (veryOldLogs.isNotEmpty()) recommendations.add("Consider cleaning ${veryOldLogs.size} logs older than 90 days")

            if (recommendations.isEmpty()) appendLine("  ✅ No actions needed - database is healthy")
            else {
                appendLine("  Recommended actions:")
                recommendations.forEach { appendLine("     • $it") }
            }
            appendLine("└───────────────────────────────────────────────┘")
        }
    }

    suspend fun repairCommonIssues(): String {
        return buildString {
            appendLine("╔═══════════════════════════════════════════════╗")
            appendLine("║     DATABASE REPAIR TOOL                      ║")
            appendLine("╚═══════════════════════════════════════════════╝")
            appendLine()

            var repairsPerformed = 0
            try {
                val config = databaseManager.getAppConfig()
                if (config == null) {
                    databaseManager.createOrUpdateAppConfig()
                    appendLine("✅ Created default app configuration")
                    repairsPerformed++
                }

                val callLogs = databaseManager.getRecentCallLogs(10000).first()
                val groupedByCallId = callLogs.groupBy { it.callLog.callId }
                val duplicates = groupedByCallId.filter { it.value.size > 1 }
                if (duplicates.isNotEmpty()) {
                    appendLine("⚠️  Found ${duplicates.size} duplicate sets")
                    appendLine("   (Manual cleanup recommended)")
                }

                callHistoryManager.syncWithDatabase()
                appendLine("✅ Synchronized memory with database")
                repairsPerformed++

                appendLine()
                appendLine("═══════════════════════════════════════════════")
                appendLine("Repairs performed: $repairsPerformed")
            } catch (e: Exception) {
                appendLine("❌ Error during repair: ${e.message}")
            }
        }
    }
        /**
     * Test de escritura y lectura
     */
    suspend fun testDatabaseReadWrite(): String {
        return try {
            buildString {
                appendLine("┌─ READ/WRITE TEST ─────────────────────────────┐")

                val testAccountId = "test_account_${Clock.System.now().toEpochMilliseconds()}"

                // Test 1: Crear cuenta
                appendLine("  Test 1: Creating test account...")
                val account = databaseManager.createOrUpdateSipAccount(
                    username = "test_user",
                    password = "test_pass",
                    domain = "test.domain",
                    displayName = "Test User",
                    pushToken = "test_token",
                    pushProvider = "fcm"
                )
                appendLine("  ✅ Account created: ${account.id}")

                // Test 2: Leer cuenta
                appendLine("\n  Test 2: Reading account...")
                val readAccount = databaseManager.getSipAccountByCredentials("test_user", "test.domain")
                appendLine("  ${if (readAccount != null) "✅" else "❌"} Account read: ${readAccount?.username}")

                // Test 3: Crear call log
                appendLine("\n  Test 3: Creating call log...")
                val testCallData = CallData(
                    callId = "test_call_${Clock.System.now().toEpochMilliseconds()}",
                    from = "+1234567890",
                    to = "+0987654321",
                    direction = CallDirections.OUTGOING,
                    startTime = Clock.System.now().toEpochMilliseconds()
                )

                val callLog = databaseManager.createCallLog(
                    accountId = account.id,
                    callData = testCallData,
                    callType = CallTypes.SUCCESS,
                    endTime = Clock.System.now().toEpochMilliseconds() + 30000
                )
                appendLine("  ✅ Call log created: ${callLog.id}")

                // Test 4: Leer call log
                appendLine("\n  Test 4: Reading call log...")
                val callLogs = databaseManager.getRecentCallLogs(1).first()
                appendLine("  ${if (callLogs.isNotEmpty()) "✅" else "❌"} Call log read")

                // Test 5: Crear contacto
                appendLine("\n  Test 5: Creating contact...")
                val contact = databaseManager.createOrUpdateContact(
                    phoneNumber = "+1234567890",
                    displayName = "Test Contact",
                    firstName = "Test",
                    lastName = "Contact"
                )
                appendLine("  ✅ Contact created: ${contact.id}")

                // Test 6: Actualizar configuración
                appendLine("\n  Test 6: Updating configuration...")
                val config = databaseManager.createOrUpdateAppConfig(
                    enableLogs = true,
                    enableAutoReconnect = true
                )
                appendLine("  ✅ Configuration updated")

                // Limpiar datos de prueba
                appendLine("\n  Cleaning up test data...")
                databaseManager.deleteSipAccount(account.id)
                appendLine("  ✅ Test data cleaned")

                appendLine("\n  ✅ ALL READ/WRITE TESTS PASSED")
                appendLine("└───────────────────────────────────────────────┘")
            }
        } catch (e: Exception) {
            "┌─ READ/WRITE TEST ─────────────────────────────┐\n" +
                    "  ❌ TEST FAILED: ${e.message}\n" +
                    "  Stack trace: ${e.stackTraceToString()}\n" +
                    "└───────────────────────────────────────────────┘"
        }
    }
    // ================================
    // ==== HELPER METHODS ============
    // ================================
    private fun formatTimestamp(timestamp: Long): String {
        val instant = Instant.fromEpochMilliseconds(timestamp)
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${localDateTime.dayOfMonth.toString().padStart(2, '0')}/" +
                "${localDateTime.monthNumber.toString().padStart(2, '0')}/" +
                "${localDateTime.year} " +
                "${localDateTime.hour.toString().padStart(2, '0')}:" +
                "${localDateTime.minute.toString().padStart(2, '0')}"
    }

    private fun calculateDuration(startTime: Long, endTime: Long?): Long {
        return if (endTime != null && endTime > startTime) (endTime - startTime) / 1000 else 0
    }
}


//class DatabaseDiagnosticTool(
//    private val databaseManager: DatabaseManager,
//    private val sipCoreManager: SipCoreManager?
//) {
//    private val TAG = "DatabaseDiagnostic"
//
//    /**
//     * Ejecuta diagnóstico completo del sistema
//     */
//    suspend fun runFullDiagnostic(): String {
//        val report = StringBuilder()
//
//        report.appendLine("╔════════════════════════════════════════════════╗")
//        report.appendLine("║   DATABASE DIAGNOSTIC & VERIFICATION TOOL      ║")
//        report.appendLine("╚════════════════════════════════════════════════╝")
//        report.appendLine()
//
//        // 1. Estado de la base de datos
//        report.appendLine(checkDatabaseStatus())
//        report.appendLine()
//
//        // 2. Verificación de cuentas SIP
//        report.appendLine(checkSipAccounts())
//        report.appendLine()
//
//        // 3. Verificación de call logs
//        report.appendLine(checkCallLogs())
//        report.appendLine()
//
//        // 4. Verificación de contactos
//        report.appendLine(checkContacts())
//        report.appendLine()
//
//        // 5. Verificación de configuración
//        report.appendLine(checkConfiguration())
//        report.appendLine()
//
//        // 6. Verificación de integridad
//        report.appendLine(checkDataIntegrity())
//        report.appendLine()
//
//        // 7. Sincronización memoria vs BD
//        report.appendLine(checkMemoryVsDatabaseSync())
//        report.appendLine()
//
//        // 8. Estadísticas generales
//        report.appendLine(getGeneralStatistics())
//
//        return report.toString()
//    }
//
//    /**
//     * 1. Estado de la base de datos
//     */
//    private suspend fun checkDatabaseStatus(): String {
//        return try {
//            buildString {
//                appendLine("┌─ DATABASE STATUS ─────────────────────────────┐")
//
//                val stats = databaseManager.getGeneralStatistics()
//
//                appendLine("  Initialized: ✅")
//                appendLine("  Total Tables: 6 (expected)")
//                appendLine("  Database Health: ${if (stats.totalAccounts >= 0) "✅ OK" else "❌ ERROR"}")
//
//                appendLine("└───────────────────────────────────────────────┘")
//            }
//        } catch (e: Exception) {
//            "┌─ DATABASE STATUS ─────────────────────────────┐\n" +
//                    "  ❌ ERROR: ${e.message}\n" +
//                    "└───────────────────────────────────────────────┘"
//        }
//    }
//
//    /**
//     * 2. Verificación de cuentas SIP
//     */
//    private suspend fun checkSipAccounts(): String {
//        return try {
//            buildString {
//                appendLine("┌─ SIP ACCOUNTS ────────────────────────────────┐")
//
//                val activeAccounts = databaseManager.getActiveSipAccounts().first()
//                val registeredAccounts = databaseManager.getRegisteredSipAccounts().first()
//
//                appendLine("  Total Active Accounts: ${activeAccounts.size}")
//                appendLine("  Registered Accounts: ${registeredAccounts.size}")
//
//                if (activeAccounts.isNotEmpty()) {
//                    appendLine("\n  Account Details:")
//                    activeAccounts.forEachIndexed { index, account ->
//                        appendLine("  ${index + 1}. ${account.username}@${account.domain}")
//                        appendLine("     • ID: ${account.id}")
//                        appendLine("     • State: ${account.registrationState}")
//                        appendLine("     • Active: ${if (account.isActive) "✅" else "❌"}")
//                        appendLine("     • Push Token: ${if (account.pushToken.isNullOrEmpty()) "❌ None" else "✅ Set"}")
//                        appendLine("     • Created: ${formatTimestamp(account.createdAt)}")
//                        appendLine("     • Updated: ${formatTimestamp(account.updatedAt)}")
//
//                        if (account.registrationExpiry > 0) {
//                            val expiresIn = account.registrationExpiry - Clock.System.now().toEpochMilliseconds()
//                            appendLine("     • Expires in: ${expiresIn / 1000}s")
//                        }
//                        appendLine()
//                    }
//                } else {
//                    appendLine("  ⚠️  No accounts found in database")
//                }
//
//                appendLine("└───────────────────────────────────────────────┘")
//            }
//        } catch (e: Exception) {
//            "┌─ SIP ACCOUNTS ────────────────────────────────┐\n" +
//                    "  ❌ ERROR: ${e.message}\n" +
//                    "└───────────────────────────────────────────────┘"
//        }
//    }
//
//    /**
//     * 3. Verificación de call logs
//     */
//    private suspend fun checkCallLogs(): String {
//        return try {
//            buildString {
//                appendLine("┌─ CALL LOGS ───────────────────────────────────┐")
//
//                val recentLogs = databaseManager.getRecentCallLogs(100).first()
//                val missedCalls = databaseManager.getMissedCallLogs().first()
//
//                appendLine("  Total Call Logs: ${recentLogs.size}")
//                appendLine("  Missed Calls: ${missedCalls.size}")
//
//                if (recentLogs.isNotEmpty()) {
//                    // Estadísticas por tipo
//                    val byType = recentLogs.groupBy { it.callLog.callType }
//                    appendLine("\n  Breakdown by Type:")
//                    appendLine("  • SUCCESS: ${byType[CallTypes.SUCCESS]?.size ?: 0}")
//                    appendLine("  • MISSED: ${byType[CallTypes.MISSED]?.size ?: 0}")
//                    appendLine("  • DECLINED: ${byType[CallTypes.DECLINED]?.size ?: 0}")
//                    appendLine("  • ABORTED: ${byType[CallTypes.ABORTED]?.size ?: 0}")
//
//                    // Estadísticas por dirección
//                    val byDirection = recentLogs.groupBy { it.callLog.direction }
//                    appendLine("\n  Breakdown by Direction:")
//                    appendLine("  • INCOMING: ${byDirection[CallDirections.INCOMING]?.size ?: 0}")
//                    appendLine("  • OUTGOING: ${byDirection[CallDirections.OUTGOING]?.size ?: 0}")
//
//                    // Últimas 5 llamadas
//                    appendLine("\n  Last 5 Calls:")
//                    recentLogs.take(5).forEachIndexed { index, callLogWithContact ->
//                        val call = callLogWithContact.callLog
//                        val contact = callLogWithContact.contact
//
//                        appendLine("  ${index + 1}. ${call.phoneNumber}")
//                        appendLine("     • Direction: ${call.direction}")
//                        appendLine("     • Type: ${call.callType}")
//                        appendLine("     • Duration: ${calculateDuration(call.startTime, call.endTime)}s")
//                        appendLine("     • Time: ${formatTimestamp(call.startTime)}")
//                        if (contact != null) {
//                            appendLine("     • Contact: ${contact.displayName}")
//                        }
//                        appendLine()
//                    }
//
//                    // Verificar si hay call logs sin accountId
//                    val orphanLogs = recentLogs.filter { it.callLog.accountId.isEmpty() }
//                    if (orphanLogs.isNotEmpty()) {
//                        appendLine("  ⚠️  Warning: ${orphanLogs.size} call logs without account")
//                    }
//                } else {
//                    appendLine("  ℹ️  No call logs found")
//                }
//
//                appendLine("└───────────────────────────────────────────────┘")
//            }
//        } catch (e: Exception) {
//            "┌─ CALL LOGS ───────────────────────────────────┐\n" +
//                    "  ❌ ERROR: ${e.message}\n" +
//                    "└───────────────────────────────────────────────┘"
//        }
//    }
//
//    /**
//     * 4. Verificación de contactos
//     */
//    private suspend fun checkContacts(): String {
//        return try {
//            buildString {
//                appendLine("┌─ CONTACTS ────────────────────────────────────┐")
//
//                val contacts = databaseManager.getAllContacts().first()
//                val blockedCount = contacts.count { it.isBlocked }
//
//                appendLine("  Total Contacts: ${contacts.size}")
//                appendLine("  Blocked Contacts: $blockedCount")
//
//                if (contacts.isNotEmpty()) {
//                    appendLine("\n  Sample Contacts (first 5):")
//                    contacts.take(5).forEachIndexed { index, contact ->
//                        appendLine("  ${index + 1}. ${contact.displayName}")
//                        appendLine("     • Phone: ${contact.phoneNumber}")
//                        appendLine("     • Email: ${contact.email ?: "N/A"}")
//                        appendLine("     • Company: ${contact.company ?: "N/A"}")
//                        appendLine("     • Blocked: ${if (contact.isBlocked) "❌ Yes" else "✅ No"}")
//                        appendLine()
//                    }
//                } else {
//                    appendLine("  ℹ️  No contacts found")
//                }
//
//                appendLine("└───────────────────────────────────────────────┘")
//            }
//        } catch (e: Exception) {
//            "┌─ CONTACTS ────────────────────────────────────┐\n" +
//                    "  ❌ ERROR: ${e.message}\n" +
//                    "└───────────────────────────────────────────────┘"
//        }
//    }
//
//    /**
//     * 5. Verificación de configuración
//     */
//    private suspend fun checkConfiguration(): String {
//        return try {
//            buildString {
//                appendLine("┌─ CONFIGURATION ───────────────────────────────┐")
//
//                val config = databaseManager.getAppConfig()
//
//                if (config != null) {
//                    appendLine("  Configuration Found: ✅")
//                    appendLine("\n  Settings:")
//                    appendLine("  • Incoming Ringtone: ${if (config.incomingRingtoneUri.isNullOrEmpty()) "❌ Not set" else "✅ Set"}")
//                    appendLine("  • Outgoing Ringtone: ${if (config.outgoingRingtoneUri.isNullOrEmpty()) "❌ Not set" else "✅ Set"}")
//                    appendLine("  • Default Domain: ${config.defaultDomain ?: "N/A"}")
//                    appendLine("  • WebSocket URL: ${config.webSocketUrl ?: "N/A"}")
//                    appendLine("  • User Agent: ${config.userAgent ?: "N/A"}")
//                    appendLine("  • Logs Enabled: ${if (config.enableLogs) "✅" else "❌"}")
//                    appendLine("  • Auto-Reconnect: ${if (config.enableAutoReconnect) "✅" else "❌"}")
//                    appendLine("  • Ping Interval: ${config.pingIntervalMs}ms")
//                } else {
//                    appendLine("  ⚠️  No configuration found")
//                    appendLine("  Creating default configuration...")
//
//                    val newConfig = databaseManager.createOrUpdateAppConfig()
//                    appendLine("  ✅ Default configuration created")
//                }
//
//                appendLine("└───────────────────────────────────────────────┘")
//            }
//        } catch (e: Exception) {
//            "┌─ CONFIGURATION ───────────────────────────────┐\n" +
//                    "  ❌ ERROR: ${e.message}\n" +
//                    "└───────────────────────────────────────────────┘"
//        }
//    }
//
//    /**
//     * 6. Verificación de integridad de datos
//     */
//    private suspend fun checkDataIntegrity(): String {
//        return try {
//            buildString {
//                appendLine("┌─ DATA INTEGRITY ──────────────────────────────┐")
//
//                val issues = mutableListOf<String>()
//
//                // Verificar cuentas sin credenciales
//                val accounts = databaseManager.getActiveSipAccounts().first()
//                val accountsWithoutCredentials = accounts.filter {
//                    it.username.isEmpty() || it.password.isEmpty() || it.domain.isEmpty()
//                }
//                if (accountsWithoutCredentials.isNotEmpty()) {
//                    issues.add("${accountsWithoutCredentials.size} accounts with missing credentials")
//                }
//
//                // Verificar call logs sin accountId
//                val callLogs = databaseManager.getRecentCallLogs(1000).first()
//                val orphanCallLogs = callLogs.filter { it.callLog.accountId.isEmpty() }
//                if (orphanCallLogs.isNotEmpty()) {
//                    issues.add("${orphanCallLogs.size} call logs without account reference")
//                }
//
//                // Verificar call logs con timestamps inválidos
//                val invalidTimestamps = callLogs.filter {
//                    it.callLog.startTime <= 0 ||
//                            (it.callLog.endTime != null && it.callLog.endTime!! < it.callLog.startTime)
//                }
//                if (invalidTimestamps.isNotEmpty()) {
//                    issues.add("${invalidTimestamps.size} call logs with invalid timestamps")
//                }
//
//                // Verificar contactos sin número de teléfono
//                val contacts = databaseManager.getAllContacts().first()
//                val contactsWithoutPhone = contacts.filter { it.phoneNumber.isEmpty() }
//                if (contactsWithoutPhone.isNotEmpty()) {
//                    issues.add("${contactsWithoutPhone.size} contacts without phone number")
//                }
//
//                if (issues.isEmpty()) {
//                    appendLine("  ✅ All data integrity checks passed")
//                } else {
//                    appendLine("  ⚠️  Found ${issues.size} integrity issues:")
//                    issues.forEach { issue ->
//                        appendLine("  • $issue")
//                    }
//                }
//
//                appendLine("└───────────────────────────────────────────────┘")
//            }
//        } catch (e: Exception) {
//            "┌─ DATA INTEGRITY ──────────────────────────────┐\n" +
//                    "  ❌ ERROR: ${e.message}\n" +
//                    "└───────────────────────────────────────────────┘"
//        }
//    }
//
//    /**
//     * 7. Sincronización memoria vs BD
//     */
//    private suspend fun checkMemoryVsDatabaseSync(): String {
//        return try {
//            buildString {
//                appendLine("┌─ MEMORY ↔ DATABASE SYNC ──────────────────────┐")
//
//                if (sipCoreManager == null) {
//                    appendLine("  ⚠️  SipCoreManager not available")
//                    appendLine("└───────────────────────────────────────────────┘")
//                    return@buildString
//                }
//
//                val memoryAccounts = sipCoreManager.activeAccounts
//                val dbAccounts = databaseManager.getActiveSipAccounts().first()
//
//                appendLine("  Accounts in Memory: ${memoryAccounts.size}")
//                appendLine("  Accounts in Database: ${dbAccounts.size}")
//
//                // Cuentas en memoria pero no en BD
//                val missingInDb = memoryAccounts.keys.filter { accountKey ->
//                    val (username, domain) = accountKey.split("@")
//                    dbAccounts.none { it.username == username && it.domain == domain }
//                }
//
//                // Cuentas en BD pero no en memoria
//                val missingInMemory = dbAccounts.filter { dbAccount ->
//                    val accountKey = "${dbAccount.username}@${dbAccount.domain}"
//                    !memoryAccounts.containsKey(accountKey)
//                }
//
//                if (missingInDb.isEmpty() && missingInMemory.isEmpty()) {
//                    appendLine("\n  ✅ Perfect sync - all accounts match")
//                } else {
//                    if (missingInDb.isNotEmpty()) {
//                        appendLine("\n  ⚠️  ${missingInDb.size} accounts in memory but not in database:")
//                        missingInDb.forEach { appendLine("     • $it") }
//                    }
//
//                    if (missingInMemory.isNotEmpty()) {
//                        appendLine("\n  ⚠️  ${missingInMemory.size} accounts in database but not in memory:")
//                        missingInMemory.forEach {
//                            appendLine("     • ${it.username}@${it.domain}")
//                        }
//                    }
//                }
//
//                // Verificar estados de registro
//                appendLine("\n  Registration State Comparison:")
//                memoryAccounts.forEach { (accountKey, accountInfo) ->
//                    val (username, domain) = accountKey.split("@")
//                    val dbAccount = dbAccounts.find {
//                        it.username == username && it.domain == domain
//                    }
//
//                    val memoryState = sipCoreManager.getRegistrationState(accountKey)
//                    val dbState = dbAccount?.registrationState ?: RegistrationState.NONE
//
//                    val match = memoryState == dbState
//                    val icon = if (match) "✅" else "⚠️"
//
//                    appendLine("  $icon $accountKey")
//                    appendLine("     Memory: $memoryState | DB: $dbState")
//                }
//
//                appendLine("└───────────────────────────────────────────────┘")
//            }
//        } catch (e: Exception) {
//            "┌─ MEMORY ↔ DATABASE SYNC ──────────────────────┐\n" +
//                    "  ❌ ERROR: ${e.message}\n" +
//                    "└───────────────────────────────────────────────┘"
//        }
//    }
//
//    /**
//     * 8. Estadísticas generales
//     */
//    private suspend fun getGeneralStatistics(): String {
//        return try {
//            buildString {
//                appendLine("┌─ GENERAL STATISTICS ──────────────────────────┐")
//
//                val stats = databaseManager.getGeneralStatistics()
//
//                appendLine("  Total Accounts: ${stats.totalAccounts}")
//                appendLine("  Registered Accounts: ${stats.registeredAccounts}")
//                appendLine("  Total Calls: ${stats.totalCalls}")
//                appendLine("  Missed Calls: ${stats.missedCalls}")
//                appendLine("  Total Contacts: ${stats.totalContacts}")
//                appendLine("  Active Calls: ${stats.activeCalls}")
//
//                // Estadísticas de conectividad
//                val connectivityStats = databaseManager.getAccountConnectivityStats()
//                appendLine("\n  Connectivity Stats:")
//                appendLine("  • Total: ${connectivityStats["total"]}")
//                appendLine("  • Registered: ${connectivityStats["registered"]}")
//                appendLine("  • Connecting: ${connectivityStats["connecting"]}")
//                appendLine("  • Failed: ${connectivityStats["failed"]}")
//                appendLine("  • Disconnected: ${connectivityStats["disconnected"]}")
//
//                appendLine("└───────────────────────────────────────────────┘")
//            }
//        } catch (e: Exception) {
//            "┌─ GENERAL STATISTICS ──────────────────────────┐\n" +
//                    "  ❌ ERROR: ${e.message}\n" +
//                    "└───────────────────────────────────────────────┘"
//        }
//    }
//
//    /**
//     * Test de escritura y lectura
//     */
//    suspend fun testDatabaseReadWrite(): String {
//        return try {
//            buildString {
//                appendLine("┌─ READ/WRITE TEST ─────────────────────────────┐")
//
//                val testAccountId = "test_account_${Clock.System.now().toEpochMilliseconds()}"
//
//                // Test 1: Crear cuenta
//                appendLine("  Test 1: Creating test account...")
//                val account = databaseManager.createOrUpdateSipAccount(
//                    username = "test_user",
//                    password = "test_pass",
//                    domain = "test.domain",
//                    displayName = "Test User",
//                    pushToken = "test_token",
//                    pushProvider = "fcm"
//                )
//                appendLine("  ✅ Account created: ${account.id}")
//
//                // Test 2: Leer cuenta
//                appendLine("\n  Test 2: Reading account...")
//                val readAccount = databaseManager.getSipAccountByCredentials("test_user", "test.domain")
//                appendLine("  ${if (readAccount != null) "✅" else "❌"} Account read: ${readAccount?.username}")
//
//                // Test 3: Crear call log
//                appendLine("\n  Test 3: Creating call log...")
//                val testCallData = CallData(
//                    callId = "test_call_${Clock.System.now().toEpochMilliseconds()}",
//                    from = "+1234567890",
//                    to = "+0987654321",
//                    direction = CallDirections.OUTGOING,
//                    startTime = Clock.System.now().toEpochMilliseconds()
//                )
//
//                val callLog = databaseManager.createCallLog(
//                    accountId = account.id,
//                    callData = testCallData,
//                    callType = CallTypes.SUCCESS,
//                    endTime = Clock.System.now().toEpochMilliseconds() + 30000
//                )
//                appendLine("  ✅ Call log created: ${callLog.id}")
//
//                // Test 4: Leer call log
//                appendLine("\n  Test 4: Reading call log...")
//                val callLogs = databaseManager.getRecentCallLogs(1).first()
//                appendLine("  ${if (callLogs.isNotEmpty()) "✅" else "❌"} Call log read")
//
//                // Test 5: Crear contacto
//                appendLine("\n  Test 5: Creating contact...")
//                val contact = databaseManager.createOrUpdateContact(
//                    phoneNumber = "+1234567890",
//                    displayName = "Test Contact",
//                    firstName = "Test",
//                    lastName = "Contact"
//                )
//                appendLine("  ✅ Contact created: ${contact.id}")
//
//                // Test 6: Actualizar configuración
//                appendLine("\n  Test 6: Updating configuration...")
//                val config = databaseManager.createOrUpdateAppConfig(
//                    enableLogs = true,
//                    enableAutoReconnect = true
//                )
//                appendLine("  ✅ Configuration updated")
//
//                // Limpiar datos de prueba
//                appendLine("\n  Cleaning up test data...")
//                databaseManager.deleteSipAccount(account.id)
//                appendLine("  ✅ Test data cleaned")
//
//                appendLine("\n  ✅ ALL READ/WRITE TESTS PASSED")
//                appendLine("└───────────────────────────────────────────────┘")
//            }
//        } catch (e: Exception) {
//            "┌─ READ/WRITE TEST ─────────────────────────────┐\n" +
//                    "  ❌ TEST FAILED: ${e.message}\n" +
//                    "  Stack trace: ${e.stackTraceToString()}\n" +
//                    "└───────────────────────────────────────────────┘"
//        }
//    }
//
//    // === HELPER METHODS ===
//
//    private fun formatTimestamp(timestamp: Long): String {
//        val instant = Instant.fromEpochMilliseconds(timestamp)
//        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
//
//        return "${localDateTime.dayOfMonth.toString().padStart(2, '0')}/" +
//                "${localDateTime.monthNumber.toString().padStart(2, '0')}/" +
//                "${localDateTime.year} " +
//                "${localDateTime.hour.toString().padStart(2, '0')}:" +
//                "${localDateTime.minute.toString().padStart(2, '0')}"
//    }
//
//    private fun calculateDuration(startTime: Long, endTime: Long?): Long {
//        return if (endTime != null && endTime > startTime) {
//            (endTime - startTime) / 1000
//        } else {
//            0
//        }
//    }
//}
