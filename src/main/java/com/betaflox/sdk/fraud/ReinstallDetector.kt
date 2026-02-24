package com.betaflox.sdk.fraud

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.betaflox.sdk.config.SDKConfig

/**
 * Detects if the app has been reinstalled.
 * Used to prevent abuse through app reinstallation.
 */
internal class ReinstallDetector(private val context: Context) {
    
    companion object {
        private const val TAG = "ReinstallDetector"

        // Static utility methods for testing
        @JvmStatic
        fun checkReinstall(storedFirstInstall: Long, currentInstallTime: Long): Boolean {
            if (storedFirstInstall == 0L) return false
            return currentInstallTime > storedFirstInstall
        }

        @JvmStatic
        fun calculateTimeDifference(time1: Long, time2: Long): Long {
            return kotlin.math.abs(time2 - time1)
        }

        @JvmStatic
        fun formatInstallDate(timestamp: Long): String {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(timestamp))
        }

        @JvmStatic
        fun isSignificantTimeDifference(differenceMs: Long): Boolean {
            return differenceMs >= 86400000L // 1 day in milliseconds
        }

        @JvmStatic
        fun getReinstallInfo(isReinstall: Boolean, timeDifferenceMs: Long, storedInstallTime: Long, currentInstallTime: Long): Map<String, Any> {
            return mapOf(
                "isReinstall" to isReinstall,
                "timeDifferenceMs" to timeDifferenceMs,
                "storedInstallTime" to storedInstallTime,
                "currentInstallTime" to currentInstallTime
            )
        }

        @JvmStatic
        fun isValidTimestamp(timestamp: Long): Boolean {
            return timestamp > 0 && timestamp < 4102444800000L // Before year 2100
        }
    }
    
    private val prefs = context.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Record the first install time if not already recorded.
     */
    fun recordInstallIfNeeded() {
        val existingTime = prefs.getLong(SDKConfig.KEY_FIRST_INSTALL_TIME, 0)
        if (existingTime == 0L) {
            val installTime = getPackageInstallTime()
            prefs.edit().putLong(SDKConfig.KEY_FIRST_INSTALL_TIME, installTime).apply()
            Log.d(TAG, "First install time recorded: $installTime")
        }
    }
    
    /**
     * Check if the app has been reinstalled.
     * Compares stored install time with current package install time.
     */
    fun isReinstall(): Boolean {
        val storedInstallTime = prefs.getLong(SDKConfig.KEY_FIRST_INSTALL_TIME, 0)
        if (storedInstallTime == 0L) {
            // First run, not a reinstall
            return false
        }
        
        val currentInstallTime = getPackageInstallTime()
        
        // If the current install time is significantly different, it's a reinstall
        val timeDifference = kotlin.math.abs(currentInstallTime - storedInstallTime)
        
        // Allow 1 minute tolerance for timing differences
        val isReinstall = timeDifference > 60_000
        
        if (isReinstall) {
            Log.w(TAG, "Reinstall detected. Stored: $storedInstallTime, Current: $currentInstallTime")
        }
        
        return isReinstall
    }
    
    /**
     * Get the time the app was first installed.
     */
    private fun getPackageInstallTime(): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.firstInstallTime
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get package install time", e)
            System.currentTimeMillis()
        }
    }
    
    /**
     * Get the last update time of the app.
     */
    fun getLastUpdateTime(): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.lastUpdateTime
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last update time", e)
            0L
        }
    }
    
    /**
     * Get install detection details.
     */
    fun getInstallDetails(): Map<String, Long> {
        return mapOf(
            "storedInstallTime" to prefs.getLong(SDKConfig.KEY_FIRST_INSTALL_TIME, 0),
            "packageInstallTime" to getPackageInstallTime(),
            "lastUpdateTime" to getLastUpdateTime()
        )
    }
    
    /**
     * Reset the stored install time (for testing purposes).
     */
    internal fun reset() {
        prefs.edit().remove(SDKConfig.KEY_FIRST_INSTALL_TIME).apply()
        Log.d(TAG, "Install time reset")
    }
}
