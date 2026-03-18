package com.betaflox.sdk.tracking

import android.content.Context
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Manages Play Install Referrer API for growth campaign install attribution.
 * Captures referrer click timestamp and install begin timestamp.
 *
 * The timestamps are used server-side to validate that ≥10 seconds elapsed
 * between the referrer click and the install begin (click-injection defense).
 * Client-side pre-validation tags suspicious installs for server decision.
 */
class InstallReferrerManager(private val context: Context) {

    companion object {
        private const val TAG = "InstallReferrerManager"
        private const val PREFS_NAME = "betaflox_referrer"
        private const val KEY_CLICK_TS = "referrer_click_ts"
        private const val KEY_INSTALL_BEGIN_TS = "install_begin_ts"
        private const val KEY_REFERRER_URL = "referrer_url"
        private const val KEY_REFERRER_CAPTURED = "referrer_captured"
        private const val KEY_CLIENT_FRAUD_SIGNAL = "client_fraud_signal"
        private const val MIN_CLICK_TO_INSTALL_SECONDS = 10L
    }

    data class ReferrerData(
        val clickTimestampSeconds: Long,
        val installBeginTimestampSeconds: Long,
        val referrerUrl: String,
        val clientFraudSignal: String? = null
    )

    /**
     * Capture referrer data from the Play Install Referrer API.
     * Only runs once per install (guarded by KEY_REFERRER_CAPTURED flag).
     *
     * @return ReferrerData if captured successfully, null otherwise
     */
    suspend fun captureReferrerFromPlayAPI(): ReferrerData? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REFERRER_CAPTURED, false)) {
            Log.d(TAG, "Referrer already captured, returning stored data")
            return getReferrerData()
        }

        return withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { continuation ->
                val client = InstallReferrerClient.newBuilder(context).build()

                client.startConnection(object : InstallReferrerStateListener {
                    override fun onInstallReferrerSetupFinished(responseCode: Int) {
                        when (responseCode) {
                            InstallReferrerClient.InstallReferrerResponse.OK -> {
                                try {
                                    val details = client.installReferrer
                                    val clickTs = details.referrerClickTimestampSeconds
                                    val installTs = details.installBeginTimestampSeconds
                                    val url = details.installReferrer ?: ""

                                    // Client-side pre-validation: flag suspicious timing
                                    val timeDiff = installTs - clickTs
                                    val fraudSignal = if (timeDiff < MIN_CLICK_TO_INSTALL_SECONDS && clickTs > 0) {
                                        Log.w(TAG, "Click-injection suspect: timeDiff=${timeDiff}s (<${MIN_CLICK_TO_INSTALL_SECONDS}s)")
                                        "click_injection_suspect"
                                    } else {
                                        null
                                    }

                                    storeReferrerData(clickTs, installTs, url, fraudSignal)

                                    val data = ReferrerData(
                                        clickTimestampSeconds = clickTs,
                                        installBeginTimestampSeconds = installTs,
                                        referrerUrl = url,
                                        clientFraudSignal = fraudSignal
                                    )
                                    Log.d(TAG, "Referrer captured: click=$clickTs, install=$installTs, timeDiff=${timeDiff}s")
                                    continuation.resume(data)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error reading referrer details", e)
                                    continuation.resume(null)
                                } finally {
                                    client.endConnection()
                                }
                            }
                            InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                                Log.w(TAG, "Install Referrer API not supported on this device")
                                client.endConnection()
                                continuation.resume(null)
                            }
                            InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                                Log.w(TAG, "Install Referrer service unavailable")
                                client.endConnection()
                                continuation.resume(null)
                            }
                            else -> {
                                Log.w(TAG, "Install Referrer unknown response: $responseCode")
                                client.endConnection()
                                continuation.resume(null)
                            }
                        }
                    }

                    override fun onInstallReferrerServiceDisconnected() {
                        Log.w(TAG, "Install Referrer service disconnected")
                        // Don't resume — the timeout will handle cancellation
                    }
                })

                continuation.invokeOnCancellation {
                    try { client.endConnection() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Store referrer data from the Install Referrer API callback.
     */
    fun storeReferrerData(
        clickTimestampSeconds: Long,
        installBeginTimestampSeconds: Long,
        referrerUrl: String,
        clientFraudSignal: String? = null
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putLong(KEY_CLICK_TS, clickTimestampSeconds)
            putLong(KEY_INSTALL_BEGIN_TS, installBeginTimestampSeconds)
            putString(KEY_REFERRER_URL, referrerUrl)
            putBoolean(KEY_REFERRER_CAPTURED, true)
            if (clientFraudSignal != null) {
                putString(KEY_CLIENT_FRAUD_SIGNAL, clientFraudSignal)
            }
            apply()
        }
        Log.d(TAG, "Referrer data stored: click=$clickTimestampSeconds, installBegin=$installBeginTimestampSeconds")
    }

    /**
     * Get stored referrer data, or null if not available.
     */
    fun getReferrerData(): ReferrerData? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val clickTs = prefs.getLong(KEY_CLICK_TS, 0)
        val installTs = prefs.getLong(KEY_INSTALL_BEGIN_TS, 0)
        val url = prefs.getString(KEY_REFERRER_URL, "") ?: ""
        val fraudSignal = prefs.getString(KEY_CLIENT_FRAUD_SIGNAL, null)

        if (clickTs == 0L && installTs == 0L) return null

        return ReferrerData(
            clickTimestampSeconds = clickTs,
            installBeginTimestampSeconds = installTs,
            referrerUrl = url,
            clientFraudSignal = fraudSignal
        )
    }

    /**
     * Validate that the referrer data passes click-injection defense.
     * Requires ≥10 seconds between click and install begin.
     */
    fun passesClickInjectionCheck(): Boolean {
        val data = getReferrerData() ?: return false
        val timeDiff = data.installBeginTimestampSeconds - data.clickTimestampSeconds
        return timeDiff >= MIN_CLICK_TO_INSTALL_SECONDS
    }

    /**
     * Whether referrer data has already been captured for this install.
     */
    fun isReferrerCaptured(): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REFERRER_CAPTURED, false)
    }

    /**
     * Clear stored referrer data.
     */
    fun clear() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
