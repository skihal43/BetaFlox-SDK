package com.betaflox.sdk.network

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.betaflox.sdk.BetaFloxSDK
import com.betaflox.sdk.growth.GrowthValidationResult
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for guaranteed background retry of growth install validation.
 * Uses exponential backoff (30s initial) with a maximum of 5 retries.
 *
 * Follows the same pattern as [EventSyncWorker]:
 * - OneTimeWorkRequest with ExistingWorkPolicy.KEEP (prevents duplicates)
 * - Network connectivity constraint
 * - Battery-aware scheduling
 */
class GrowthValidationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "GrowthValidationWorker"
        private const val KEY_CAMPAIGN_ID = "campaign_id"
        private const val MAX_RETRIES = 5

        /**
         * Enqueue a unique validation retry for a given campaign.
         * Uses KEEP policy to prevent duplicate workers for the same campaign.
         */
        fun enqueue(context: Context, campaignId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = OneTimeWorkRequestBuilder<GrowthValidationWorker>()
                .setInputData(workDataOf(KEY_CAMPAIGN_ID to campaignId))
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30_000, // 30 seconds initial delay
                    TimeUnit.MILLISECONDS
                )
                .addTag("growth_validation")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "growth_validation_$campaignId",
                ExistingWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Enqueued validation retry for campaign=$campaignId")
        }

        /**
         * Cancel any pending validation work for a campaign.
         */
        fun cancel(context: Context, campaignId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("growth_validation_$campaignId")
            Log.d(TAG, "Cancelled validation work for campaign=$campaignId")
        }
    }

    override suspend fun doWork(): Result {
        val campaignId = inputData.getString(KEY_CAMPAIGN_ID)
        if (campaignId.isNullOrBlank()) {
            Log.e(TAG, "No campaign ID provided")
            return Result.failure()
        }

        if (!BetaFloxSDK.isInitialized()) {
            Log.d(TAG, "SDK not initialized, retrying later")
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }

        Log.i(TAG, "Attempting validation retry #${runAttemptCount + 1} for campaign=$campaignId")

        return try {
            val validator = BetaFloxSDK.getGrowthInstallValidator()
            if (validator == null) {
                Log.e(TAG, "GrowthInstallValidator not available")
                return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            }

            when (val result = validator.performValidationFromStoredPayload()) {
                is GrowthValidationResult.Success -> {
                    Log.i(TAG, "Validation succeeded on retry #${runAttemptCount + 1}")
                    Result.success()
                }
                is GrowthValidationResult.Queued -> {
                    Log.i(TAG, "Validation queued on retry #${runAttemptCount + 1}")
                    Result.success() // Queued is a valid end state
                }
                is GrowthValidationResult.Rejected -> {
                    Log.w(TAG, "Validation rejected: ${result.reason}")
                    Result.failure() // Permanent — don't retry
                }
                is GrowthValidationResult.Error -> {
                    Log.e(TAG, "Validation error: ${result.message}")
                    if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Worker error: ${e.message}")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }
}
