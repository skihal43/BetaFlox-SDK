package com.betaflox.sdk.fraud

/**
 * Represents a single emulator detection signal.
 * Each signal contributes to the overall confidence score.
 */
data class EmulatorSignal(
    /**
     * Unique identifier for this signal (e.g., "SENSOR_ACCELEROMETER_MISSING")
     */
    val signalId: String,
    
    /**
     * Human-readable name (e.g., "Accelerometer Missing")
     */
    val displayName: String,
    
    /**
     * Weight of this signal in the final score (0.0 to 1.0).
     * Higher weight means stronger indicator of emulator.
     */
    val weight: Float,
    
    /**
     * Whether this signal was triggered (detected as emulator-like)
     */
    val isTriggered: Boolean,
    
    /**
     * Raw value captured for debugging purposes
     */
    val rawValue: String
)
