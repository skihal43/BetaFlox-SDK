package com.betaflox.sdk.network

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.betaflox.sdk.BetaFloxSDK
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for reliable background sync.
 * Ensures events are synced even if the app is killed.
 */
class EventSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "EventSyncWorker"
        private const val WORK_NAME = "betaflox_event_sync"
        private const val SYNC_INTERVAL_MINUTES = 15L
        
        /**
         * Schedule periodic sync work.
         * Should be called after SDK initialization.
         */
        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<EventSyncWorker>(
                SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "Event sync work scheduled every $SYNC_INTERVAL_MINUTES minutes")
        }
        
        /**
         * Cancel scheduled sync work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Event sync work cancelled")
        }
    }
    
    override suspend fun doWork(): Result {
        return try {
            if (!BetaFloxSDK.isInitialized()) {
                Log.d(TAG, "SDK not initialized, skipping sync")
                return Result.success()
            }
            
            if (!BetaFloxSDK.isTrackingEnabled()) {
                Log.d(TAG, "Tracking disabled, skipping sync")
                return Result.success()
            }
            
            Log.d(TAG, "Starting background sync...")
            BetaFloxSDK.syncNow()
            Log.d(TAG, "Background sync completed")
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Background sync failed", e)
            Result.retry()
        }
    }
}
