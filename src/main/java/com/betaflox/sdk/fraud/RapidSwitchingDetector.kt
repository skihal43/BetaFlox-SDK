package com.betaflox.sdk.fraud

import android.content.Context

/**
 * Detects rapid app switching which may indicate fraudulent behavior.
 * Flags when app is opened/closed too frequently in a short time.
 */
internal class RapidSwitchingDetector(private val context: Context) {
    
    private val sessionTimestamps = mutableListOf<Long>()
    
    companion object {
        const val WINDOW_MS = 60_000L // 1 minute window
        const val MAX_SESSIONS_IN_WINDOW = 5 // More than 5 opens in 1 minute is suspicious
        const val MIN_SESSION_DURATION_MS = 10_000L // Sessions under 10 seconds are suspicious
    }
    
    /**
     * Record a session start.
     * @return true if rapid switching is detected
     */
    fun recordSessionStart(): Boolean {
        val now = System.currentTimeMillis()
        sessionTimestamps.add(now)
        
        // Clean old timestamps outside window
        sessionTimestamps.removeAll { now - it > WINDOW_MS }
        
        // Check for rapid switching
        return sessionTimestamps.size > MAX_SESSIONS_IN_WINDOW
    }
    
    /**
     * Check if last session was suspiciously short.
     */
    fun recordSessionEnd(sessionDurationMs: Long): Boolean {
        return sessionDurationMs < MIN_SESSION_DURATION_MS
    }
    
    /**
     * Get current fraud signal.
     */
    fun isRapidSwitching(): Boolean {
        val now = System.currentTimeMillis()
        val recentSessions = sessionTimestamps.count { now - it <= WINDOW_MS }
        return recentSessions > MAX_SESSIONS_IN_WINDOW
    }
}
