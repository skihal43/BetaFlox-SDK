package com.betaflox.sdk.tracking

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log

/**
 * Tracks application lifecycle events to detect foreground/background transitions.
 * Automatically triggers session start/end events.
 */
internal class AppLifecycleTracker(
    private val sessionManager: SessionManager,
    private val heartbeatManager: HeartbeatManager
) : Application.ActivityLifecycleCallbacks {
    
    companion object {
        private const val TAG = "AppLifecycleTracker"
    }
    
    private var activityCount = 0
    private var isInForeground = false
    
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // Not used
    }
    
    override fun onActivityStarted(activity: Activity) {
        activityCount++
        
        if (activityCount == 1 && !isInForeground) {
            // App came to foreground
            isInForeground = true
            Log.d(TAG, "App entered foreground")
            sessionManager.startSession()
            heartbeatManager.start()
        }
    }
    
    override fun onActivityResumed(activity: Activity) {
        // Not used
    }
    
    override fun onActivityPaused(activity: Activity) {
        // Not used
    }
    
    override fun onActivityStopped(activity: Activity) {
        activityCount--
        
        if (activityCount == 0 && isInForeground) {
            // App went to background
            isInForeground = false
            Log.d(TAG, "App entered background")
            sessionManager.pauseSession()
            heartbeatManager.stop()
        }
    }
    
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        // Not used
    }
    
    override fun onActivityDestroyed(activity: Activity) {
        // Not used
    }
    
    /**
     * Check if the app is currently in the foreground.
     */
    fun isAppInForeground(): Boolean = isInForeground
}
