package com.betaflox.sdk.signals

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Uploads device signals to Firebase Firestore.
 * 
 * Creates session documents with all collected signals.
 * The Cloud Function will process these and compute risk scores.
 */
internal class SignalUploader(
    private val firestore: FirebaseFirestore
) {
    
    companion object {
        private const val TAG = "SignalUploader"
        private const val COLLECTION_SESSIONS = "sessions"
    }
    
    /**
     * Upload device signals to Firestore.
     * 
     * @param userId The authenticated user ID
     * @param appId The application package name
     * @param campaignId The campaign this session belongs to
     * @param signals The collected device signals
     * @return The session document ID if successful, null otherwise
     */
    suspend fun uploadSignals(
        userId: String,
        authUid: String?, 
        appId: String,
        campaignId: String,
        signals: DeviceSignals
    ): String? {
        return try {
            val sessionId = signals.sessionSignals.sessionId
            
            val sessionData = mutableMapOf<String, Any>(
                "userId" to userId,
                "appId" to appId,
                "campaignId" to campaignId,
                "createdAt" to com.google.firebase.Timestamp.now(),
                "packageName" to appId
            )
            
            // Store the Firebase Auth UID to allow reading back the verdict
            if (authUid != null) {
                sessionData["firebaseAuthUid"] = authUid
            }
            
            // Add all signal categories
            sessionData.putAll(signals.toFirestoreMap())
            
            // Do NOT set riskScore or verdict - that's server-side only
            // The security rules will reject any attempt to set these client-side
            
            firestore.collection(COLLECTION_SESSIONS)
                .document(sessionId)
                .set(sessionData)
                .await()
            
            Log.d(TAG, "Session $sessionId uploaded successfully")
            sessionId
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload signals: ${e.message}")
            null
        }
    }
    
    /**
     * Update session with additional signals (e.g., after Play Integrity check).
     */
    suspend fun updateIntegritySignals(
        sessionId: String,
        integritySignals: IntegritySignals
    ): Boolean {
        return try {
            firestore.collection(COLLECTION_SESSIONS)
                .document(sessionId)
                .update("integritySignals", integritySignals.toMap())
                .await()
            
            Log.d(TAG, "Integrity signals updated for session $sessionId")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update integrity signals: ${e.message}")
            false
        }
    }
    
    /**
     * Update session with foreground transition count.
     */
    suspend fun updateForegroundTransitions(
        sessionId: String,
        transitions: Int
    ): Boolean {
        return try {
            firestore.collection(COLLECTION_SESSIONS)
                .document(sessionId)
                .update("sessionSignals.foregroundTransitions", transitions)
                .await()
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update transitions: ${e.message}")
            false
        }
    }
    
    /**
     * Get session verdict from server (read-only).
     * 
     * This reads the verdict computed by the Cloud Function.
     * Returns null if verdict not yet computed.
     */
    suspend fun getSessionVerdict(sessionId: String): SessionVerdict? {
        return try {
            val document = firestore.collection(COLLECTION_SESSIONS)
                .document(sessionId)
                .get()
                .await()
            
            if (document.exists()) {
                val riskScore = document.getDouble("riskScore")
                val verdict = document.getString("verdict")
                val verdictReason = document.getString("verdictReason")
                
                if (verdict != null) {
                    SessionVerdict(
                        riskScore = riskScore?.toFloat() ?: 0f,
                        verdict = verdict,
                        reason = verdictReason
                    )
                } else {
                    null // Verdict not yet computed by server
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get verdict: ${e.message}")
            null
        }
    }
}

/**
 * Session verdict from server-side risk scoring.
 */
data class SessionVerdict(
    val riskScore: Float,
    val verdict: String, // "VALID", "SUSPICIOUS", "INVALID"
    val reason: String?
)
