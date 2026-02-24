package com.betaflox.sdk.network

import android.util.Log

/**
 * Manages the offline event queue with retry logic.
 * Events are queued when offline and synced when connectivity is restored.
 * 
 * Note: This class works in conjunction with EventLogger for persistence
 * and FirebaseSync for actual uploads.
 */
internal class EventQueue {
    
    companion object {
        private const val TAG = "EventQueue"
        const val MAX_RETRY_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 5000L // 5 seconds
    }
    
    private val retryAttempts = mutableMapOf<String, Int>()
    
    /**
     * Record a failed sync attempt for an event.
     * 
     * @param eventId The event ID that failed to sync
     * @return true if the event should be retried, false if max attempts reached
     */
    fun recordFailure(eventId: String): Boolean {
        val currentAttempts = retryAttempts.getOrDefault(eventId, 0) + 1
        retryAttempts[eventId] = currentAttempts
        
        val shouldRetry = currentAttempts < MAX_RETRY_ATTEMPTS
        
        if (!shouldRetry) {
            Log.w(TAG, "Event $eventId reached max retry attempts ($MAX_RETRY_ATTEMPTS)")
        }
        
        return shouldRetry
    }
    
    /**
     * Record a successful sync for an event.
     * Clears the retry counter.
     */
    fun recordSuccess(eventId: String) {
        retryAttempts.remove(eventId)
    }
    
    /**
     * Get the number of retry attempts for an event.
     */
    fun getRetryCount(eventId: String): Int {
        return retryAttempts.getOrDefault(eventId, 0)
    }
    
    /**
     * Clear all retry counters.
     */
    fun clearRetries() {
        retryAttempts.clear()
    }
    
    /**
     * Get events that have exceeded max retries.
     */
    fun getFailedEvents(): Set<String> {
        return retryAttempts.filter { it.value >= MAX_RETRY_ATTEMPTS }.keys
    }
    
    /**
     * Calculate delay before next retry (exponential backoff).
     */
    fun getRetryDelay(eventId: String): Long {
        val attempts = retryAttempts.getOrDefault(eventId, 0)
        // Exponential backoff: 5s, 10s, 20s, ...
        return RETRY_DELAY_MS * (1 shl attempts).coerceAtMost(8) // Max 8x multiplier
    }
}
