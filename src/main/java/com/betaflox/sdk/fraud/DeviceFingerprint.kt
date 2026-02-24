package com.betaflox.sdk.fraud

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.betaflox.sdk.config.SDKConfig
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.security.MessageDigest

/**
 * Generates a unique device fingerprint hash.
 * Used to ensure one tester account per device.
 */
internal class DeviceFingerprint(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceFingerprint"

        // Static utility methods for testing
        @JvmStatic
        fun generateHash(input: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }

        @JvmStatic
        fun combineDeviceInfo(androidId: String, brand: String, model: String, manufacturer: String, hardware: String, fingerprint: String): String {
            return "$androidId|$brand|$model|$manufacturer|$hardware|$fingerprint"
        }

        @JvmStatic
        fun isValidHashFormat(hash: String): Boolean {
            return hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }
        }

        @JvmStatic
        fun normalizeInput(input: String): String {
            return input.replace("\n", " ").replace("\t", " ")
        }

        @JvmStatic
        fun hashesMatch(hash1: String, hash2: String): Boolean {
            return hash1 == hash2
        }
    }
    
    private val prefs = context.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
    private var cachedHash: String? = null
    
    /**
     * Get the device fingerprint hash.
     * The hash is cached and persisted for consistency.
     */
    fun getHash(): String {
        // Return cached hash if available
        cachedHash?.let { return it }
        
        // Check for persisted hash
        val persistedHash = prefs.getString(SDKConfig.KEY_DEVICE_HASH, null)
        if (persistedHash != null) {
            cachedHash = persistedHash
            return persistedHash
        }
        
        // Generate new hash
        val hash = generateHash()
        
        // Persist the hash
        prefs.edit().putString(SDKConfig.KEY_DEVICE_HASH, hash).apply()
        cachedHash = hash
        
        Log.d(TAG, "Device hash generated: ${hash.take(8)}...")
        return hash
    }
    
    @SuppressLint("HardwareIds")
    private fun generateHash(): String {
        val components = StringBuilder()
        
        // Android ID (unique per user/device)
        try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            if (!androidId.isNullOrBlank()) {
                components.append(androidId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get Android ID", e)
        }
        
        // Hardware components
        components.append(Build.BOARD)
        components.append(Build.BRAND)
        components.append(Build.DEVICE)
        components.append(Build.HARDWARE)
        components.append(Build.MANUFACTURER)
        components.append(Build.MODEL)
        components.append(Build.PRODUCT)
        
        // App Signature (binds to specific app)
        components.append(getAppSignature())
        
        // Supported ABIs
        
        // Supported ABIs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            components.append(Build.SUPPORTED_ABIS.joinToString(","))
        }
        
        // Generate SHA-256 hash
        return sha256(components.toString())
    }
    
    private fun sha256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "SHA-256 hashing failed", e)
            // Fallback to simple hash
            input.hashCode().toString(16)
        }
    }

    @SuppressLint("PackageManagerGetSignatures")
    private fun getAppSignature(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageInfo.signingInfo
                if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }
            
            val signature = signatures?.firstOrNull() ?: return ""
            // Use SHA-256 of the signature
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(signature.toByteArray())
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get app signature", e)
            ""
        }
    }

    
    /**
     * Get individual device properties for debugging.
     */
    fun getDeviceProperties(): Map<String, String> {
        return mapOf(
            "board" to Build.BOARD,
            "brand" to Build.BRAND,
            "device" to Build.DEVICE,
            "hardware" to Build.HARDWARE,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "product" to Build.PRODUCT,
            "sdk" to Build.VERSION.SDK_INT.toString()
        )
    }
}
