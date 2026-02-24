package com.betaflox.sdk.fraud

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before

/**
 * Unit tests for ReinstallDetector
 */
class ReinstallDetectorTest {

    @Test
    fun `isReinstall returns true when install time is newer than stored time`() {
        val storedFirstInstall = 1700000000000L // Earlier timestamp
        val currentInstallTime = 1700100000000L // Later timestamp (reinstalled)
        
        assertTrue(
            "Should detect reinstall when current install is newer",
            ReinstallDetector.checkReinstall(storedFirstInstall, currentInstallTime)
        )
    }

    @Test
    fun `isReinstall returns false when install time matches`() {
        val storedFirstInstall = 1700000000000L
        val currentInstallTime = 1700000000000L // Same timestamp
        
        assertFalse(
            "Should not detect reinstall when times match",
            ReinstallDetector.checkReinstall(storedFirstInstall, currentInstallTime)
        )
    }

    @Test
    fun `isReinstall returns false on first install`() {
        val storedFirstInstall = 0L // No stored value (first install)
        val currentInstallTime = 1700000000000L
        
        assertFalse(
            "Should not detect reinstall on first install",
            ReinstallDetector.checkReinstall(storedFirstInstall, currentInstallTime)
        )
    }

    @Test
    fun `calculateTimeDifference works correctly`() {
        val time1 = 1700000000000L
        val time2 = 1700100000000L
        
        val diff = ReinstallDetector.calculateTimeDifference(time1, time2)
        assertEquals(100000000L, diff)
    }

    @Test
    fun `formatInstallDate returns readable format`() {
        val timestamp = 1700000000000L // Nov 14, 2023
        val formatted = ReinstallDetector.formatInstallDate(timestamp)
        
        assertTrue("Formatted date should contain year", formatted.contains("2023"))
    }

    @Test
    fun `isSignificantTimeDifference detects meaningful gaps`() {
        // 1 hour difference - not significant
        assertFalse(
            ReinstallDetector.isSignificantTimeDifference(3600000L)
        )
        
        // 1 day difference - significant
        assertTrue(
            ReinstallDetector.isSignificantTimeDifference(86400000L)
        )
        
        // 7 days difference - significant
        assertTrue(
            ReinstallDetector.isSignificantTimeDifference(604800000L)
        )
    }

    @Test
    fun `getReinstallInfo returns complete data`() {
        val info = ReinstallDetector.getReinstallInfo(
            isReinstall = true,
            timeDifferenceMs = 86400000L,
            storedInstallTime = 1700000000000L,
            currentInstallTime = 1700086400000L
        )
        
        assertNotNull(info)
        assertTrue(info["isReinstall"] == true)
        assertEquals(86400000L, info["timeDifferenceMs"])
    }

    @Test
    fun `validateTimestamp handles edge cases`() {
        // Valid timestamp
        assertTrue(ReinstallDetector.isValidTimestamp(1700000000000L))
        
        // Negative timestamp
        assertFalse(ReinstallDetector.isValidTimestamp(-1L))
        
        // Zero timestamp
        assertFalse(ReinstallDetector.isValidTimestamp(0L))
        
        // Far future timestamp (year 2100+)
        assertFalse(ReinstallDetector.isValidTimestamp(4102444800000L))
    }
}
