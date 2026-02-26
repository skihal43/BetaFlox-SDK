package com.betaflox.sdk

import android.app.Application
import android.content.Context
import android.util.Log
import com.betaflox.sdk.config.FirebaseConfig
import com.betaflox.sdk.config.SDKConfig
import com.betaflox.sdk.fraud.DeviceFingerprint
import com.betaflox.sdk.fraud.RapidSwitchingDetector
import com.betaflox.sdk.fraud.ReinstallDetector
import com.betaflox.sdk.fraud.RootDetector
import com.betaflox.sdk.network.EventSyncWorker
import com.betaflox.sdk.network.FirebaseSync
import com.betaflox.sdk.signals.DeviceSignalCollector
import com.betaflox.sdk.signals.PlayIntegrityChecker
import com.betaflox.sdk.signals.SignalUploader
import com.betaflox.sdk.signals.SessionVerdict
import com.betaflox.sdk.tracking.AppLifecycleTracker
import com.betaflox.sdk.tracking.EventLogger
import com.betaflox.sdk.tracking.HeartbeatManager
import com.betaflox.sdk.tracking.SessionManager
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.betaflox.sdk.binding.JoinTokenManager
import com.google.firebase.functions.FirebaseFunctions
import android.widget.Toast
import android.content.Intent

/**
 * BetaFlox SDK main entry point.
 * 
 * Initialize the SDK in your Application class:
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         BetaFloxSDK.initialize(this, "your-api-key", "campaign-id")
 *         BetaFloxSDK.setTesterId("tester-user-id")
 *     }
 * }
 * ```
 */
object BetaFloxSDK {
    
    private const val TAG = "BetaFloxSDK"
    const val SDK_VERSION = "1.0.7"
    
    private var isInitialized = false
    private var trackingEnabled = true
    private lateinit var config: SDKConfig
    private lateinit var appContext: Context
    
    // Components
    private lateinit var sessionManager: SessionManager
    private lateinit var joinTokenManager: JoinTokenManager
    private lateinit var eventLogger: EventLogger
    private lateinit var lifecycleTracker: AppLifecycleTracker
    private lateinit var heartbeatManager: HeartbeatManager
    private lateinit var firebaseSync: FirebaseSync
    private lateinit var auth: FirebaseAuth
    
    // Signal collection (server-side risk scoring)
    private lateinit var signalCollector: DeviceSignalCollector
    private lateinit var signalUploader: SignalUploader
    private lateinit var playIntegrityChecker: PlayIntegrityChecker
    private var currentSessionId: String? = null
    
    // Fraud signal collectors (legacy - kept for device hash and reinstall detection)
    private lateinit var rootDetector: RootDetector
    private lateinit var deviceFingerprint: DeviceFingerprint
    private lateinit var reinstallDetector: ReinstallDetector
    private lateinit var rapidSwitchingDetector: RapidSwitchingDetector
    
    /**
     * Get the SDK version.
     */
    @JvmStatic
    fun getVersion(): String = SDK_VERSION
    
    /**
     * Check if the SDK has been initialized.
     */
    @JvmStatic
    fun isInitialized(): Boolean = isInitialized
    
    /**
     * Get the current tester ID.
     */
    @JvmStatic
    fun getTesterId(): String? {
        return if (isInitialized && ::config.isInitialized) {
            config.testerId
        } else {
            null
        }
    }
    
    /**
     * Initialize the SDK with required parameters.
     * 
     * @param context Application context
     * @param apiKey Your BetaFlox API key
     * @param campaignId The campaign ID to track
     */
    @JvmStatic
    fun initialize(context: Context, apiKey: String, campaignId: String) {
        if (isInitialized) {
            Log.w(TAG, "SDK already initialized")
            return
        }
        
        require(apiKey.isNotBlank()) { "API key cannot be blank" }
        require(campaignId.isNotBlank()) { "Campaign ID cannot be blank" }
        
        appContext = context.applicationContext
        config = SDKConfig(apiKey = apiKey, campaignId = campaignId)
        
        // Restore testerId from SharedPreferences if previously set during binding
        val prefs = appContext.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val savedTesterId = prefs.getString(SDKConfig.KEY_TESTER_ID, null)
            ?: prefs.getString(SDKConfig.KEY_BOUND_TESTER_ID, null)
        if (!savedTesterId.isNullOrBlank()) {
            config.testerId = savedTesterId
            Log.i(TAG, "Restored tester ID from preferences: $savedTesterId")
        }
        
        // Initialize Firebase with SDK's own configuration
        val firebaseApp = FirebaseConfig.initialize(appContext)
        if (firebaseApp == null) {
            Log.w(TAG, "Firebase initialization failed - sync features will be disabled")
            return
        }

        // Initialize Auth and Firestore for SDK
        auth = FirebaseAuth.getInstance(firebaseApp)
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(firebaseApp)
        
        // Initialize signal collectors (server-side risk scoring)
        signalCollector = DeviceSignalCollector(appContext)
        signalUploader = SignalUploader(firestore)
        playIntegrityChecker = PlayIntegrityChecker(appContext)
        
        // Initialize fraud detectors FIRST (deviceFingerprint is needed in the auth callback below)
        rootDetector = RootDetector()
        deviceFingerprint = DeviceFingerprint(appContext)
        reinstallDetector = ReinstallDetector(appContext)
        rapidSwitchingDetector = RapidSwitchingDetector(appContext)
        
        // Auto sign-in anonymously to enable Firestore writes (and reads for resolution)
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { 
                    Log.i(TAG, "SDK signed in anonymously: ${it.user?.uid}")
                    // Attempt to resolve identity
                    resolveTesterIdFromDeviceHash(firestore, deviceFingerprint.getHash())
                }
                .addOnFailureListener { 
                    Log.e(TAG, "SDK anonymous sign-in failed", it) 
                }
        } else {
            Log.d(TAG, "SDK already signed in: ${auth.currentUser?.uid}")
            // Attempt to resolve identity
            resolveTesterIdFromDeviceHash(firestore, deviceFingerprint.getHash())
        }
        
        // Initialize Functions & JoinTokenManager
        val functions = FirebaseFunctions.getInstance(firebaseApp)
        joinTokenManager = JoinTokenManager(appContext, config, deviceFingerprint, functions)

        
        
        // Ensure campaign start date is set (needed for daily check-in day calculation)
        // If it wasn't set during initializeWithToken, set it now on first initialize
        if (prefs.getLong(SDKConfig.KEY_CAMPAIGN_START_DATE, 0L) == 0L) {
            prefs.edit().putLong(SDKConfig.KEY_CAMPAIGN_START_DATE, System.currentTimeMillis()).apply()
            Log.i(TAG, "Set campaign start date during initialize()")
        }
        
        // Restore tracking enabled state from preferences
        trackingEnabled = prefs.getBoolean(SDKConfig.KEY_TRACKING_ENABLED, true)
        
        
        // Initialize tracking components
        eventLogger = EventLogger(appContext, config)
        sessionManager = SessionManager(appContext, config, eventLogger, rapidSwitchingDetector)
        heartbeatManager = HeartbeatManager(config, eventLogger, sessionManager)
        firebaseSync = FirebaseSync(config, eventLogger, firebaseApp, appContext)
        
        // Initialize lifecycle tracker
        lifecycleTracker = AppLifecycleTracker(sessionManager, heartbeatManager, firebaseSync)
        
        // Register lifecycle callbacks
        if (appContext is Application) {
            (appContext as Application).registerActivityLifecycleCallbacks(lifecycleTracker)
        }
        
        // Record first install time if not already set
        reinstallDetector.recordInstallIfNeeded()
        
        // Schedule WorkManager for reliable background sync
        if (trackingEnabled) {
            EventSyncWorker.schedule(appContext)
        }
        
        isInitialized = true
        
        // Start syncing events to Firebase immediately
        firebaseSync.startSync()
        
        Log.i(TAG, "SDK initialized for campaign: $campaignId (tracking: $trackingEnabled)")
    }
    
    /**
     * Initialize the SDK with a join token (from deep link).
     * This automates the binding process on first launch.
     */
    @JvmStatic
    fun initializeWithToken(context: Context, apiKey: String, token: String) {
        try {
            val parts = token.split(".")
            if (parts.size >= 3) {
                val campaignId = parts[1]
                val testerId = parts[2]
                
                initialize(context, apiKey, campaignId)
                
                // Save campaign ID and start date immediately for subsequent launches & daily check-in
                val tokenPrefs = appContext.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
                val editor = tokenPrefs.edit()
                    .putString(SDKConfig.KEY_BOUND_CAMPAIGN_ID, campaignId)
                // Only set campaign start date if not already set (don't overwrite on repeat launches)
                if (tokenPrefs.getLong(SDKConfig.KEY_CAMPAIGN_START_DATE, 0L) == 0L) {
                    editor.putLong(SDKConfig.KEY_CAMPAIGN_START_DATE, System.currentTimeMillis())
                }
                editor.apply()
                Log.i(TAG, "Saved campaign ID ($campaignId) and start date to preferences")
                
                // Set tester ID immediately from the token (don't wait for async validation)
                if (testerId.isNotBlank()) {
                    Log.i(TAG, "Setting tester ID from token: $testerId")
                    setTesterId(testerId)
                }
                
                // Still validate the token async to create device_mappings and bindings
                handleDeepLinkString(token)
            } else {
                 Log.e(TAG, "Invalid token format in initializeWithToken")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing with token", e)
        }
    }

    private fun handleDeepLinkString(token: String) {
         if (!isInitialized) return
         
         Log.d(TAG, "Validating join token: $token")
         joinTokenManager.validateToken(token)
            .addOnSuccessListener { testerId ->
                Log.i(TAG, "Successfully bound to tester: $testerId")
                config.testerId = testerId
                val prefs = appContext.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                    .putString(SDKConfig.KEY_TESTER_ID, testerId)
                // Only set campaign start date if not already set (don't reset on subsequent launches)
                if (prefs.getLong(SDKConfig.KEY_CAMPAIGN_START_DATE, 0L) == 0L) {
                    editor.putLong(SDKConfig.KEY_CAMPAIGN_START_DATE, System.currentTimeMillis())
                }
                editor.apply()
                Toast.makeText(appContext, "Device successfully bound!", Toast.LENGTH_LONG).show()
                firebaseSync.startSync()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to bind device", e)
                Toast.makeText(appContext, "Binding failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Handle a deep link intent to extract and validate a join token.
     * This should be called from the main Activity's onCreate or onNewIntent.
     */
    @JvmStatic
    fun handleDeepLink(intent: Intent) {
        if (!isInitialized) {
            Log.w(TAG, "SDK not initialized. Call initialize() first.")
            return
        }
        
        val token = joinTokenManager.extractTokenFromIntent(intent)
        if (token != null) {
             handleDeepLinkString(token)
        }
    }

    /**
     * Check if the device is securely bound to the current campaign.
     * Returns true if server has validated the binding.
     */
    @JvmStatic
    fun isDeviceBound(): Boolean {
        return if (isInitialized) {
            joinTokenManager.isDeviceBound()
        } else {
            false
        }
    }
    
    /**
     * Set the tester ID for this session.
     * This should be called after the user authenticates in your app.
     * 
     * @param testerId The authenticated tester's user ID
     */
    @JvmStatic
    fun setTesterId(testerId: String) {
        checkInitialized()
        require(testerId.isNotBlank()) { "Tester ID cannot be blank" }
        
        config.testerId = testerId
        
        // Store tester ID and device hash
        val prefs = appContext.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(SDKConfig.KEY_TESTER_ID, testerId)
            .apply()
        
        Log.i(TAG, "Tester ID set: $testerId")
        
        // Start syncing events to Firebase
        firebaseSync.startSync()
    }

    /**
     * Manually trigger daily check-in.
     * Called when tester has met daily usage requirement.
     * 
     * @param dayIndex The campaign day (0-13)
     */
    @JvmStatic
    fun completeDailyTask(dayIndex: Int) {
        checkInitialized()
        eventLogger.logDailyCheckin(dayIndex)
        Log.d(TAG, "Manual daily task completion triggered for day $dayIndex")
    }
    
    /**
     * Get the unique device fingerprint hash.
     * Use this to verify device uniqueness.
     */
    @JvmStatic
    fun getDeviceHash(): String {
        checkInitialized()
        return deviceFingerprint.getHash()
    }
    
    /**
     * Get current fraud detection signals (for local logging/debugging only).
     * 
     * IMPORTANT: This does NOT return verdicts. All fraud verdicts are computed server-side.
     * Use getSessionVerdict() to check the server-computed verdict.
     * 
     * @return Map of local fraud signals
     */
    @JvmStatic
    @Deprecated(
        message = "Use collectAndUploadSignals() for server-side risk scoring",
        replaceWith = ReplaceWith("collectAndUploadSignals()")
    )
    fun getFraudFlags(): Map<String, Boolean> {
        checkInitialized()
        // NOTE: isEmulator is NO LONGER returned - verdicts are server-side only
        return mapOf(
            "isRooted" to rootDetector.isRooted(),
            "isReinstall" to reinstallDetector.isReinstall(),
            "rapidSwitching" to rapidSwitchingDetector.isRapidSwitching()
        )
    }
    
    /**
     * Collect all device signals and upload to Firebase for server-side risk scoring.
     * 
     * This is the primary method for fraud detection. The SDK collects signals
     * and uploads them to Firestore. A Cloud Function then computes the risk score
     * and verdict server-side.
     * 
     * @return The session ID for this signal collection, or null on failure
     */
    @JvmStatic
    fun collectAndUploadSignals(): String? {
        checkInitialized()
        
        val testerId = config.testerId
        if (testerId.isNullOrBlank()) {
            Log.w(TAG, "Cannot upload signals: tester ID not set")
            return null
        }
        
        val signals = signalCollector.collectSignals()
        currentSessionId = signals.sessionSignals.sessionId
        
        // Upload signals asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sessionId = signalUploader.uploadSignals(
                    userId = testerId,
                    authUid = auth.currentUser?.uid,
                    appId = appContext.packageName,
                    campaignId = config.campaignId,
                    signals = signals
                )
                
                if (sessionId != null) {
                    Log.i(TAG, "Signals uploaded to session: $sessionId")
                    
                    // Check Play Integrity and update session
                    if (playIntegrityChecker.isAvailable()) {
                        val integritySignals = playIntegrityChecker.checkIntegrity(
                            nonce = java.util.UUID.randomUUID().toString()
                        )
                        signalUploader.updateIntegritySignals(sessionId, integritySignals)
                    }
                } else {
                    Log.e(TAG, "Failed to upload signals")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Signal upload error: ${e.message}")
            }
        }
        
        return currentSessionId
    }
    
    /**
     * Get the current session's verdict from the server.
     * 
     * The verdict is computed by a Cloud Function after signals are uploaded.
     * This method reads the server-computed result.
     * 
     * @param sessionId The session ID returned by collectAndUploadSignals()
     * @param callback Callback with the verdict, or null if not yet computed
     */
    @JvmStatic
    fun getSessionVerdict(sessionId: String, callback: (SessionVerdict?) -> Unit) {
        checkInitialized()
        
        CoroutineScope(Dispatchers.IO).launch {
            val verdict = signalUploader.getSessionVerdict(sessionId)
            CoroutineScope(Dispatchers.Main).launch {
                callback(verdict)
            }
        }
    }
    
    /**
     * Get a summary of device signals for debugging.
     * Does NOT include verdicts.
     */
    @JvmStatic
    fun getSignalSummary(): Map<String, Any> {
        checkInitialized()
        return signalCollector.getSignalSummary()
    }
    
    /**
     * Get the current session duration in seconds.
     */
    @JvmStatic
    fun getCurrentSessionDuration(): Long {
        checkInitialized()
        return sessionManager.getCurrentSessionDuration()
    }
    
    /**
     * Enable or disable tracking (GDPR compliance).
     * When disabled, no events are logged and no data is synced.
     * 
     * @param enabled true to enable tracking, false to disable
     */
    @JvmStatic
    fun setTrackingEnabled(enabled: Boolean) {
        checkInitialized()
        trackingEnabled = enabled
        
        // Persist the preference
        val prefs = appContext.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(SDKConfig.KEY_TRACKING_ENABLED, enabled).apply()
        
        if (!enabled) {
            heartbeatManager.stop()
            firebaseSync.stopSync()
            Log.i(TAG, "Tracking disabled")
        } else {
            heartbeatManager.start()
            firebaseSync.startSync()
            Log.i(TAG, "Tracking enabled")
        }
    }
    
    /**
     * Check if tracking is currently enabled.
     */
    @JvmStatic
    fun isTrackingEnabled(): Boolean = trackingEnabled
    
    /**
     * Set the campaign start date for proper day calculation.
     * This should be called when the tester joins a campaign.
     * 
     * @param timestamp The campaign start date in milliseconds since epoch
     */
    @JvmStatic
    fun setCampaignStartDate(timestamp: Long) {
        checkInitialized()
        require(timestamp > 0) { "Campaign start date must be a positive timestamp" }
        
        val prefs = appContext.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(SDKConfig.KEY_CAMPAIGN_START_DATE, timestamp).apply()
        Log.i(TAG, "Campaign start date set: $timestamp")
    }
    
    /**
     * Get the campaign start date if set.
     * 
     * @return The campaign start timestamp, or 0 if not set
     */
    @JvmStatic
    fun getCampaignStartDate(): Long {
        checkInitialized()
        val prefs = appContext.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(SDKConfig.KEY_CAMPAIGN_START_DATE, 0L)
    }
    
    /**
     * Export all user data collected by the SDK (GDPR compliance).
     * 
     * @return Map containing all user data
     */
    @JvmStatic
    fun exportUserData(): Map<String, Any?> {
        checkInitialized()
        val prefs = appContext.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
        
        return mapOf(
            "testerId" to config.testerId,
            "campaignId" to config.campaignId,
            "deviceHash" to prefs.getString(SDKConfig.KEY_DEVICE_HASH, null),
            "firstInstallTime" to prefs.getLong(SDKConfig.KEY_FIRST_INSTALL_TIME, 0L),
            "campaignStartDate" to prefs.getLong(SDKConfig.KEY_CAMPAIGN_START_DATE, 0L),
            "totalSessionDuration" to getCurrentSessionDuration(),
            "fraudFlags" to getFraudFlags(),
            "pendingEventsCount" to firebaseSync.getPendingCount()
        )
    }
    
    /**
     * Delete all user data collected by the SDK (GDPR compliance).
     * This will clear all local data and stop tracking.
     */
    @JvmStatic
    fun deleteUserData() {
        checkInitialized()
        
        // Stop all tracking
        setTrackingEnabled(false)
        
        // Clear all preferences
        val prefs = appContext.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        
        // Clear event queue
        eventLogger.clearEvents()
        
        // Reset config
        config.testerId = null
        
        Log.i(TAG, "All user data deleted")
    }
    
    /**
     * Force sync pending events to Firebase.
     * Normally events are synced automatically.
     */
    @JvmStatic
    fun syncNow() {
        checkInitialized()
        firebaseSync.syncNow()
    }
    
    /**
     * Stop the SDK and clean up resources.
     */
    @JvmStatic
    fun shutdown() {
        if (!isInitialized) return
        
        // Unregister lifecycle callbacks
        if (appContext is Application) {
            (appContext as Application).unregisterActivityLifecycleCallbacks(lifecycleTracker)
        }
        
        // Stop syncing
        firebaseSync.stopSync()
        
        // End current session
        sessionManager.endSession()
        
        isInitialized = false
        Log.i(TAG, "SDK shutdown complete")
    }
    
    // Internal methods
    
    internal fun getConfig(): SDKConfig {
        checkInitialized()
        return config
    }
    
    internal fun getPackageName(): String {
        return appContext.packageName
    }
    
    private fun checkInitialized() {
        check(isInitialized) { "BetaFloxSDK not initialized. Call initialize() first." }
    }
    
    private fun resolveTesterIdFromDeviceHash(firestore: com.google.firebase.firestore.FirebaseFirestore, deviceHash: String) {
        // If tester ID is already set, don't overwrite it
        if (config.testerId != null && config.testerId!!.isNotBlank()) {
            return
        }

        Log.d(TAG, "Attempting to resolve tester ID from device hash: ${deviceHash.take(8)}...")
        
        firestore.collection("device_mappings").document(deviceHash).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val testerId = document.getString("testerId")
                    if (!testerId.isNullOrBlank()) {
                        Log.i(TAG, "Auto-resolved tester ID: $testerId")
                        setTesterId(testerId)
                    } else {
                         Log.d(TAG, "Device mapping found but no testerId")
                    }
                } else {
                    Log.d(TAG, "No device mapping found for this device")
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to resolve tester ID: ${e.message}")
            }
    }
}
