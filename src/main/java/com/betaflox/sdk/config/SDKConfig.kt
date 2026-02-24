package com.betaflox.sdk.config

/**
 * SDK configuration data class.
 * Holds all the configuration needed for the SDK to operate.
 */
data class SDKConfig(
    val apiKey: String,
    val campaignId: String,
    var testerId: String? = null,
    val enableAntifraud: Boolean = true,
    val syncIntervalMs: Long = 30_000L, // 30 seconds
    val maxEventsPerBatch: Int = 50
) {
    companion object {
        const val PREFS_NAME = "betaflox_sdk_prefs"
        const val KEY_TESTER_ID = "tester_id"
        const val KEY_CAMPAIGN_ID = "campaign_id"
        const val KEY_FIRST_INSTALL_TIME = "first_install_time"
        const val KEY_DEVICE_HASH = "device_hash"
        const val KEY_SESSION_START = "session_start"
        const val KEY_CAMPAIGN_START_DATE = "campaign_start_date"
        const val KEY_TRACKING_ENABLED = "tracking_enabled"
        
        // Secure Binding Keys
        const val KEY_BINDING_STATUS = "binding_status" // "active", "pending", "none"
        const val KEY_BOUND_TESTER_ID = "bound_tester_id"
        const val KEY_BOUND_CAMPAIGN_ID = "bound_campaign_id"
        const val KEY_JOIN_TOKEN = "join_token"
    }
    
    fun isConfigured(): Boolean {
        return apiKey.isNotBlank() && campaignId.isNotBlank()
    }
}
