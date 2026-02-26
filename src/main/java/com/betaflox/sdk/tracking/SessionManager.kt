package com.betaflox.sdk.tracking

import android.content.Context
import android.util.Log
import com.betaflox.sdk.BetaFloxSDK
import com.betaflox.sdk.config.SDKConfig
import com.betaflox.sdk.fraud.RapidSwitchingDetector

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages session lifecycle including start, pause, resume, and end.
 * Calculates session duration and logs session events.
 */
internal class SessionManager(
    private val context: Context,
    private val config: SDKConfig,
    private val eventLogger: EventLogger,
    private val rapidSwitchingDetector: RapidSwitchingDetector
) {
    companion object {
        private const val TAG = "SessionManager"
        const val DAILY_MIN_DURATION = 180L // 3 minutes minimum for daily completion
        private const val KEY_LAST_CHECKIN_TIME = "last_checkin_time"
        private const val KEY_DAILY_DURATION = "daily_accumulated_duration"
        private const val KEY_DAILY_DURATION_DATE = "daily_duration_date"
        private const val CHECKIN_COOLDOWN_MS = 22 * 60 * 60 * 1000L // 22 hours
    }
    
    private val prefs = context.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
    
    private var sessionStartTime: Long = 0
    private var dailyTotalDuration: Long = 0
    private var currentLaunchDuration: Long = 0
    private var isSessionActive = false
    private var lastCheckinTimestamp: Long = 0L
    
    init {
        lastCheckinTimestamp = prefs.getLong(KEY_LAST_CHECKIN_TIME, 0L)
        restoreDailyDuration()
    }
    
    /**
     * Restore the accumulated daily session duration from SharedPreferences.
     * If the stored date matches today, restore the duration; otherwise reset to 0.
     */
    private fun restoreDailyDuration() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val savedDate = prefs.getString(KEY_DAILY_DURATION_DATE, "") ?: ""
        if (today == savedDate) {
            dailyTotalDuration = prefs.getLong(KEY_DAILY_DURATION, 0L)
            Log.d(TAG, "Restored daily duration: ${dailyTotalDuration}s for $today")
        } else {
            dailyTotalDuration = 0L
            // Reset stored duration for the new day
            prefs.edit()
                .putLong(KEY_DAILY_DURATION, 0L)
                .putString(KEY_DAILY_DURATION_DATE, today)
                .apply()
            Log.d(TAG, "New day detected ($today), reset daily duration")
        }
    }
    
    /**
     * Persist the current daily accumulated duration to SharedPreferences.
     */
    private fun persistDailyDuration() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        prefs.edit()
            .putLong(KEY_DAILY_DURATION, dailyTotalDuration)
            .putString(KEY_DAILY_DURATION_DATE, today)
            .apply()
        Log.d(TAG, "Persisted daily duration: ${dailyTotalDuration}s for $today")
    }
    
    /**
     * Start a new session when app comes to foreground.
     */
    fun startSession() {
        if (isSessionActive) {
            Log.d(TAG, "Session already active")
            return
        }
        
        sessionStartTime = System.currentTimeMillis()
        isSessionActive = true
        
        // Persist session start time
        prefs.edit().putLong(SDKConfig.KEY_SESSION_START, sessionStartTime).apply()
        
        // Check for rapid switching
        if (rapidSwitchingDetector.recordSessionStart()) {
            Log.w(TAG, "Rapid switching detected!")
        }
        
        // Log app_open event
        eventLogger.logEvent(
            eventType = "app_open",
            data = mapOf(
                "timestamp" to sessionStartTime,
                "packageName" to context.packageName
            )
        )
        
        Log.d(TAG, "Session started at $sessionStartTime")
    }
    
    /**
     * Pause session when app goes to background.
     * Records the session duration.
     */
    fun pauseSession() {
        if (!isSessionActive) {
            Log.d(TAG, "No active session to pause")
            return
        }
        
        val currentTime = System.currentTimeMillis()
        val sessionDuration = (currentTime - sessionStartTime) / 1000 // Convert to seconds
        dailyTotalDuration += sessionDuration
        currentLaunchDuration += sessionDuration
        isSessionActive = false
        
        // Check for quick session
        val sessionDurationMs = currentTime - sessionStartTime
        if (rapidSwitchingDetector.recordSessionEnd(sessionDurationMs)) {
            Log.w(TAG, "Suspiciously short session detected")
        }
        
        // Log app_close event with duration
        eventLogger.logEvent(
            eventType = "app_close",
            data = mapOf(
                "timestamp" to currentTime,
                "sessionDuration" to sessionDuration,
                "packageName" to context.packageName
            )
        )
        
        // Persist daily accumulated duration
        persistDailyDuration()
        
        // Check for daily completion
        checkDailyCompletion()
        
        // Log session_duration event
        eventLogger.logEvent(
            eventType = "session_duration",
            data = mapOf(
                "timestamp" to currentTime,
                "sessionDuration" to sessionDuration,
                "totalDuration" to dailyTotalDuration,
                "packageName" to context.packageName
            )
        )
        
        Log.d(TAG, "Session paused. Duration: ${sessionDuration}s, Daily: ${dailyTotalDuration}s")
    }
    
    /**
     * End the session completely (e.g., when SDK shuts down).
     */
    fun endSession() {
        if (isSessionActive) {
            pauseSession()
        }
        
        // Clear persisted session data
        prefs.edit().remove(SDKConfig.KEY_SESSION_START).apply()
        
        Log.d(TAG, "Session ended. Daily duration: ${dailyTotalDuration}s, Current launch: ${currentLaunchDuration}s")
    }
    
    /**
     * Get the current session duration in seconds for the current application launch.
     * Includes time from the current active session if one is running.
     */
    fun getCurrentSessionDuration(): Long {
        return if (isSessionActive) {
            val currentSegment = (System.currentTimeMillis() - sessionStartTime) / 1000
            currentLaunchDuration + currentSegment
        } else {
            currentLaunchDuration
        }
    }

    /**
     * Get the daily accumulated session duration in seconds.
     */
    private fun getDailySessionDuration(): Long {
        return if (isSessionActive) {
            val currentSegment = (System.currentTimeMillis() - sessionStartTime) / 1000
            dailyTotalDuration + currentSegment
        } else {
            dailyTotalDuration
        }
    }
    
    /**
     * Check if a session is currently active.
     */
    fun isSessionActive(): Boolean = isSessionActive
    
    /**
     * Restore session state after process restart.
     */
    fun restoreSession() {
        val savedStartTime = prefs.getLong(SDKConfig.KEY_SESSION_START, 0)
        if (savedStartTime > 0) {
            // There was an active session that didn't end properly
            Log.w(TAG, "Restoring interrupted session from $savedStartTime")
            sessionStartTime = savedStartTime
            isSessionActive = true
        }
    }

    /**
     * Called periodically by HeartbeatManager to check daily completion
     * while the session is still active (no need to background the app).
     */
    fun checkDailyCompletionIfActive() {
        if (isSessionActive) {
            checkDailyCompletion()
        }
    }

    /**
     * Check if daily task is completed based on duration.
     * Uses a 22-hour cooldown instead of date-based lock to allow retries
     * if the previous check-in event was not synced to Firestore.
     */
    fun checkDailyCompletion() {
        val now = System.currentTimeMillis()
        val timeSinceLastCheckin = now - lastCheckinTimestamp
        
        if (timeSinceLastCheckin >= CHECKIN_COOLDOWN_MS && getDailySessionDuration() >= DAILY_MIN_DURATION) {
            // Calculate which day of the campaign this is
            val dayIndex = calculateCampaignDay()
            if (dayIndex in 0..13) {
                eventLogger.logDailyCheckin(dayIndex)
                lastCheckinTimestamp = now
                
                // Store in SharedPreferences for persistence
                prefs.edit().putLong(KEY_LAST_CHECKIN_TIME, now).apply()
                Log.d(TAG, "Daily check-in completed for day $dayIndex")
            }
        }
    }

    private fun calculateCampaignDay(): Int {
        val campaignStartDate = prefs.getLong(SDKConfig.KEY_CAMPAIGN_START_DATE, 0L)
        if (campaignStartDate == 0L) {
            Log.w(TAG, "Campaign start date not set, cannot calculate campaign day")
            return -1
        }
        val daysSinceStart = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(
            System.currentTimeMillis() - campaignStartDate
        )
        return daysSinceStart.toInt().coerceIn(0, 13)
    }
}
