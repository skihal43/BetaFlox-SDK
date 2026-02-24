package com.betaflox.sdk.signals

import android.os.Build

/**
 * Data classes representing device signals for server-side risk scoring.
 * 
 * These are raw signals only - NO verdict computation happens here.
 * The SDK collects and uploads; the server decides.
 */

/**
 * Build-related device signals.
 */
data class BuildSignals(
    val model: String = Build.MODEL,
    val manufacturer: String = Build.MANUFACTURER,
    val brand: String = Build.BRAND,
    val device: String = Build.DEVICE,
    val hardware: String = Build.HARDWARE,
    val product: String = Build.PRODUCT,
    val fingerprint: String = Build.FINGERPRINT,
    val board: String = Build.BOARD,
    val tags: String = Build.TAGS ?: ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "model" to model,
        "manufacturer" to manufacturer,
        "brand" to brand,
        "device" to device,
        "hardware" to hardware,
        "product" to product,
        "fingerprint" to fingerprint,
        "board" to board,
        "tags" to tags
    )
}

/**
 * CPU and ABI-related signals.
 */
data class AbiSignals(
    val supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
    val primaryAbi: String = Build.CPU_ABI ?: "",
    val hasX86Abi: Boolean = supportedAbis.any { 
        it.lowercase().contains("x86") 
    }
) {
    fun toMap(): Map<String, Any> = mapOf(
        "supportedAbis" to supportedAbis,
        "primaryAbi" to primaryAbi,
        "hasX86Abi" to hasX86Abi
    )
}

/**
 * Sensor-related signals.
 */
data class SensorSignals(
    val sensorCount: Int,
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val hasProximity: Boolean,
    val hasMagneticField: Boolean,
    val hasLight: Boolean
) {
    fun toMap(): Map<String, Any> = mapOf(
        "sensorCount" to sensorCount,
        "hasAccelerometer" to hasAccelerometer,
        "hasGyroscope" to hasGyroscope,
        "hasProximity" to hasProximity,
        "hasMagneticField" to hasMagneticField,
        "hasLight" to hasLight
    )
}

/**
 * Runtime environment signals.
 */
data class RuntimeSignals(
    val isDebuggable: Boolean,
    val isDebugBuild: Boolean,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val isReinstall: Boolean
) {
    fun toMap(): Map<String, Any> = mapOf(
        "isDebuggable" to isDebuggable,
        "isDebugBuild" to isDebugBuild,
        "firstInstallTime" to firstInstallTime,
        "lastUpdateTime" to lastUpdateTime,
        "isReinstall" to isReinstall
    )
}

/**
 * Session-related signals.
 */
data class SessionSignals(
    val appOpenTimestamp: Long,
    val sessionId: String,
    val foregroundTransitions: Int = 0
) {
    fun toMap(): Map<String, Any> = mapOf(
        "appOpenTimestamp" to appOpenTimestamp,
        "sessionId" to sessionId,
        "foregroundTransitions" to foregroundTransitions
    )
}

/**
 * Google Play Integrity API signals.
 */
data class IntegritySignals(
    val meetsBasicIntegrity: Boolean,
    val meetsDeviceIntegrity: Boolean,
    val meetsStrongIntegrity: Boolean,
    val status: IntegrityStatus = IntegrityStatus.UNKNOWN
) {
    fun toMap(): Map<String, Any> = mapOf(
        "meetsBasicIntegrity" to meetsBasicIntegrity,
        "meetsDeviceIntegrity" to meetsDeviceIntegrity,
        "meetsStrongIntegrity" to meetsStrongIntegrity,
        "status" to status.name
    )
}

enum class IntegrityStatus {
    VALID,      // All integrity checks passed
    FAILED,     // One or more checks failed
    UNKNOWN,    // Could not determine (API unavailable, timeout, etc.)
    NOT_CHECKED // Play Integrity not yet called
}

/**
 * Complete device signals payload for server upload.
 */
data class DeviceSignals(
    val buildSignals: BuildSignals,
    val abiSignals: AbiSignals,
    val sensorSignals: SensorSignals,
    val runtimeSignals: RuntimeSignals,
    val sessionSignals: SessionSignals,
    val integritySignals: IntegritySignals
) {
    /**
     * Convert to Firestore-compatible map.
     */
    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "deviceSignals" to buildSignals.toMap(),
        "abiSignals" to abiSignals.toMap(),
        "sensorSignals" to sensorSignals.toMap(),
        "runtimeSignals" to runtimeSignals.toMap(),
        "sessionSignals" to sessionSignals.toMap(),
        "integritySignals" to integritySignals.toMap()
    )
}
