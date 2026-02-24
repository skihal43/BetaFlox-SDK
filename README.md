# BetaFlox SDK

Android library for integrating BetaFlox tester tracking into your app.

## Features

- **Automatic Session Tracking** - Tracks app opens, closes, and session duration
- **Anti-Fraud Detection** - Detects emulators, rooted devices, and reinstalls
- **Offline Support** - Events are queued locally and synced when online
- **Minimal Setup** - Just 3 lines of code to integrate

## Installation

### Step 1: Add the SDK to your project

Add the AAR file to your `libs/` folder, then add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("libs/betaflox-sdk.aar"))
    
    // Required dependencies
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

### Step 2: Initialize the SDK

In your `Application` class:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize BetaFlox SDK
        BetaFloxSDK.initialize(
            context = this,
            apiKey = "your-api-key",
            campaignId = "your-campaign-id"
        )
    }
}
```

### Step 3: Set the Tester ID

After user authentication:

```kotlin
// In your login/auth flow
fun onUserAuthenticated(userId: String) {
    BetaFloxSDK.setTesterId(userId)
}
```

That's it! The SDK will now automatically track:
- App opens and closes
- Session duration
- Fraud indicators

## API Reference

### BetaFloxSDK

| Method | Description |
|--------|-------------|
| `initialize(context, apiKey, campaignId)` | Initialize the SDK (call once in Application) |
| `setTesterId(testerId)` | Set the authenticated tester's user ID |
| `getDeviceHash()` | Get unique device fingerprint |
| `getFraudFlags()` | Get map of fraud detection results |
| `getCurrentSessionDuration()` | Get current session length in seconds |
| `syncNow()` | Force immediate sync of pending events |
| `shutdown()` | Clean up SDK resources |

### Fraud Detection Flags

```kotlin
val flags = BetaFloxSDK.getFraudFlags()
// Returns: Map<String, Boolean>
// - "isEmulator": true if running on emulator
// - "isRooted": true if device is rooted
// - "isReinstall": true if app was reinstalled
```

## Requirements

- Android API 24+ (Android 7.0)
- Firebase project with Firestore enabled
- Kotlin 1.9+

## Permissions

The SDK requires these permissions (automatically merged):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## ProGuard

The SDK includes consumer ProGuard rules automatically. No additional configuration needed.

## Support

For issues or questions, contact: support@betaflox.com
