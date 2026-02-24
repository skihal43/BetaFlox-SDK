package com.betaflox.sdk.tracking

import android.os.Handler
import android.os.Looper
import com.betaflox.sdk.config.SDKConfig

/**
 * Sends periodic heartbeat events while app is active.
 * Helps detect background kills and suspicious behavior.
 */
internal class HeartbeatManager(
    private val config: SDKConfig,
    private val eventLogger: EventLogger,
    private val sessionManager: SessionManager? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    
    companion object {
        const val HEARTBEAT_INTERVAL_MS = 60_000L // 1 minute
    }
    
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                sendHeartbeat()
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }
    
    fun start() {
        if (!isRunning) {
            isRunning = true
            // Post the first heartbeat after interval (removes duplicate on start)
            handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        }
    }
    
    fun stop() {
        isRunning = false
        handler.removeCallbacks(heartbeatRunnable)
    }
    
    private fun sendHeartbeat() {
        val data = mutableMapOf<String, Any>(
            "timestamp" to System.currentTimeMillis()
        )
        
        // Include session duration if available
        sessionManager?.let {
            data["sessionDuration"] = it.getCurrentSessionDuration()
            // Check if daily task is completed during active session
            it.checkDailyCompletionIfActive()
        }
        
        eventLogger.logEvent(
            eventType = EventLogger.EventTypes.HEARTBEAT,
            data = data
        )
    }
}
