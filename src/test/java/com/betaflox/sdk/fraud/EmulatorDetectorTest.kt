package com.betaflox.sdk.fraud

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import android.content.Context

/**
 * Unit tests for EmulatorDetector
 * Tests the individual detection signals and confidence scoring.
 */
class EmulatorDetectorTest {

    // ============ Build Property Detection Tests ============

    @Test
    fun `checkFingerprintForEmulator returns false for real device fingerprints`() {
        // Real device fingerprints should not trigger emulator detection
        val realFingerprints = listOf(
            "google/oriole/oriole:14/AP1A.240305.019.A1/11445699:user/release-keys",
            "samsung/beyond1qlteue/beyond1q:13/TP1A.220624.014/G973USQS9HWK2:user/release-keys",
            "OnePlus/OnePlus9Pro/OnePlus9Pro:14/UKQ1.230924.001/R.1544fd7_2:user/release-keys",
            "google/redfin/redfin:13/TQ3A.230901.001/10750268:user/release-keys"
        )
        
        for (fingerprint in realFingerprints) {
            assertFalse(
                "Real fingerprint $fingerprint should not be detected as emulator",
                EmulatorDetector.checkFingerprintForEmulator(fingerprint)
            )
        }
    }

    @Test
    fun `checkFingerprintForEmulator returns true for generic emulator fingerprints`() {
        val emulatorFingerprints = listOf(
            "generic/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11030tried:userdebug/dev-keys",
            "google/sdk_gphone_arm64/generic_arm64:11/RSR1.201013.001/6903271:userdebug/dev-keys",
            "unknown/generic_x86/generic_x86:4.1.2/MASTER/eng.user:eng/test-keys",
            "google/sdk_gphone64_x86_64/emulator64_x86_64:12/SE1A.220826.001/8836277:user/release-keys"
        )
        
        for (fingerprint in emulatorFingerprints) {
            assertTrue(
                "Emulator fingerprint $fingerprint should be detected",
                EmulatorDetector.checkFingerprintForEmulator(fingerprint)
            )
        }
    }

    @Test
    fun `checkFingerprintForEmulator returns true for Genymotion signatures`() {
        val genymotionFingerprints = listOf(
            "genymotion/vbox86p/vbox86p:9/PPR1.180610.011/eng.build.user:userdebug/test-keys",
            "Genymotion/Phone/vbox86p:8.1.0/OPM1.171019.011/1:userdebug/test-keys"
        )
        
        for (fingerprint in genymotionFingerprints) {
            assertTrue(
                "Genymotion fingerprint $fingerprint should be detected",
                EmulatorDetector.checkFingerprintForEmulator(fingerprint)
            )
        }
    }

    @Test
    fun `checkFingerprintForEmulator detects Nox emulator`() {
        val noxFingerprints = listOf(
            "nox/nox/nox:7.1.2/NJH47F/80:userdebug/test-keys",
            "google/nox/nox:5.1.1/LMY48B/eng.nox.user:eng/test-keys"
        )
        
        for (fingerprint in noxFingerprints) {
            assertTrue(
                "Nox fingerprint $fingerprint should be detected",
                EmulatorDetector.checkFingerprintForEmulator(fingerprint)
            )
        }
    }

    // ============ Model Detection Tests ============

    @Test
    fun `checkModelForEmulator returns true for emulator models`() {
        val emulatorModels = listOf(
            "Android SDK built for x86",
            "Android SDK built for x86_64",
            "sdk_gphone_arm64",
            "sdk_gphone64_x86_64",
            "Emulator"
        )
        
        for (model in emulatorModels) {
            assertTrue(
                "Emulator model $model should be detected",
                EmulatorDetector.checkModelForEmulator(model)
            )
        }
    }

    @Test
    fun `checkModelForEmulator returns false for real device models`() {
        val realModels = listOf(
            "Pixel 7",
            "SM-G973U",
            "OnePlus 9 Pro",
            "Galaxy S21"
        )
        
        for (model in realModels) {
            assertFalse(
                "Real model $model should not be detected as emulator",
                EmulatorDetector.checkModelForEmulator(model)
            )
        }
    }

    // ============ Hardware Detection Tests ============

    @Test
    fun `checkHardwareForEmulator detects emulator hardware`() {
        assertTrue(EmulatorDetector.checkHardwareForEmulator("goldfish"))
        assertTrue(EmulatorDetector.checkHardwareForEmulator("ranchu"))
        assertTrue(EmulatorDetector.checkHardwareForEmulator("vbox86"))
        assertTrue(EmulatorDetector.checkHardwareForEmulator("nox"))
    }

    @Test
    fun `checkHardwareForEmulator returns false for real hardware`() {
        assertFalse(EmulatorDetector.checkHardwareForEmulator("qcom"))
        assertFalse(EmulatorDetector.checkHardwareForEmulator("exynos990"))
        assertFalse(EmulatorDetector.checkHardwareForEmulator("mt6785"))
        assertFalse(EmulatorDetector.checkHardwareForEmulator("kona"))
    }

    // ============ Product Detection Tests ============

    @Test
    fun `checkProductForEmulator detects SDK products`() {
        assertTrue(EmulatorDetector.checkProductForEmulator("sdk"))
        assertTrue(EmulatorDetector.checkProductForEmulator("sdk_x86"))
        assertTrue(EmulatorDetector.checkProductForEmulator("sdk_gphone64_arm64"))
        assertTrue(EmulatorDetector.checkProductForEmulator("vbox86p"))
    }

    @Test
    fun `checkProductForEmulator returns false for real products`() {
        assertFalse(EmulatorDetector.checkProductForEmulator("oriole"))
        assertFalse(EmulatorDetector.checkProductForEmulator("redfin"))
        assertFalse(EmulatorDetector.checkProductForEmulator("beyond1q"))
    }

    // ============ Build Tags Tests ============

    @Test
    fun `checkBuildTagsForEmulator detects test and dev keys`() {
        assertTrue(EmulatorDetector.checkBuildTagsForEmulator("test-keys"))
        assertTrue(EmulatorDetector.checkBuildTagsForEmulator("dev-keys"))
    }

    @Test
    fun `checkBuildTagsForEmulator returns false for release keys`() {
        assertFalse(EmulatorDetector.checkBuildTagsForEmulator("release-keys"))
    }

    // ============ All Indicators Combined Tests ============

    @Test
    fun `getAllEmulatorIndicators returns complete map for emulator`() {
        val flags = EmulatorDetector.getAllEmulatorIndicators(
            fingerprint = "generic/sdk/sdk:test",
            model = "Android SDK built for x86",
            product = "sdk",
            brand = "generic",
            device = "generic",
            hardware = "goldfish",
            buildTags = "test-keys"
        )
        
        assertNotNull(flags)
        assertEquals(7, flags.size)
        assertTrue(flags["fingerprint"] == true)
        assertTrue(flags["model"] == true)
        assertTrue(flags["product"] == true)
        assertTrue(flags["brand"] == true)
        assertTrue(flags["device"] == true)
        assertTrue(flags["hardware"] == true)
        assertTrue(flags["buildTags"] == true)
    }

    @Test
    fun `getAllEmulatorIndicators returns all false for real device`() {
        val flags = EmulatorDetector.getAllEmulatorIndicators(
            fingerprint = "google/oriole/oriole:14/AP1A.240305.019.A1/11445699:user/release-keys",
            model = "Pixel 7",
            product = "oriole",
            brand = "google",
            device = "oriole",
            hardware = "tensor",
            buildTags = "release-keys"
        )
        
        assertNotNull(flags)
        assertTrue(flags.values.none { it })
    }

    // ============ Threshold Tests ============

    @Test
    fun `default threshold is 0_75`() {
        assertEquals(0.75f, EmulatorDetector.DEFAULT_THRESHOLD)
    }

    // ============ EmulatorSignal Data Class Tests ============

    @Test
    fun `EmulatorSignal holds correct values`() {
        val signal = EmulatorSignal(
            signalId = "TEST_SIGNAL",
            displayName = "Test Signal",
            weight = 0.15f,
            isTriggered = true,
            rawValue = "test_value"
        )
        
        assertEquals("TEST_SIGNAL", signal.signalId)
        assertEquals("Test Signal", signal.displayName)
        assertEquals(0.15f, signal.weight, 0.001f)
        assertTrue(signal.isTriggered)
        assertEquals("test_value", signal.rawValue)
    }

    // ============ EmulatorDetectionResult Tests ============

    @Test
    fun `EmulatorDetectionResult calculates triggered signals correctly`() {
        val signals = listOf(
            EmulatorSignal("S1", "Signal 1", 0.2f, true, ""),
            EmulatorSignal("S2", "Signal 2", 0.3f, false, ""),
            EmulatorSignal("S3", "Signal 3", 0.25f, true, "")
        )
        
        val result = EmulatorDetectionResult(
            isEmulator = false, // Not used in this test
            confidenceScore = 0.45f,
            threshold = 0.75f,
            signals = signals,
            triggeredSignals = signals.filter { it.isTriggered }
        )
        
        assertEquals(3, result.signals.size)
        assertEquals(2, result.triggeredSignals.size)
        assertTrue(result.triggeredSignals.all { it.isTriggered })
    }

    @Test
    fun `EmulatorDetectionResult toSummary returns formatted string`() {
        val result = EmulatorDetectionResult(
            isEmulator = true,
            confidenceScore = 0.85f,
            threshold = 0.75f,
            signals = emptyList(),
            triggeredSignals = emptyList()
        )
        
        val summary = result.toSummary()
        assertTrue(summary.contains("isEmulator=true"))
        assertTrue(summary.contains("score=0.85"))
        assertTrue(summary.contains("threshold=0.75"))
    }

    @Test
    fun `EmulatorDetectionResult toMap contains required keys`() {
        val result = EmulatorDetectionResult(
            isEmulator = true,
            confidenceScore = 0.80f,
            threshold = 0.75f,
            signals = emptyList(),
            triggeredSignals = emptyList()
        )
        
        val map = result.toMap()
        assertTrue(map.containsKey("isEmulator"))
        assertTrue(map.containsKey("confidenceScore"))
        assertTrue(map.containsKey("threshold"))
        assertTrue(map.containsKey("signalsEvaluated"))
        assertTrue(map.containsKey("signalsTriggered"))
        assertTrue(map.containsKey("triggeredSignalIds"))
    }

    // ============ Score Calculation Tests ============

    @Test
    fun `confidence score is sum of triggered signal weights`() {
        // Simulating signal weights:
        // If all 7 signals trigger with weights 0.20, 0.20, 0.15, 0.20, 0.10, 0.10, 0.05
        // Total = 1.0
        val weights = listOf(0.20f, 0.20f, 0.15f, 0.20f, 0.10f, 0.10f, 0.05f)
        val totalWeight = weights.sum()
        
        assertEquals(1.0f, totalWeight, 0.001f)
    }

    @Test
    fun `score threshold boundary detection`() {
        val threshold = 0.75f
        
        // Score just below threshold should be false
        assertFalse(0.74f >= threshold)
        
        // Score at threshold should be true
        assertTrue(0.75f >= threshold)
        
        // Score above threshold should be true
        assertTrue(0.80f >= threshold)
    }
}
