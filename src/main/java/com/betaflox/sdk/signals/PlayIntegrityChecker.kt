package com.betaflox.sdk.signals

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Handles Google Play Integrity API integration.
 * 
 * Collects integrity signals for server-side verification.
 * The SDK only collects the token - verification happens server-side.
 */
internal class PlayIntegrityChecker(private val context: Context) {
    
    companion object {
        private const val TAG = "PlayIntegrityChecker"
        
        /**
         * Cloud project number from Firebase Console.
         * This should be configured via SDK initialization.
         */
        private var cloudProjectNumber: Long? = null
        
        fun setCloudProjectNumber(projectNumber: Long) {
            cloudProjectNumber = projectNumber
        }
    }
    
    private val integrityManager by lazy {
        IntegrityManagerFactory.create(context)
    }
    
    /**
     * Request an integrity token from Play Integrity API.
     * 
     * @param nonce A unique, single-use nonce for this request
     * @return IntegritySignals with the result, or UNKNOWN status on failure
     */
    suspend fun checkIntegrity(nonce: String): IntegritySignals {
        val projectNumber = cloudProjectNumber
        
        if (projectNumber == null) {
            Log.w(TAG, "Cloud project number not configured, skipping integrity check")
            return IntegritySignals(
                meetsBasicIntegrity = false,
                meetsDeviceIntegrity = false,
                meetsStrongIntegrity = false,
                status = IntegrityStatus.UNKNOWN
            )
        }
        
        return try {
            val tokenResponse = requestIntegrityToken(nonce, projectNumber)
            
            if (tokenResponse != null) {
                // The token needs to be sent to server for verification
                // Server will decode and extract the actual integrity verdicts
                // For now, we mark as "needs verification"
                Log.d(TAG, "Integrity token obtained successfully")
                
                IntegritySignals(
                    meetsBasicIntegrity = false, // Will be populated by server
                    meetsDeviceIntegrity = false,
                    meetsStrongIntegrity = false,
                    status = IntegrityStatus.VALID // Token obtained, needs server verification
                )
            } else {
                Log.w(TAG, "Failed to obtain integrity token")
                IntegritySignals(
                    meetsBasicIntegrity = false,
                    meetsDeviceIntegrity = false,
                    meetsStrongIntegrity = false,
                    status = IntegrityStatus.FAILED
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Integrity check failed: ${e.message}")
            IntegritySignals(
                meetsBasicIntegrity = false,
                meetsDeviceIntegrity = false,
                meetsStrongIntegrity = false,
                status = IntegrityStatus.UNKNOWN
            )
        }
    }
    
    /**
     * Request integrity token using coroutines.
     */
    private suspend fun requestIntegrityToken(
        nonce: String, 
        projectNumber: Long
    ): String? = suspendCancellableCoroutine { continuation ->
        
        val request = IntegrityTokenRequest.builder()
            .setCloudProjectNumber(projectNumber)
            .setNonce(nonce)
            .build()
        
        integrityManager.requestIntegrityToken(request)
            .addOnSuccessListener { response ->
                continuation.resume(response.token())
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Integrity token request failed: ${e.message}")
                continuation.resume(null)
            }
    }
    
    /**
     * Check if Play Integrity API is available on this device.
     */
    fun isAvailable(): Boolean {
        return try {
            // Check if Google Play Services is available
            val packageManager = context.packageManager
            packageManager.getPackageInfo("com.google.android.gms", 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
