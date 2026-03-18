package com.betaflox.sdk.tracking

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client-side Play Integrity token request manager.
 * Generates integrity tokens with campaign-specific nonces for server-side verification.
 */
class PlayIntegrityManager(private val context: Context) {

    companion object {
        private const val TAG = "PlayIntegrityManager"
    }

    /**
     * Request a Play Integrity token with a campaign-specific nonce.
     * 
     * @param nonce A unique nonce string (typically campaignId + timestamp)
     * @return The integrity token string, or null if request fails
     */
    suspend fun requestIntegrityToken(nonce: String): String? {
        return try {
            val integrityManager = IntegrityManagerFactory.create(context)
            val request = IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .build()

            suspendCancellableCoroutine { continuation ->
                integrityManager.requestIntegrityToken(request)
                    .addOnSuccessListener { response ->
                        val token = response.token()
                        Log.d(TAG, "Integrity token obtained (${token.length} chars)")
                        continuation.resume(token)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Integrity token request failed", e)
                        continuation.resume(null)
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PlayIntegrityManager error", e)
            null
        }
    }

    /**
     * Generate a campaign-specific nonce for integrity verification.
     */
    fun generateNonce(campaignId: String, testerId: String): String {
        val timestamp = System.currentTimeMillis()
        return android.util.Base64.encodeToString(
            "$campaignId:$testerId:$timestamp".toByteArray(),
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
        )
    }
}
