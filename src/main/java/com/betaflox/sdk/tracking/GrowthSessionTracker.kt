package com.betaflox.sdk.tracking

import android.content.Context
import android.util.Log

/**
 * Tracks growth campaign sessions with a 2-minute minimum threshold.
 * Sessions below 2 minutes are not eligible for daily growth points.
 */
class GrowthSessionTracker(private val context: Context) {

    companion object {
        private const val TAG = "GrowthSessionTracker"
        private const val MIN_SESSION_SECONDS = 120L // 2 minutes
    }

    private var sessionStartTime: Long = 0L
    private var isTracking = false
    private var currentCampaignId: String? = null

    /**
     * Start tracking a growth session for a campaign.
     */
    fun startSession(campaignId: String) {
        if (isTracking) {
            Log.w(TAG, "Session already in progress for ${currentCampaignId}")
            return
        }
        currentCampaignId = campaignId
        sessionStartTime = System.currentTimeMillis()
        isTracking = true
        Log.d(TAG, "Growth session started for $campaignId")
    }

    /**
     * End the current session and return duration in seconds.
     * 
     * @return Session duration in seconds, or -1 if session was invalid
     */
    fun endSession(): Long {
        if (!isTracking) {
            Log.w(TAG, "No session in progress")
            return -1
        }

        val durationMs = System.currentTimeMillis() - sessionStartTime
        val durationSec = durationMs / 1000

        isTracking = false
        Log.d(TAG, "Growth session ended for ${currentCampaignId}: ${durationSec}s")
        
        return durationSec
    }

    /**
     * Check if the current session meets the minimum duration requirement.
     */
    fun meetsMinimumDuration(): Boolean {
        if (!isTracking) return false
        val durationSec = (System.currentTimeMillis() - sessionStartTime) / 1000
        return durationSec >= MIN_SESSION_SECONDS
    }

    /**
     * Get the current session duration in seconds.
     */
    fun getCurrentDurationSeconds(): Long {
        if (!isTracking) return 0
        return (System.currentTimeMillis() - sessionStartTime) / 1000
    }

    /**
     * Get the campaign ID of the current session.
     */
    fun getCurrentCampaignId(): String? = currentCampaignId

    /**
     * Whether a session is currently being tracked.
     */
    fun isSessionActive(): Boolean = isTracking
}
