package com.betaflox.sdk.fraud

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import android.content.Context

/**
 * Unit tests for RootDetector
 */
class RootDetectorTest {

    @Test
    fun `checkForSuBinary returns true when su exists`() {
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        
        // Test the path checking logic
        for (path in suPaths) {
            assertTrue(
                "Su binary path $path should be recognized",
                RootDetector.isKnownSuPath(path)
            )
        }
    }

    @Test
    fun `regular system paths are not flagged as su paths`() {
        val normalPaths = listOf(
            "/system/bin/app_process",
            "/system/lib/libc.so",
            "/data/app/com.example.app/base.apk"
        )
        
        for (path in normalPaths) {
            assertFalse(
                "Normal path $path should not be flagged as su path",
                RootDetector.isKnownSuPath(path)
            )
        }
    }

    @Test
    fun `detectMagisk returns true for Magisk package names`() {
        val magiskPackages = listOf(
            "com.topjohnwu.magisk",
            "de.robv.android.xposed.installer",
            "com.saurik.substrate",
            "com.zachspong.temprootremovejb",
            "com.amphoras.hidemyroot",
            "com.formyhm.hideroot"
        )
        
        for (pkg in magiskPackages) {
            assertTrue(
                "Magisk/root package $pkg should be detected",
                RootDetector.isRootManagementPackage(pkg)
            )
        }
    }

    @Test
    fun `normal packages are not flagged as root packages`() {
        val normalPackages = listOf(
            "com.google.android.apps.maps",
            "com.android.chrome",
            "com.facebook.katana"
        )
        
        for (pkg in normalPackages) {
            assertFalse(
                "Normal package $pkg should not be flagged",
                RootDetector.isRootManagementPackage(pkg)
            )
        }
    }

    @Test
    fun `testKeys detection works correctly`() {
        assertTrue(RootDetector.hasTestKeys("test-keys"))
        assertTrue(RootDetector.hasTestKeys("dev-keys"))
        assertFalse(RootDetector.hasTestKeys("release-keys"))
    }

    @Test
    fun `dangerous props are detected`() {
        val dangerousProps = mapOf(
            "ro.debuggable" to "1",
            "ro.secure" to "0"
        )
        
        assertTrue(RootDetector.hasDangerousProps(dangerousProps))
        
        val safeProps = mapOf(
            "ro.debuggable" to "0",
            "ro.secure" to "1"
        )
        
        assertFalse(RootDetector.hasDangerousProps(safeProps))
    }

    @Test
    fun `getRootIndicators returns complete report`() {
        val indicators = RootDetector.getRootIndicators(
            suPathExists = true,
            magiskDetected = false,
            testKeys = false,
            dangerousProps = true
        )
        
        assertNotNull(indicators)
        assertEquals(4, indicators.size)
        assertTrue(indicators["suPathExists"] == true)
        assertTrue(indicators["magiskDetected"] == false)
    }
}
