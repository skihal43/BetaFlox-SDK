package com.betaflox.sdk.fraud

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.opengl.GLES20
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.NetworkInterface

/**
 * Robust emulator detection using multi-signal confidence scoring.
 * 
 * This detector aggregates multiple weak signals into a confidence score,
 * avoiding reliance on any single detection method that could be spoofed.
 * 
 * Supports: Android Emulator, Genymotion, BlueStacks, Nox, LDPlayer
 * Target: Android 10 (API 29) through Android 14 (API 34)
 * 
 * No dangerous permissions required.
 */
internal class EmulatorDetector(private val context: Context) {
    
    companion object {
        private const val TAG = "EmulatorDetector"
        
        /**
         * Default threshold for emulator detection.
         * A device with score >= threshold is considered an emulator.
         * Can be configured via Firebase Remote Config.
         */
        const val DEFAULT_THRESHOLD = 0.75f
        
        // ============ Signal ID Constants ============
        private const val SIGNAL_BUILD_PROPERTIES = "BUILD_PROPERTIES"
        private const val SIGNAL_HARDWARE_NAME = "HARDWARE_NAME"
        private const val SIGNAL_SENSOR_MISSING = "SENSOR_MISSING"
        private const val SIGNAL_QEMU_FILES = "QEMU_FILES"
        private const val SIGNAL_QEMU_PROPERTIES = "QEMU_PROPERTIES"
        private const val SIGNAL_EMULATOR_FILES = "EMULATOR_FILES"
        private const val SIGNAL_BUILD_TAGS = "BUILD_TAGS"
        private const val SIGNAL_CPU_ABI = "CPU_ABI"
        private const val SIGNAL_SHELL_PROPS = "SHELL_PROPS"
        private const val SIGNAL_NETWORK_INTERFACES = "NETWORK_INTERFACES"
        
        // ============ Detection Constants ============
        
        // Known emulator fingerprint patterns
        private val EMULATOR_FINGERPRINTS = listOf(
            "generic", "unknown", "google_sdk", "sdk_gphone", 
            "vbox86p", "nox", "ttvm_hdragon", "andy", "droid4x"
        )
        
        // Known emulator model patterns
        private val EMULATOR_MODELS = listOf(
            "sdk", "emulator", "android sdk built for", "google_sdk",
            "sdk_gphone", "sdk_gphone64", "sdk_gphone_x86"
        )
        
        // Known emulator hardware names
        private val EMULATOR_HARDWARE = listOf(
            "goldfish", "ranchu", "vbox86", "nox", "ttvm_x86"
        )
        
        // Known emulator product names
        private val EMULATOR_PRODUCTS = listOf(
            "sdk", "sdk_x86", "sdk_google", "vbox86p", "emulator",
            "sdk_gphone", "sdk_gphone64", "nox"
        )
        
        // Known emulator board names
        private val EMULATOR_BOARDS = listOf(
            "goldfish", "ranchu", "unknown", "nox"
        )
        
        // QEMU system files
        private val QEMU_FILES = listOf(
            "/dev/qemu_pipe",
            "/dev/qemu_trace",
            "/dev/goldfish_pipe",
            "/dev/socket/qemud",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace",
            "/system/bin/qemu-props"
        )
        
        // Emulator-specific paths (expanded for Nox/LDPlayer detection)
        private val EMULATOR_FILES = listOf(
            // Genymotion
            "/dev/vboxguest",
            "/dev/vboxuser",
            "/system/lib/vboxsf.ko",
            "/system/lib/vboxguest.ko",
            "/system/xbin/mount.vboxsf",
            "/data/youwave_id",
            // Nox/LDPlayer - comprehensive list
            "/fstab.nox",
            "/init.nox.rc",
            "/ueventd.nox.rc",
            "/data/data/com.bignox.app",
            "/data/data/com.bignox.app.store.hd",
            "/data/bignox",
            "/system/bin/nox",
            "/system/bin/noxd",
            "/system/lib/libnoxd.so",
            "/system/lib/libnoxspeedup.so",
            "/system/priv-app/NoxHelp",
            "/system/app/NoxHelp",
            "/data/property/persist.nox.simulator_version",
            // LDPlayer/Titan
            "/fstab.titan",
            "/init.titan.rc",
            "/data/data/com.ldplayer",
            "/data/data/com.changzhi.ldplayer",
            // BlueStacks
            "/data/bluestacks.prop",
            "/sdcard/windows/BstSharedFolder",
            "/data/data/com.bluestacks.appmart",
            "/data/data/com.bluestacks.settings",
            // MEmu
            "/data/data/com.microvirt.memuime",
            "/data/data/com.microvirt.tools",
            "/init.memu.rc",
            // Generic x86/emulator
            "/init.android_x86.rc",
            "/system/bin/androVM-vbox-sf",
            "/ueventd.android_x86.rc",
            "/system/etc/init.androVM.sh",
            "/ueventd.vbox86.rc"
        )
        
        // QEMU and emulator system properties to check
        private val QEMU_PROPERTIES = listOf(
            "init.svc.qemud",
            "init.svc.qemu-props",
            "qemu.hw.mainkeys",
            "qemu.sf.fake_camera",
            "qemu.sf.lcd_density",
            "ro.kernel.qemu",
            "ro.kernel.qemu.gles",
            "ro.kernel.android.qemud",
            "ro.hardware.audio.primary",
            // Nox-specific properties
            "init.svc.noxd",
            "ro.nox.features",
            "persist.nox.simulator_version",
            "ro.bignox.features",
            // LDPlayer properties
            "ro.ldplayer.features",
            // Generic emulator properties
            "ro.setupwizard.mode",
            "ro.hardware.virtual_device"
        )
        
        // x86-based ABIs that indicate emulator on most devices
        private val EMULATOR_ABIS = listOf(
            "x86", "x86_64"
        )

        // ============ Static Utility Methods for Testing ============
        
        @JvmStatic
        fun checkFingerprintForEmulator(fingerprint: String): Boolean {
            return EMULATOR_FINGERPRINTS.any { 
                fingerprint.lowercase().contains(it.lowercase()) 
            }
        }

        @JvmStatic
        fun checkModelForEmulator(model: String): Boolean {
            return EMULATOR_MODELS.any { 
                model.lowercase().contains(it.lowercase()) 
            }
        }

        @JvmStatic
        fun checkProductForEmulator(product: String): Boolean {
            return EMULATOR_PRODUCTS.any { 
                product.lowercase().contains(it.lowercase()) 
            }
        }

        @JvmStatic
        fun checkHardwareForEmulator(hardware: String): Boolean {
            return EMULATOR_HARDWARE.any { 
                hardware.lowercase().contains(it.lowercase()) 
            }
        }

        @JvmStatic
        fun checkBuildTagsForEmulator(tags: String): Boolean {
            return tags.contains("test-keys") || tags.contains("dev-keys")
        }

        @JvmStatic
        fun getAllEmulatorIndicators(
            fingerprint: String,
            model: String,
            product: String,
            brand: String,
            device: String,
            hardware: String,
            buildTags: String
        ): Map<String, Boolean> {
            return mapOf(
                "fingerprint" to checkFingerprintForEmulator(fingerprint),
                "model" to checkModelForEmulator(model),
                "product" to checkProductForEmulator(product),
                "brand" to (brand.lowercase() == "generic"),
                "device" to (device.lowercase() == "generic" || device.lowercase().contains("emu")),
                "hardware" to checkHardwareForEmulator(hardware),
                "buildTags" to checkBuildTagsForEmulator(buildTags)
            )
        }
    }
    
    // Configurable threshold (can be updated via Remote Config)
    private var threshold: Float = DEFAULT_THRESHOLD
    
    // Cached detection result
    private var cachedResult: EmulatorDetectionResult? = null
    
    /**
     * Update the detection threshold.
     * Call this after fetching from Firebase Remote Config.
     * 
     * @param newThreshold Value between 0.0 and 1.0
     */
    fun setThreshold(newThreshold: Float) {
        if (newThreshold in 0f..1f) {
            threshold = newThreshold
            cachedResult = null // Clear cache when threshold changes
            Log.d(TAG, "Threshold updated to $newThreshold")
        } else {
            Log.w(TAG, "Invalid threshold $newThreshold, must be 0.0-1.0")
        }
    }
    
    /**
     * Get the current threshold.
     */
    fun getThreshold(): Float = threshold
    
    /**
     * Check if the device is an emulator.
     * Uses confidence scoring with configurable threshold.
     * Results are cached for performance.
     * 
     * @return true if confidence score >= threshold
     */
    fun isEmulator(): Boolean {
        return getDetectionResult().isEmulator
    }
    
    /**
     * Get the full detection result with confidence score and signals.
     * Results are cached for performance.
     */
    fun getDetectionResult(): EmulatorDetectionResult {
        cachedResult?.let { return it }
        
        val result = performDetection()
        cachedResult = result
        
        if (result.isEmulator) {
            Log.w(TAG, "Emulator detected: ${result.toSummary()}")
        } else {
            Log.d(TAG, "Real device: ${result.toSummary()}")
        }
        
        return result
    }
    
    /**
     * Get emulator confidence score (0.0 to 1.0).
     */
    fun getConfidenceScore(): Float {
        return getDetectionResult().confidenceScore
    }
    
    /**
     * Clear cached result to force re-detection.
     */
    fun clearCache() {
        cachedResult = null
    }
    
    /**
     * Get details about detected signals (for debugging).
     */
    fun getEmulatorDetails(): Map<String, String> {
        return mapOf(
            "fingerprint" to Build.FINGERPRINT,
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "device" to Build.DEVICE,
            "hardware" to Build.HARDWARE,
            "product" to Build.PRODUCT,
            "board" to Build.BOARD,
            "tags" to Build.TAGS,
            "confidenceScore" to getConfidenceScore().toString(),
            "threshold" to threshold.toString(),
            "isEmulator" to isEmulator().toString()
        )
    }
    
    // ============ Detection Implementation ============
    
    private fun performDetection(): EmulatorDetectionResult {
        val signals = mutableListOf<EmulatorSignal>()
        
        // Signal 1: Build Properties (weight: 0.20)
        signals.add(checkBuildProperties())
        
        // Signal 2: Hardware Names (weight: 0.20)
        signals.add(checkHardwareNames())
        
        // Signal 3: Sensor Availability (weight: 0.15)
        signals.add(checkSensors())
        
        // Signal 4: QEMU Files (weight: 0.20)
        signals.add(checkQemuFiles())
        
        // Signal 5: QEMU System Properties (weight: 0.10)
        signals.add(checkQemuProperties())
        
        // Signal 6: Emulator-Specific Files (weight: 0.15 - increased for Nox detection)
        signals.add(checkEmulatorFiles())
        
        // Signal 7: Build Tags (weight: 0.05)
        signals.add(checkBuildTags())
        
        // Signal 8: CPU ABI (weight: 0.15 - x86 on phone is very suspicious)
        signals.add(checkCpuAbi())
        
        // Signal 9: Shell getprop detection (weight: 0.20 - harder to spoof)
        signals.add(checkShellProperties())
        
        // Signal 10: Network interfaces (weight: 0.15 - emulators have specific interfaces)
        signals.add(checkNetworkInterfaces())
        
        // Calculate weighted confidence score
        val triggeredSignals = signals.filter { it.isTriggered }
        val confidenceScore = triggeredSignals
            .sumOf { it.weight.toDouble() }
            .toFloat()
            .coerceIn(0f, 1f)
        
        return EmulatorDetectionResult(
            isEmulator = confidenceScore >= threshold,
            confidenceScore = confidenceScore,
            threshold = threshold,
            signals = signals.toList(),
            triggeredSignals = triggeredSignals
        )
    }
    
    /**
     * Signal 1: Check Build.* properties for emulator signatures.
     * Weight: 0.20 (high - these are strong indicators)
     */
    private fun checkBuildProperties(): EmulatorSignal {
        val checks = mutableListOf<String>()
        var isTriggered = false
        
        // Check fingerprint
        if (checkFingerprintForEmulator(Build.FINGERPRINT)) {
            checks.add("fingerprint=${Build.FINGERPRINT}")
            isTriggered = true
        }
        
        // Check model
        if (checkModelForEmulator(Build.MODEL)) {
            checks.add("model=${Build.MODEL}")
            isTriggered = true
        }
        
        // Check product
        if (checkProductForEmulator(Build.PRODUCT)) {
            checks.add("product=${Build.PRODUCT}")
            isTriggered = true
        }
        
        // Check brand = generic
        if (Build.BRAND.lowercase() == "generic") {
            checks.add("brand=${Build.BRAND}")
            isTriggered = true
        }
        
        // Check device = generic or contains "emu"
        if (Build.DEVICE.lowercase() == "generic" || 
            Build.DEVICE.lowercase().contains("emu")) {
            checks.add("device=${Build.DEVICE}")
            isTriggered = true
        }
        
        return EmulatorSignal(
            signalId = SIGNAL_BUILD_PROPERTIES,
            displayName = "Build Properties",
            weight = 0.20f,
            isTriggered = isTriggered,
            rawValue = if (checks.isNotEmpty()) checks.joinToString("; ") else "none"
        )
    }
    
    /**
     * Signal 2: Check hardware names for emulator indicators.
     * Weight: 0.20 (high - goldfish/ranchu are definitive)
     */
    private fun checkHardwareNames(): EmulatorSignal {
        val checks = mutableListOf<String>()
        var isTriggered = false
        
        // Check hardware
        if (checkHardwareForEmulator(Build.HARDWARE)) {
            checks.add("hardware=${Build.HARDWARE}")
            isTriggered = true
        }
        
        // Check board
        if (EMULATOR_BOARDS.any { Build.BOARD.lowercase().contains(it.lowercase()) }) {
            checks.add("board=${Build.BOARD}")
            isTriggered = true
        }
        
        return EmulatorSignal(
            signalId = SIGNAL_HARDWARE_NAME,
            displayName = "Hardware Name",
            weight = 0.20f,
            isTriggered = isTriggered,
            rawValue = if (checks.isNotEmpty()) checks.joinToString("; ") else "none"
        )
    }
    
    /**
     * Signal 3: Check for missing physical sensors.
     * Weight: 0.15 (medium - some cheap devices may lack sensors too)
     */
    private fun checkSensors(): EmulatorSignal {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        
        val hasAccelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasGyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val hasMagneticField = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
        val hasProximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
        
        // Count missing critical sensors
        val missingSensors = mutableListOf<String>()
        if (!hasAccelerometer) missingSensors.add("accelerometer")
        if (!hasGyroscope) missingSensors.add("gyroscope")
        if (!hasMagneticField) missingSensors.add("magneticField")
        if (!hasProximity) missingSensors.add("proximity")
        
        // Trigger if missing accelerometer (critical sensor) OR if missing 2+ other sensors
        // Accelerometer is present on virtually all real phones since 2010
        val isTriggered = !hasAccelerometer || missingSensors.size >= 3
        
        return EmulatorSignal(
            signalId = SIGNAL_SENSOR_MISSING,
            displayName = "Missing Sensors",
            weight = 0.15f,
            isTriggered = isTriggered,
            rawValue = if (missingSensors.isNotEmpty()) 
                "missing: ${missingSensors.joinToString(", ")}" 
                else "all present"
        )
    }
    
    /**
     * Signal 4: Check for QEMU-related files.
     * Weight: 0.20 (high - these are definitive emulator indicators)
     */
    private fun checkQemuFiles(): EmulatorSignal {
        val foundFiles = QEMU_FILES.filter { path ->
            try {
                File(path).exists()
            } catch (e: Exception) {
                false
            }
        }
        
        return EmulatorSignal(
            signalId = SIGNAL_QEMU_FILES,
            displayName = "QEMU Files",
            weight = 0.20f,
            isTriggered = foundFiles.isNotEmpty(),
            rawValue = if (foundFiles.isNotEmpty()) 
                foundFiles.joinToString(", ") 
                else "none"
        )
    }
    
    /**
     * Signal 5: Check for QEMU system properties.
     * Weight: 0.10 (medium - some properties may not be accessible)
     */
    private fun checkQemuProperties(): EmulatorSignal {
        val foundProps = QEMU_PROPERTIES.filter { prop ->
            try {
                val value = System.getProperty(prop)
                !value.isNullOrBlank()
            } catch (e: Exception) {
                false
            }
        }
        
        // Also check ro.kernel.qemu specifically
        val qemuKernel = try {
            System.getProperty("ro.kernel.qemu") == "1"
        } catch (e: Exception) {
            false
        }
        
        val isTriggered = foundProps.isNotEmpty() || qemuKernel
        
        return EmulatorSignal(
            signalId = SIGNAL_QEMU_PROPERTIES,
            displayName = "QEMU Properties",
            weight = 0.10f,
            isTriggered = isTriggered,
            rawValue = if (qemuKernel) "ro.kernel.qemu=1" 
                else if (foundProps.isNotEmpty()) foundProps.joinToString(", ")
                else "none"
        )
    }
    
    /**
     * Signal 6: Check for emulator-specific files (Genymotion, BlueStacks, Nox).
     * Weight: 0.10 (medium - specific to certain emulators)
     */
    private fun checkEmulatorFiles(): EmulatorSignal {
        val foundFiles = EMULATOR_FILES.filter { path ->
            try {
                File(path).exists()
            } catch (e: Exception) {
                false
            }
        }
        
        return EmulatorSignal(
            signalId = SIGNAL_EMULATOR_FILES,
            displayName = "Emulator Files",
            weight = 0.15f,  // Increased weight for Nox detection
            isTriggered = foundFiles.isNotEmpty(),
            rawValue = if (foundFiles.isNotEmpty()) 
                foundFiles.joinToString(", ") 
                else "none"
        )
    }
    
    /**
     * Signal 7: Check build tags for test/dev keys.
     * Weight: 0.05 (low - some rooted devices also have test-keys)
     */
    private fun checkBuildTags(): EmulatorSignal {
        val tags = Build.TAGS ?: ""
        val isTriggered = checkBuildTagsForEmulator(tags)
        
        return EmulatorSignal(
            signalId = SIGNAL_BUILD_TAGS,
            displayName = "Build Tags",
            weight = 0.05f,
            isTriggered = isTriggered,
            rawValue = tags.ifEmpty { "none" }
        )
    }
    
    /**
     * Signal 8: Check CPU ABI for x86/x86_64 architecture.
     * Weight: 0.15 (high - x86 on a phone is very suspicious)
     * 
     * Most real Android phones use ARM architecture.
     * Emulators often run x86/x86_64 for performance.
     */
    private fun checkCpuAbi(): EmulatorSignal {
        val supportedAbis = Build.SUPPORTED_ABIS ?: emptyArray()
        val primaryAbi = Build.CPU_ABI ?: ""
        
        // Check if any supported ABI is x86-based
        val hasX86Abi = supportedAbis.any { abi ->
            EMULATOR_ABIS.any { emulatorAbi -> 
                abi.lowercase().contains(emulatorAbi) 
            }
        }
        
        // Also check primary CPU ABI
        val primaryIsX86 = EMULATOR_ABIS.any { 
            primaryAbi.lowercase().contains(it) 
        }
        
        val isTriggered = hasX86Abi || primaryIsX86
        val abiInfo = "primary=$primaryAbi; supported=${supportedAbis.joinToString(",")}"
        
        return EmulatorSignal(
            signalId = SIGNAL_CPU_ABI,
            displayName = "CPU Architecture",
            weight = 0.15f,
            isTriggered = isTriggered,
            rawValue = if (isTriggered) "x86 detected: $abiInfo" else abiInfo
        )
    }
    
    /**
     * Signal 9: Check system properties via shell getprop command.
     * Weight: 0.20 (high - harder to spoof than Build.* fields)
     * 
     * This bypasses Java-level spoofing by directly reading from shell.
     */
    private fun checkShellProperties(): EmulatorSignal {
        val emulatorProps = mutableListOf<String>()
        
        // Properties to check via shell
        val propsToCheck = listOf(
            "ro.hardware" to listOf("goldfish", "ranchu", "nox", "vbox", "ttVM"),
            "ro.product.model" to listOf("sdk", "emulator", "android sdk"),
            "ro.product.brand" to listOf("generic"),
            "ro.product.device" to listOf("generic", "vbox86p", "nox"),
            "ro.product.name" to listOf("sdk", "vbox86p", "nox"),
            "ro.kernel.qemu" to listOf("1"),
            "ro.boot.hardware" to listOf("goldfish", "ranchu", "vbox"),
            "init.svc.noxd" to listOf("running"),
            "ro.nox" to listOf(""),  // Any value means Nox
            "ro.bignox.version" to listOf("")  // Any value means Nox
        )
        
        for ((prop, patterns) in propsToCheck) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("getprop", prop))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val value = reader.readLine()?.trim()?.lowercase() ?: ""
                reader.close()
                process.waitFor()
                
                // For props where any value indicates emulator (empty pattern list means "exists")
                val isMatch = if (patterns.isEmpty() || (patterns.size == 1 && patterns[0].isEmpty())) {
                    value.isNotEmpty()
                } else {
                    patterns.any { pattern -> value.contains(pattern.lowercase()) }
                }
                
                if (isMatch && value.isNotEmpty()) {
                    emulatorProps.add("$prop=$value")
                }
            } catch (e: Exception) {
                // getprop failed - expected on some devices
            }
        }
        
        return EmulatorSignal(
            signalId = SIGNAL_SHELL_PROPS,
            displayName = "Shell Properties",
            weight = 0.20f,
            isTriggered = emulatorProps.isNotEmpty(),
            rawValue = if (emulatorProps.isNotEmpty()) 
                emulatorProps.joinToString("; ") 
                else "none"
        )
    }
    
    /**
     * Signal 10: Check network interfaces for emulator patterns.
     * Weight: 0.15 (medium - emulators often have specific network interfaces)
     * 
     * Emulators typically have eth0, vboxnet, or similar interfaces
     * instead of wlan0/rmnet used by real phones.
     */
    private fun checkNetworkInterfaces(): EmulatorSignal {
        val suspiciousInterfaces = mutableListOf<String>()
        
        // Emulator-specific network interface patterns
        val emulatorInterfacePatterns = listOf(
            "eth0",      // Common in emulators
            "eth1",
            "vboxnet",   // VirtualBox
            "vnic",      // Virtual NIC
            "veth",      // Virtual ethernet
            "bridge"     // Bridge interface
        )
        
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces?.hasMoreElements() == true) {
                val netInterface = interfaces.nextElement()
                val name = netInterface.name.lowercase()
                
                for (pattern in emulatorInterfacePatterns) {
                    if (name.contains(pattern)) {
                        suspiciousInterfaces.add(name)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // Network interface enumeration failed
            Log.d(TAG, "Network interface check failed: ${e.message}")
        }
        
        return EmulatorSignal(
            signalId = SIGNAL_NETWORK_INTERFACES,
            displayName = "Network Interfaces",
            weight = 0.15f,
            isTriggered = suspiciousInterfaces.isNotEmpty(),
            rawValue = if (suspiciousInterfaces.isNotEmpty()) 
                "suspicious: ${suspiciousInterfaces.joinToString(", ")}" 
                else "normal"
        )
    }
}
