package com.eddyslarez.kmpsiprtc.services.calls

import com.eddyslarez.kmpsiprtc.platform.log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.util.*
import java.io.File

/**
 * Cliente Ktor para conectar con el servidor de análisis
 */
class AnalysisKtorClient(private val baseUrl: String) {
    private val TAG = "AnalysisKtorClient"

    private val client = HttpClient {
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
                disableHtmlEscaping()
            }
        }
        install(Logging) {
            level = LogLevel.HEADERS
            logger = object : Logger {
                override fun log(message: String) {
                    log.d(TAG) { "Ktor: $message" }
                }
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 30000
            socketTimeoutMillis = 60000
        }
        defaultRequest {
            url("$baseUrl/")
            contentType(ContentType.Application.Json)
        }
    }

    /**
     * Crear sesión de análisis
     */
    suspend fun createAnalysisSession(language: String = "es"): AnalysisSession? {
        return try {
            val response: HttpResponse = client.post("session/analysis/create") {
                setBody(CreateSessionRequest(
                    language = language,
                    enableAdvancedAnalysis = true,
                    enableRecording = true,
                    analysisFeatures = AnalysisFeatures(
                        emotionalAnalysis = true,
                        complianceCheck = true,
                        keywordExtraction = true,
                        conversationPatterns = true
                    )
                ))
            }

            if (response.status.isSuccess()) {
                response.body<AnalysisSession>()
            } else {
                log.e(TAG) { "❌ Error creating session: ${response.status}" }
                null
            }
        } catch (e: Exception) {
            log.e(TAG) { "❌ Error creating analysis session: ${e.message}" }
            null
        }
    }

    /**
     * Enviar archivo de audio para análisis
     */
    suspend fun uploadAudioFile(sessionId: String, audioFile: File): Boolean {
        return try {
            val response: HttpResponse = client.submitFormWithBinaryData(
                url = "session/analysis/$sessionId/upload-audio",
                formData = formData {
                    append("audio", audioFile.readBytes(), Headers.build {
                        append(HttpHeaders.ContentType, "audio/wav")
                        append(HttpHeaders.ContentDisposition, "filename=\"${audioFile.name}\"")
                    })
                }
            )

            response.status.isSuccess().also { success ->
                if (success) {
                    log.d(TAG) { "✅ Audio uploaded successfully" }
                } else {
                    log.e(TAG) { "❌ Upload failed: ${response.status}" }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "❌ Error uploading audio: ${e.message}" }
            false
        }
    }

    /**
     * Obtener análisis completo
     */
    suspend fun getComprehensiveAnalysis(sessionId: String): ComprehensiveAnalysis? {
        return try {
            val response: HttpResponse = client.get("analysis/$sessionId/comprehensive")

            if (response.status.isSuccess()) {
                response.body<ComprehensiveAnalysis>()
            } else {
                log.e(TAG) { "❌ Error getting analysis: ${response.status}" }
                null
            }
        } catch (e: Exception) {
            log.e(TAG) { "❌ Error getting comprehensive analysis: ${e.message}" }
            null
        }
    }

    /**
     * Finalizar sesión y obtener análisis
     */
    suspend fun endAnalysisSession(sessionId: String): AnalysisResult? {
        return try {
            val response: HttpResponse = client.post("session/analysis/$sessionId/end")

            if (response.status.isSuccess()) {
                response.body<AnalysisResult>()
            } else {
                log.e(TAG) { "❌ Error ending session: ${response.status}" }
                null
            }
        } catch (e: Exception) {
            log.e(TAG) { "❌ Error ending analysis session: ${e.message}" }
            null
        }
    }

    /**
     * Enviar audio directamente via WebSocket (opcional - para tiempo real)
     */
    suspend fun sendAudioViaWebSocket(sessionId: String, audioData: ByteArray): Boolean {
        return try {
            // Implementación simplificada - en producción usarías WebSockets reales
            val response: HttpResponse = client.post("session/analysis/$sessionId/audio-chunk") {
                setBody(audioData)
                contentType(ContentType.Application.OctetStream)
            }

            response.status.isSuccess()
        } catch (e: Exception) {
            log.e(TAG) { "❌ Error sending audio via WebSocket: ${e.message}" }
            false
        }
    }

    /**
     * Verificar salud del servidor
     */
    suspend fun checkServerHealth(): Boolean {
        return try {
            val response: HttpResponse = client.get("health")
            response.status.isSuccess()
        } catch (e: Exception) {
            log.e(TAG) { "❌ Server health check failed: ${e.message}" }
            false
        }
    }

    fun close() {
        client.close()
    }
}

// Data classes (las mismas que antes)
data class CreateSessionRequest(
    val language: String = "es",
    val voice: String = "alloy",
    val enableRecording: Boolean = true,
    val enableAdvancedAnalysis: Boolean = true,
    val analysisFeatures: AnalysisFeatures = AnalysisFeatures()
)

data class AnalysisFeatures(
    val emotionalAnalysis: Boolean = true,
    val complianceCheck: Boolean = true,
    val keywordExtraction: Boolean = true,
    val conversationPatterns: Boolean = true
)

data class AnalysisSession(
    val sessionId: String,
    val type: String,
    val wsUrl: String,
    val expiresIn: Long,
    val config: SessionConfig
)

data class SessionConfig(
    val language: String,
    val voice: String,
    val features: SessionFeatures
)

data class SessionFeatures(
    val recording: Boolean,
    val advancedAnalysis: Boolean,
    val emotionalAnalysis: Boolean,
    val complianceCheck: Boolean,
    val keywordExtraction: Boolean,
    val conversationPatterns: Boolean,
    val realTimeTranscription: Boolean
)

data class ComprehensiveAnalysis(
    val sessionId: String,
    val language: String,
    val timestamp: String,
    val summary: ConversationSummary?,
    val patterns: ConversationPatterns?,
    val emotions: EmotionalAnalysis?,
    val compliance: ComplianceAnalysis?,
    val keywords: KeywordAnalysis?,
    val metrics: SpeechMetrics?,
    val transcriptionCount: Int
)

data class ConversationSummary(
    val language: String,
    val summary: String,
    val keyPoints: List<String>,
    val decisions: List<String>,
    val issues: List<String>,
    val nextActions: List<String>,
    val overallTone: String
)

data class ConversationPatterns(
    val totalTurns: Int,
    val avgTurnDuration: String,
    val speakerStats: Map<String, SpeakerStats>,
    val conversationFlow: String
)

data class SpeakerStats(
    val turns: Int,
    val totalDuration: Long,
    val words: Int,
    val avgTurnLength: String,
    val participationRate: String,
    val avgWordsPerTurn: String
)

data class EmotionalAnalysis(
    val emotions: List<String>,
    val emotionBreakdown: Map<String, Double>,
    val tone: String,
    val intensity: String,
    val emotionalHighlights: List<String>,
    val overallMood: String
)

data class ComplianceAnalysis(
    val riskLevel: String,
    val complianceIssues: List<String>,
    val sensitiveTopics: List<String>,
    val recommendations: List<String>,
    val redFlags: List<String>,
    val legalConsiderations: List<String>
)

data class KeywordAnalysis(
    val topKeywords: List<Keyword>,
    val wordCloud: String,
    val uniqueTermsCount: Int
)

data class Keyword(
    val word: String,
    val frequency: Int
)

data class SpeechMetrics(
    val wordCount: Int,
    val sentenceCount: Int,
    val avgWordsPerSentence: String,
    val uniqueWords: Int,
    val lexicalDiversity: String
)

data class AnalysisResult(
    val message: String,
    val sessionId: String,
    val duration: Long,
    val analysis: ComprehensiveAnalysis?,
    val transcription: String
)