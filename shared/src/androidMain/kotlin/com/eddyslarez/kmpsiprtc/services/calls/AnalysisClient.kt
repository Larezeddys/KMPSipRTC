package com.eddyslarez.kmpsiprtc.services.calls
//
//import com.eddyslarez.kmpsiprtc.platform.log
//import okhttp3.*
//import okhttp3.MediaType.Companion.toMediaType
//import okhttp3.RequestBody.Companion.asRequestBody
//import okhttp3.RequestBody.Companion.toRequestBody
//
//import java.io.File
//import java.util.concurrent.TimeUnit
//
///**
// * Cliente para conectar con el servidor de análisis
// */
//class AnalysisClient(private val baseUrl: String) {
//    private val TAG = "AnalysisClient"
//
//    private val okHttpClient = OkHttpClient.Builder()
//        .connectTimeout(30, TimeUnit.SECONDS)
//        .readTimeout(60, TimeUnit.SECONDS)
//        .writeTimeout(60, TimeUnit.SECONDS)
//        .addInterceptor(HttpLoggingInterceptor().apply {
//            level = HttpLoggingInterceptor.Level.BODY
//        })
//        .build()
//
//    private val retrofit = Retrofit.Builder()
//        .baseUrl(baseUrl)
//        .client(okHttpClient)
//        .addConverterFactory(GsonConverterFactory.create())
//        .build()
//
//    private val service = retrofit.create(AnalysisService::class.java)
//
//    /**
//     * Crear sesión de análisis
//     */
//    suspend fun createAnalysisSession(language: String = "es"): AnalysisSession? {
//        return try {
//            val request = CreateSessionRequest(
//                language = language,
//                enableAdvancedAnalysis = true,
//                enableRecording = true,
//                analysisFeatures = AnalysisFeatures(
//                    emotionalAnalysis = true,
//                    complianceCheck = true,
//                    keywordExtraction = true,
//                    conversationPatterns = true
//                )
//            )
//            service.createAnalysisSession(request)
//        } catch (e: Exception) {
//            log.e(TAG) { "❌ Error creating analysis session: ${e.message}" }
//            null
//        }
//    }
//
//    /**
//     * Enviar audio para análisis
//     */
//    suspend fun sendAudioForAnalysis(sessionId: String, audioFile: File): Boolean {
//        return try {
//            val requestBody = MultipartBody.Builder()
//                .setType(MultipartBody.FORM)
//                .addFormDataPart(
//                    "audio",
//                    audioFile.name,
//                    audioFile.asRequestBody("audio/wav".toMediaType())
//                )
//                .build()
//
//            val response = okHttpClient.newCall(
//                Request.Builder()
//                    .url("$baseUrl/session/analysis/$sessionId/upload-audio")
//                    .post(requestBody)
//                    .build()
//            ).execute()
//
//            response.isSuccessful
//        } catch (e: Exception) {
//            log.e(TAG) { "❌ Error sending audio: ${e.message}" }
//            false
//        }
//    }
//
//    /**
//     * Obtener análisis completo
//     */
//    suspend fun getComprehensiveAnalysis(sessionId: String): ComprehensiveAnalysis? {
//        return try {
//            service.getComprehensiveAnalysis(sessionId)
//        } catch (e: Exception) {
//            log.e(TAG) { "❌ Error getting analysis: ${e.message}" }
//            null
//        }
//    }
//
//    /**
//     * Finalizar sesión y obtener análisis
//     */
//    suspend fun endAnalysisSession(sessionId: String): AnalysisResult? {
//        return try {
//            service.endAnalysisSession(sessionId)
//        } catch (e: Exception) {
//            log.e(TAG) { "❌ Error ending session: ${e.message}" }
//            null
//        }
//    }
//}
//
//// Interfaces para Retrofit
//interface AnalysisService {
//    @POST("/session/analysis/create")
//    suspend fun createAnalysisSession(@Body request: CreateSessionRequest): AnalysisSession
//
//    @GET("/analysis/{sessionId}/comprehensive")
//    suspend fun getComprehensiveAnalysis(@Path("sessionId") sessionId: String): ComprehensiveAnalysis
//
//    @POST("/session/analysis/{sessionId}/end")
//    suspend fun endAnalysisSession(@Path("sessionId") sessionId: String): AnalysisResult
//}
//
//// Data classes para las requests/responses
//data class CreateSessionRequest(
//    val language: String = "es",
//    val voice: String = "alloy",
//    val enableRecording: Boolean = true,
//    val enableAdvancedAnalysis: Boolean = true,
//    val analysisFeatures: AnalysisFeatures = AnalysisFeatures()
//)
//
//data class AnalysisFeatures(
//    val emotionalAnalysis: Boolean = true,
//    val complianceCheck: Boolean = true,
//    val keywordExtraction: Boolean = true,
//    val conversationPatterns: Boolean = true
//)
//
//data class AnalysisSession(
//    val sessionId: String,
//    val type: String,
//    val wsUrl: String,
//    val expiresIn: Long,
//    val config: SessionConfig
//)
//
//data class SessionConfig(
//    val language: String,
//    val voice: String,
//    val features: SessionFeatures
//)
//
//data class SessionFeatures(
//    val recording: Boolean,
//    val advancedAnalysis: Boolean,
//    val emotionalAnalysis: Boolean,
//    val complianceCheck: Boolean,
//    val keywordExtraction: Boolean,
//    val conversationPatterns: Boolean,
//    val realTimeTranscription: Boolean
//)
//
//data class ComprehensiveAnalysis(
//    val sessionId: String,
//    val language: String,
//    val timestamp: String,
//    val summary: ConversationSummary?,
//    val patterns: ConversationPatterns?,
//    val emotions: EmotionalAnalysis?,
//    val compliance: ComplianceAnalysis?,
//    val keywords: KeywordAnalysis?,
//    val metrics: SpeechMetrics?,
//    val transcriptionCount: Int
//)
//
//data class ConversationSummary(
//    val language: String,
//    val summary: String,
//    val keyPoints: List<String>,
//    val decisions: List<String>,
//    val issues: List<String>,
//    val nextActions: List<String>,
//    val overallTone: String
//)
//
//data class ConversationPatterns(
//    val totalTurns: Int,
//    val avgTurnDuration: String,
//    val speakerStats: Map<String, SpeakerStats>,
//    val conversationFlow: String
//)
//
//data class SpeakerStats(
//    val turns: Int,
//    val totalDuration: Long,
//    val words: Int,
//    val avgTurnLength: String,
//    val participationRate: String,
//    val avgWordsPerTurn: String
//)
//
//data class EmotionalAnalysis(
//    val emotions: List<String>,
//    val emotionBreakdown: Map<String, Double>,
//    val tone: String,
//    val intensity: String,
//    val emotionalHighlights: List<String>,
//    val overallMood: String
//)
//
//data class ComplianceAnalysis(
//    val riskLevel: String,
//    val complianceIssues: List<String>,
//    val sensitiveTopics: List<String>,
//    val recommendations: List<String>,
//    val redFlags: List<String>,
//    val legalConsiderations: List<String>
//)
//
//data class KeywordAnalysis(
//    val topKeywords: List<Keyword>,
//    val wordCloud: String,
//    val uniqueTermsCount: Int
//)
//
//data class Keyword(
//    val word: String,
//    val frequency: Int
//)
//
//data class SpeechMetrics(
//    val wordCount: Int,
//    val sentenceCount: Int,
//    val avgWordsPerSentence: String,
//    val uniqueWords: Int,
//    val lexicalDiversity: String
//)
//
//data class AnalysisResult(
//    val message: String,
//    val sessionId: String,
//    val duration: Long,
//    val analysis: ComprehensiveAnalysis?,
//    val transcription: String
//)