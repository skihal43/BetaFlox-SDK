package com.betaflox.sdk.binding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.betaflox.sdk.config.SDKConfig
import com.betaflox.sdk.fraud.DeviceFingerprint
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException

/**
 * Manages the secure binding process using Join Tokens.
 * Handles deep link parsing and server-side validation.
 */
internal class JoinTokenManager(
    private val context: Context,
    private val config: SDKConfig,
    private val deviceFingerprint: DeviceFingerprint,
    private val functions: FirebaseFunctions
) {

    companion object {
        private const val TAG = "JoinTokenManager"
        private const val DEEP_LINK_PARAM = "token"
    }

    private val prefs = context.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Check if this device is securely bound to the current campaign.
     */
    fun isDeviceBound(): Boolean {
        val status = prefs.getString(SDKConfig.KEY_BINDING_STATUS, "none")
        val boundCampaignId = prefs.getString(SDKConfig.KEY_BOUND_CAMPAIGN_ID, null)
        val currentCampaignId = config.campaignId
        
        return status == "active" && boundCampaignId == currentCampaignId
    }

    /**
     * Extract join token from an intent (deep link).
     * Returns null if no token found.
     */
    fun extractTokenFromIntent(intent: Intent?): String? {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return null
        
        val data: Uri? = intent.data
        if (data == null) return null
        
        // Handle https://betaflox.app/join?token=...
        return data.getQueryParameter(DEEP_LINK_PARAM)
    }

    /**
     * Validate the join token with the server.
     * 
     * @param token The join token string
     * @return Task with the validation result (testerId)
     */
    fun validateToken(token: String): Task<String> {
        val fingerprint = deviceFingerprint.getHash()
        
        val data = hashMapOf(
            "token" to token,
            "deviceFingerprint" to fingerprint
        )

        Log.d(TAG, "Validating join token with server...")

        return functions
            .getHttpsCallable("validateJoinToken")
            .call(data)
            .continueWith { task ->
                if (!task.isSuccessful) {
                    val e = task.exception
                    Log.e(TAG, "Token validation failed", e)
                    if (e is FirebaseFunctionsException) {
                        Log.e(TAG, "Code: ${e.code}, Details: ${e.details}")
                    }
                    throw e ?: Exception("Unknown error")
                }

                val result = task.result?.getData() as? Map<String, Any>
                    ?: throw Exception("Invalid response format")

                val success = result["bindingSuccess"] as? Boolean ?: false
                if (success) {
                    val testerId = result["testerId"] as? String
                    val campaignId = result["campaignId"] as? String
                    
                    if (testerId != null && campaignId != null) {
                        saveBinding(testerId, campaignId, token)
                        return@continueWith testerId
                    }
                }
                
                throw Exception("Binding failed on server")
            }
    }

    private fun saveBinding(testerId: String, campaignId: String, token: String) {
        prefs.edit()
            .putString(SDKConfig.KEY_BINDING_STATUS, "active")
            .putString(SDKConfig.KEY_BOUND_TESTER_ID, testerId)
            .putString(SDKConfig.KEY_BOUND_CAMPAIGN_ID, campaignId)
            .putString(SDKConfig.KEY_JOIN_TOKEN, token)
            .apply()
            
        Log.i(TAG, "Device successfully bound to tester $testerId for campaign $campaignId")
    }
}
