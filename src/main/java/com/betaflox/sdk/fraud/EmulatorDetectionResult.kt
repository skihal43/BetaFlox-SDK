package com.betaflox.sdk.fraud

/**
 * Result of emulator detection containing confidence score and all signals.
 */
data class EmulatorDetectionResult(
    /**
     * Whether the device is considered an emulator based on threshold.
     */
    val isEmulator: Boolean,
    
    /**
     * Confidence score from 0.0 to 1.0.
     * Higher score = more likely to be an emulator.
     */
    val confidenceScore: Float,
    
    /**
     * Threshold used for the isEmulator decision.
     */
    val threshold: Float,
    
    /**
     * All signals that were evaluated.
     */
    val signals: List<EmulatorSignal>,
    
    /**
     * Only the signals that were triggered (contributed to score).
     */
    val triggeredSignals: List<EmulatorSignal>
) {
    /**
     * Get a summary string for logging/debugging.
     */
    fun toSummary(): String {
        return buildString {
            append("EmulatorDetection[")
            append("isEmulator=$isEmulator, ")
            append("score=${"%.2f".format(confidenceScore)}, ")
            append("threshold=${"%.2f".format(threshold)}, ")
            append("triggered=${triggeredSignals.size}/${signals.size}")
            append("]")
        }
    }
    
    /**
     * Get details map for API transmission.
     */
    fun toMap(): Map<String, Any> {
        return mapOf(
            "isEmulator" to isEmulator,
            "confidenceScore" to confidenceScore,
            "threshold" to threshold,
            "signalsEvaluated" to signals.size,
            "signalsTriggered" to triggeredSignals.size,
            "triggeredSignalIds" to triggeredSignals.map { it.signalId }
        )
    }
}
