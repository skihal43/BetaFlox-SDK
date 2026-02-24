package com.betaflox.sdk.fraud

import android.os.Build
import android.util.Log
import java.io.File

/**
 * Detects if the device is rooted.
 * Uses multiple detection methods for accuracy.
 */
internal class RootDetector {
    
    companion object {
        private const val TAG = "RootDetector"
        
        // Common paths where su binary might be located
        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/system/bin/.ext/.su",
            "/system/usr/su-backup",
            "/su/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su"
        )
        
        // Root management apps
        private val ROOT_APPS = listOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.zachspong.temprootremovejb"
        )
        
    // Dangerous properties that indicate root
        private val DANGEROUS_PROPS = listOf(
            "ro.debuggable",
            "ro.secure"
        )

        // Static utility methods for testing
        @JvmStatic
        fun isKnownSuPath(path: String): Boolean {
            return SU_PATHS.contains(path)
        }

        @JvmStatic
        fun isRootManagementPackage(packageName: String): Boolean {
            return ROOT_APPS.contains(packageName) ||
                   packageName.contains("xposed") ||
                   packageName.contains("substrate") ||
                   packageName.contains("hideroot") ||
                   packageName.contains("hidemyroot")
        }

        @JvmStatic
        fun hasTestKeys(tags: String): Boolean {
            return tags.contains("test-keys") || tags.contains("dev-keys")
        }

        @JvmStatic
        fun hasDangerousProps(props: Map<String, String>): Boolean {
            return props["ro.debuggable"] == "1" || props["ro.secure"] == "0"
        }

        @JvmStatic
        fun getRootIndicators(suPathExists: Boolean, magiskDetected: Boolean, testKeys: Boolean, dangerousProps: Boolean): Map<String, Boolean> {
            return mapOf(
                "suPathExists" to suPathExists,
                "magiskDetected" to magiskDetected,
                "testKeys" to testKeys,
                "dangerousProps" to dangerousProps
            )
        }
    }
    
    private var cachedResult: Boolean? = null
    
    /**
     * Check if the device is rooted.
     * Results are cached for performance.
     */
    fun isRooted(): Boolean {
        cachedResult?.let { return it }
        
        val result = detectRoot()
        cachedResult = result
        
        if (result) {
            Log.w(TAG, "Root detected")
        }
        
        return result
    }
    
    private fun detectRoot(): Boolean {
        return checkSuBinary() || checkBuildTags() || checkRootPaths()
    }
    
    /**
     * Check if su binary exists in common paths.
     */
    private fun checkSuBinary(): Boolean {
        for (path in SU_PATHS) {
            try {
                if (File(path).exists()) {
                    Log.d(TAG, "su binary found at: $path")
                    return true
                }
            } catch (e: Exception) {
                // Permission denied - might still be rooted
            }
        }
        return false
    }
    
    /**
     * Check build tags for test-keys (indicates custom ROM).
     */
    private fun checkBuildTags(): Boolean {
        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) {
            Log.d(TAG, "test-keys detected in build tags")
            return true
        }
        return false
    }
    
    /**
     * Check for root-related paths and directories.
     */
    private fun checkRootPaths(): Boolean {
        val paths = listOf(
            "/system/app/Superuser.apk",
            "/system/app/SuperSU",
            "/system/app/Magisk",
            "/sbin/.magisk",
            "/cache/.disable_magisk",
            "/dev/.magisk.unblock"
        )
        
        for (path in paths) {
            try {
                if (File(path).exists()) {
                    Log.d(TAG, "Root path found: $path")
                    return true
                }
            } catch (e: Exception) {
                // Ignore permission errors
            }
        }
        
        return false
    }
    
    /**
     * Get the level of root confidence (0-100).
     */
    fun getRootConfidence(): Int {
        var confidence = 0
        
        if (checkSuBinary()) confidence += 40
        if (checkBuildTags()) confidence += 30
        if (checkRootPaths()) confidence += 30
        
        return confidence.coerceIn(0, 100)
    }
}
