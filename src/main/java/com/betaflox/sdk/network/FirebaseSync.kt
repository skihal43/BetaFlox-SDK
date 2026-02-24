package com.betaflox.sdk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.betaflox.sdk.config.SDKConfig
import com.betaflox.sdk.tracking.EventLogger
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Handles synchronization of events to Firebase Firestore.
 * Uploads events from the local queue to the sdk_events collection.
 */
internal class FirebaseSync(
    private val config: SDKConfig,
    private val eventLogger: EventLogger,
    firebaseApp: FirebaseApp?,
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "FirebaseSync"
        private const val COLLECTION_SDK_EVENTS = "sdk_events"
        private const val MIN_SYNC_INTERVAL_MS = 5000L // 5 second debounce
    }
    
    // Get Firestore from the SDK's FirebaseApp instance
    private val firestore: FirebaseFirestore? = firebaseApp?.let {
        try {
            FirebaseFirestore.getInstance(it)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Firestore instance", e)
            null
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var syncJob: Job? = null
    private var isSyncing = false
    private var lastSyncTime = 0L
    
    /**
     * Start periodic sync of events.
     */
    fun startSync() {
        if (syncJob?.isActive == true) {
            Log.d(TAG, "Sync already running")
            return
        }
        
        if (!config.isConfigured()) {
            Log.w(TAG, "SDK not fully configured, sync disabled")
            return
        }
        
        syncJob = scope.launch {
            Log.d(TAG, "Starting periodic sync (interval: ${config.syncIntervalMs}ms)")
            
            while (isActive) {
                try {
                    syncEvents()
                } catch (e: Exception) {
                    Log.e(TAG, "Sync failed", e)
                }
                delay(config.syncIntervalMs)
            }
        }
    }
    
    /**
     * Stop periodic sync.
     */
    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        Log.d(TAG, "Sync stopped")
    }
    
    /**
     * Force immediate sync of pending events.
     * Includes debounce to prevent rapid successive calls.
     */
    fun syncNow() {
        if (!config.isConfigured()) {
            Log.w(TAG, "SDK not fully configured, cannot sync")
            return
        }
        
        // Debounce: prevent sync if called too recently
        val now = System.currentTimeMillis()
        if (now - lastSyncTime < MIN_SYNC_INTERVAL_MS) {
            Log.d(TAG, "Sync debounced, last sync was ${now - lastSyncTime}ms ago")
            return
        }
        
        // Check network availability
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network available, skipping sync")
            return
        }
        
        lastSyncTime = now
        
        scope.launch {
            try {
                syncEvents()
            } catch (e: Exception) {
                Log.e(TAG, "Sync now failed", e)
            }
        }
    }
    
    /**
     * Check if network is available.
     */
    private fun isNetworkAvailable(): Boolean {
        context ?: return true // If no context, assume network is available
        
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    private suspend fun syncEvents() {
        if (isSyncing) {
            Log.d(TAG, "Sync already in progress")
            return
        }
        
        isSyncing = true
        
        try {
            val db = firestore ?: run {
                Log.w(TAG, "Firebase not available, skipping sync")
                return
            }

            val events = eventLogger.getPendingEvents(config.maxEventsPerBatch)
            
            if (events.isEmpty()) {
                Log.d(TAG, "No events to sync")
                return
            }
            
            Log.d(TAG, "Syncing ${events.size} events...")
            
            val syncedIds = mutableListOf<String>()
            
            for (event in events) {
                try {
                    // Create document in sdk_events collection
                    db.collection(COLLECTION_SDK_EVENTS)
                        .add(event.toFirestoreMap())
                        .await()
                    
                    syncedIds.add(event.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync event ${event.id}: ${e.message}", e)
                    // Continue with other events
                }
            }
            
            // Remove successfully synced events from local queue
            if (syncedIds.isNotEmpty()) {
                eventLogger.removeEvents(syncedIds)
                Log.d(TAG, "Synced ${syncedIds.size} events successfully")
            }
            
        } finally {
            isSyncing = false
        }
    }
    
    /**
     * Get the count of pending events.
     */
    fun getPendingCount(): Int = eventLogger.getPendingCount()
    
    /**
     * Check if sync is currently running.
     */
    fun isSyncRunning(): Boolean = syncJob?.isActive == true
}
