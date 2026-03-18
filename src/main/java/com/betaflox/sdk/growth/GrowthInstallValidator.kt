package com.betaflox.sdk.growth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.betaflox.sdk.config.SDKConfig
import com.betaflox.sdk.tracking.InstallReferrerManager
import com.betaflox.sdk.tracking.PlayIntegrityManager
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

/**
 * Orchestrates the full growth campaign install validation flow:
 * 1. Captures referrer data from Play Install Referrer API
 * 2. Generates Play Integrity token
 * 3. Generates idempotency key (stored in encrypted prefs)
 * 4. Calls routeInstallValidation Cloud Function
 * 5. Stores result and exposes state
 * 6. On failure, delegates to GrowthValidationWorker for retry
 */
class GrowthInstallValidator(
    private val context: Context,
    private val referrerManager: InstallReferrerManager,
    private val integrityManager: PlayIntegrityManager,
    private val functions: FirebaseFunctions
) {

    companion object {
        private const val TAG = "GrowthInstallValidator"
        private const val ENCRYPTED_PREFS_NAME = "growth_secure"
    }

    private val _validationState = MutableStateFlow<GrowthValidationState>(GrowthValidationState.Idle)
    val validationState: StateFlow<GrowthValidationState> = _validationState.asStateFlow()

    private val encryptedPrefs: SharedPreferences by lazy { createEncryptedPrefs() }
    private val standardPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Run validation only if this is a growth campaign and hasn't been validated yet.
     */
    suspend fun validateIfNeeded(campaignId: String, testerId: String) {
        val campaignType = standardPrefs.getString(SDKConfig.KEY_CAMPAIGN_TYPE, "testing")
        if (campaignType != "growth") {
            Log.d(TAG, "Not a growth campaign (type=$campaignType), skipping validation")
            return
        }

        if (standardPrefs.getBoolean(SDKConfig.KEY_GROWTH_VALIDATED, false)) {
            val status = standardPrefs.getString(SDKConfig.KEY_GROWTH_VALIDATION_STATUS, "unknown")
            Log.d(TAG, "Growth install already validated (status=$status)")
            _validationState.value = when (status) {
                "validated" -> GrowthValidationState.Validated("")
                "queued" -> GrowthValidationState.Queued("previously_queued")
                "rejected" -> GrowthValidationState.Rejected("previously_rejected")
                else -> GrowthValidationState.Idle
            }
            return
        }

        performValidation(campaignId, testerId)
    }

    /**
     * Perform the full validation flow.
     */
    suspend fun performValidation(campaignId: String, testerId: String) {
        if (_validationState.value is GrowthValidationState.InProgress) {
            Log.d(TAG, "Validation already in progress")
            return
        }

        _validationState.value = GrowthValidationState.InProgress
        Log.i(TAG, "Starting growth install validation for campaign=$campaignId")

        try {
            // 1. Capture referrer data
            val referrerData = referrerManager.captureReferrerFromPlayAPI()
            Log.d(TAG, "Referrer data: ${referrerData != null}")

            // 2. Generate integrity token
            val nonce = integrityManager.generateNonce(campaignId, testerId)
            val integrityToken = integrityManager.requestIntegrityToken(nonce)
            Log.d(TAG, "Integrity token: ${integrityToken != null}")

            // 3. Get or generate idempotency key (from encrypted prefs for persistence)
            val idempotencyKey = getOrCreateIdempotencyKey(campaignId)

            // 4. Build payload
            val payload = hashMapOf<String, Any>(
                "campaignId" to campaignId,
                "referrerClickTimestampSeconds" to (referrerData?.clickTimestampSeconds ?: 0L),
                "installBeginTimestampSeconds" to (referrerData?.installBeginTimestampSeconds ?: 0L),
                "referrerUrl" to (referrerData?.referrerUrl ?: ""),
                "integrityToken" to (integrityToken ?: ""),
                "idempotencyKey" to idempotencyKey
            )

            // Include client fraud signal if present
            referrerData?.clientFraudSignal?.let {
                payload["clientFraudSignal"] = it
            }

            // Store payload for worker retry
            storePayloadForRetry(campaignId, payload)

            // 5. Call Cloud Function
            val result = functions.getHttpsCallable("routeInstallValidation")
                .call(payload)
                .await()

            // 6. Parse result
            val data = result.getData() as? Map<String, Any>
            val status = data?.get("status") as? String ?: "unknown"

            when (status) {
                "validated" -> {
                    val installId = (data?.get("result") as? Map<*, *>)?.get("installId") as? String ?: ""
                    markValidated("validated")
                    _validationState.value = GrowthValidationState.Validated(installId)
                    Log.i(TAG, "Install validated: $installId")
                }
                "queued" -> {
                    val reason = data?.get("reason") as? String ?: "unknown"
                    markValidated("queued")
                    _validationState.value = GrowthValidationState.Queued(reason)
                    Log.i(TAG, "Install queued: $reason")
                }
                "duplicate" -> {
                    markValidated("validated")
                    _validationState.value = GrowthValidationState.Validated("")
                    Log.i(TAG, "Install already validated (duplicate)")
                }
                else -> {
                    val reason = data?.get("reason") as? String ?: status
                    markValidated("rejected")
                    _validationState.value = GrowthValidationState.Rejected(reason)
                    Log.w(TAG, "Install rejected: $reason")
                }
            }

            // Clear stored payload on success
            clearStoredPayload()

        } catch (e: Exception) {
            Log.e(TAG, "Validation failed, scheduling retry: ${e.message}")
            _validationState.value = GrowthValidationState.Error(
                message = e.message ?: "Unknown error",
                willRetry = true
            )
            // Schedule WorkManager retry
            com.betaflox.sdk.network.GrowthValidationWorker.enqueue(context, campaignId)
        }
    }

    /**
     * Perform validation using a stored payload (for WorkManager retry).
     */
    suspend fun performValidationFromStoredPayload(): GrowthValidationResult {
        val payloadJson = encryptedPrefs.getString(SDKConfig.KEY_GROWTH_VALIDATION_PAYLOAD, null)
            ?: return GrowthValidationResult.Error("No stored payload")

        return try {
            val json = JSONObject(payloadJson)
            val payload = hashMapOf<String, Any>()
            json.keys().forEach { key ->
                payload[key] = json.get(key)
            }

            val result = functions.getHttpsCallable("routeInstallValidation")
                .call(payload)
                .await()

            val data = result.getData() as? Map<String, Any>
            val status = data?.get("status") as? String ?: "unknown"

            when (status) {
                "validated", "duplicate" -> {
                    markValidated("validated")
                    clearStoredPayload()
                    _validationState.value = GrowthValidationState.Validated("")
                    GrowthValidationResult.Success
                }
                "queued" -> {
                    markValidated("queued")
                    clearStoredPayload()
                    _validationState.value = GrowthValidationState.Queued(data?.get("reason") as? String ?: "")
                    GrowthValidationResult.Queued
                }
                else -> {
                    val reason = data?.get("reason") as? String ?: status
                    markValidated("rejected")
                    clearStoredPayload()
                    _validationState.value = GrowthValidationState.Rejected(reason)
                    GrowthValidationResult.Rejected(reason)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Retry validation failed: ${e.message}")
            GrowthValidationResult.Error(e.message ?: "Unknown error")
        }
    }

    // ---- Private helpers ----

    private fun getOrCreateIdempotencyKey(campaignId: String): String {
        val existingKey = encryptedPrefs.getString(SDKConfig.KEY_GROWTH_IDEMPOTENCY_KEY, null)
        if (!existingKey.isNullOrBlank()) return existingKey

        val key = "${campaignId}_${UUID.randomUUID()}"
        encryptedPrefs.edit().putString(SDKConfig.KEY_GROWTH_IDEMPOTENCY_KEY, key).apply()
        return key
    }

    private fun storePayloadForRetry(campaignId: String, payload: Map<String, Any>) {
        val json = JSONObject(payload).toString()
        encryptedPrefs.edit()
            .putString(SDKConfig.KEY_GROWTH_VALIDATION_PAYLOAD, json)
            .apply()
        Log.d(TAG, "Stored validation payload for retry (campaign=$campaignId)")
    }

    private fun clearStoredPayload() {
        encryptedPrefs.edit()
            .remove(SDKConfig.KEY_GROWTH_VALIDATION_PAYLOAD)
            .apply()
    }

    private fun markValidated(status: String) {
        standardPrefs.edit()
            .putBoolean(SDKConfig.KEY_GROWTH_VALIDATED, true)
            .putString(SDKConfig.KEY_GROWTH_VALIDATION_STATUS, status)
            .apply()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                ENCRYPTED_PREFS_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, using fallback: ${e.message}")
            // Security degradation — log for monitoring
            Log.w(TAG, "SECURITY_DEGRADED: Growth idempotency keys stored unencrypted")
            context.getSharedPreferences("growth_fallback", Context.MODE_PRIVATE)
        }
    }
}

/**
 * Validation state exposed via StateFlow.
 */
sealed class GrowthValidationState {
    object Idle : GrowthValidationState()
    object InProgress : GrowthValidationState()
    data class Validated(val installId: String) : GrowthValidationState()
    data class Queued(val reason: String) : GrowthValidationState()
    data class Rejected(val reason: String) : GrowthValidationState()
    data class Error(val message: String, val willRetry: Boolean) : GrowthValidationState()
}

/**
 * Result type for WorkManager retry flow.
 */
sealed class GrowthValidationResult {
    object Success : GrowthValidationResult()
    object Queued : GrowthValidationResult()
    data class Rejected(val reason: String) : GrowthValidationResult()
    data class Error(val message: String) : GrowthValidationResult()
}
