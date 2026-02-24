package com.betaflox.sdk.fraud

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before

/**
 * Unit tests for DeviceFingerprint
 */
class DeviceFingerprintTest {

    @Test
    fun `generateHash returns consistent hash for same inputs`() {
        val input1 = "device123|samsung|SM-G973U|android14"
        val input2 = "device123|samsung|SM-G973U|android14"
        
        val hash1 = DeviceFingerprint.generateHash(input1)
        val hash2 = DeviceFingerprint.generateHash(input2)
        
        assertEquals("Same input should produce same hash", hash1, hash2)
    }

    @Test
    fun `generateHash returns different hash for different inputs`() {
        val input1 = "device123|samsung|SM-G973U|android14"
        val input2 = "device456|google|Pixel6|android14"
        
        val hash1 = DeviceFingerprint.generateHash(input1)
        val hash2 = DeviceFingerprint.generateHash(input2)
        
        assertNotEquals("Different input should produce different hash", hash1, hash2)
    }

    @Test
    fun `generateHash returns non-empty string`() {
        val input = "testdevice|brand|model|os"
        val hash = DeviceFingerprint.generateHash(input)
        
        assertTrue("Hash should not be empty", hash.isNotEmpty())
        assertTrue("Hash should have reasonable length", hash.length >= 32)
    }

    @Test
    fun `combineDeviceInfo creates proper fingerprint string`() {
        val combined = DeviceFingerprint.combineDeviceInfo(
            androidId = "abc123",
            brand = "samsung",
            model = "SM-G973U",
            manufacturer = "Samsung",
            hardware = "exynos9820",
            fingerprint = "samsung/beyond1qlteue/beyond1q:13/TP1A:user/release-keys"
        )
        
        assertTrue(combined.contains("abc123"))
        assertTrue(combined.contains("samsung"))
        assertTrue(combined.contains("SM-G973U"))
        assertTrue(combined.contains("Samsung"))
    }

    @Test
    fun `validateHash checks format correctly`() {
        // Valid SHA-256 hash (64 hex characters)
        val validHash = "a".repeat(64)
        assertTrue(DeviceFingerprint.isValidHashFormat(validHash))
        
        // Invalid - too short
        val shortHash = "a".repeat(32)
        assertFalse(DeviceFingerprint.isValidHashFormat(shortHash))
        
        // Invalid - contains non-hex characters
        val invalidChars = "g".repeat(64)
        assertFalse(DeviceFingerprint.isValidHashFormat(invalidChars))
        
        // Empty string
        assertFalse(DeviceFingerprint.isValidHashFormat(""))
    }

    @Test
    fun `normalizeInput handles special characters`() {
        val input = "device123|Samsung\nGalaxy\tS21|android"
        val normalized = DeviceFingerprint.normalizeInput(input)
        
        assertFalse("Normalized input should not contain newlines", normalized.contains("\n"))
        assertFalse("Normalized input should not contain tabs", normalized.contains("\t"))
    }

    @Test
    fun `hashesMatch compares correctly`() {
        val hash1 = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
        val hash2 = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
        val hash3 = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        
        assertTrue(DeviceFingerprint.hashesMatch(hash1, hash2))
        assertFalse(DeviceFingerprint.hashesMatch(hash1, hash3))
    }
}
