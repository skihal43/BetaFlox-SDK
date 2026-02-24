package com.betaflox.sdk.signals

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import com.betaflox.sdk.BuildConfig
import java.util.UUID

/**
 * Collects raw device signals for server-side risk scoring.
 * 
 * IMPORTANT: This class ONLY collects signals.
 * It does NOT make any fraud/emulator verdicts.
 * All decisions are made server-side by Cloud Functions.
 */
internal class DeviceSignalCollector(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceSignalCollector"
    }
    
    /**
     * Collect all device signals.
     * 
     * @param sessionId Unique session identifier
     * @param integritySignals Play Integrity results (collected separately)
     * @return Complete DeviceSignals payload ready for upload
     */
    fun collectSignals(
        sessionId: String = UUID.randomUUID().toString(),
        integritySignals: IntegritySignals = IntegritySignals(
            meetsBasicIntegrity = false,
            meetsDeviceIntegrity = false,
            meetsStrongIntegrity = false,
            status = IntegrityStatus.NOT_CHECKED
        )
    ): DeviceSignals {
        Log.d(TAG, "Collecting device signals for session: $sessionId")
        
        return DeviceSignals(
            buildSignals = collectBuildSignals(),
            abiSignals = collectAbiSignals(),
            sensorSignals = collectSensorSignals(),
            runtimeSignals = collectRuntimeSignals(),
            sessionSignals = collectSessionSignals(sessionId),
            integritySignals = integritySignals
        )
    }
    
    /**
     * Collect Build.* property signals.
     */
    private fun collectBuildSignals(): BuildSignals {
        return BuildSignals(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            device = Build.DEVICE,
            hardware = Build.HARDWARE,
            product = Build.PRODUCT,
            fingerprint = Build.FINGERPRINT,
            board = Build.BOARD,
            tags = Build.TAGS ?: ""
        )
    }
    
    /**
     * Collect CPU/ABI signals.
     */
    private fun collectAbiSignals(): AbiSignals {
        val supportedAbis = Build.SUPPORTED_ABIS?.toList() ?: emptyList()
        val primaryAbi = Build.CPU_ABI ?: ""
        
        // Flag if x86/x86_64 is present (DO NOT decide, just flag)
        val hasX86Abi = supportedAbis.any { abi ->
            abi.lowercase().contains("x86")
        }
        
        return AbiSignals(
            supportedAbis = supportedAbis,
            primaryAbi = primaryAbi,
            hasX86Abi = hasX86Abi
        )
    }
    
    /**
     * Collect sensor availability signals.
     */
    private fun collectSensorSignals(): SensorSignals {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        
        val allSensors = sensorManager?.getSensorList(Sensor.TYPE_ALL) ?: emptyList()
        
        return SensorSignals(
            sensorCount = allSensors.size,
            hasAccelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
            hasGyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
            hasProximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null,
            hasMagneticField = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null,
            hasLight = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        )
    }
    
    /**
     * Collect runtime environment signals.
     */
    private fun collectRuntimeSignals(): RuntimeSignals {
        val packageManager = context.packageManager
        val packageName = context.packageName
        
        var firstInstallTime = 0L
        var lastUpdateTime = 0L
        var isDebuggable = false
        
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            firstInstallTime = packageInfo.firstInstallTime
            lastUpdateTime = packageInfo.lastUpdateTime
            
            val appInfo = context.applicationInfo
            isDebuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Could not get package info: ${e.message}")
        }
        
        // Check if this is a debug build
        val isDebugBuild = BuildConfig.DEBUG
        
        // Check for reinstall (first install != last update and >24h gap)
        val isReinstall = lastUpdateTime > firstInstallTime && 
            (lastUpdateTime - firstInstallTime) > 24 * 60 * 60 * 1000
        
        return RuntimeSignals(
            isDebuggable = isDebuggable,
            isDebugBuild = isDebugBuild,
            firstInstallTime = firstInstallTime,
            lastUpdateTime = lastUpdateTime,
            isReinstall = isReinstall
        )
    }
    
    /**
     * Collect session-related signals.
     */
    private fun collectSessionSignals(sessionId: String): SessionSignals {
        return SessionSignals(
            appOpenTimestamp = System.currentTimeMillis(),
            sessionId = sessionId,
            foregroundTransitions = 0 // Will be updated by lifecycle tracker
        )
    }
    
    /**
     * Get a summary of signals for logging (non-sensitive).
     */
    fun getSignalSummary(): Map<String, Any> {
        val signals = collectSignals()
        return mapOf(
            "sensorCount" to signals.sensorSignals.sensorCount,
            "hasX86Abi" to signals.abiSignals.hasX86Abi,
            "hasAccelerometer" to signals.sensorSignals.hasAccelerometer,
            "hasGyroscope" to signals.sensorSignals.hasGyroscope,
            "isDebuggable" to signals.runtimeSignals.isDebuggable,
            "integrityStatus" to signals.integritySignals.status.name
        )
    }
}
