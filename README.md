# BetaFlox SDK

Android library for integrating BetaFlox tester tracking into your app.

## Features

- **Automatic Session Tracking** - Tracks app opens, closes, and session duration
- **Anti-Fraud Detection** - Detects emulators, rooted devices, and reinstalls
- **Offline Support** - Events are queued locally and synced when online
- **Minimal Setup** - Just 3 lines of code to integrate

## Installation

### Step 1: Configure Repository

In your application's `settings.gradle.kts` (or root `build.gradle.kts`), ensure JitPack is added to your dependency resolution management:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### Step 2: Add Dependency

Add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.skihal43:BetaFlox-SDK:1.0.22")
}
```

## Resolving Manifest Merge Errors

When integrating third-party SDKs, especially those relying on newer AndroidX or Firebase libraries, you might encounter a manifest merge error similar to this:

```
Attribute application@appComponentFactory value=(android.support.v4.app.CoreComponentFactory)
from [com.android.support:support-compat:28.0.0] AndroidManifest.xml:22:18-91
is also present at [androidx.core:core:1.13.1] AndroidManifest.xml:24:18-86 value=(androidx.core.app.CoreComponentFactory).
```

This conflict occurs because older libraries use the legacy Android Support Library, while newer ones (like BetaFlox SDK or Firebase) use AndroidX.

### The Solution: Enable Jetifier

Jetifier is an Android build tool that automatically migrates legacy Support Library dependencies to AndroidX equivalents during the build process.

To fix the manifest merge conflict, open your application's `gradle.properties` file and ensure the following flags are set to `true`:

```properties
android.useAndroidX=true
# Enable Jetifier to automatically migrate 3rd party libraries to AndroidX
android.enableJetifier=true
```

After modifying `gradle.properties`, synchronize your Gradle project and rebuild the application.

## Initialization

Initialize the SDK in your Application class and replace old lifecycle tracking overrides:

```kotlin
import com.betaflox.sdk.BetaFloxSDK

class InitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        BetaFloxSDK.initialize(
            context = this,
            apiKey = "YOUR_API_KEY",
            campaignId = "YOUR_CAMPAIGN_ID"
        )
        // No need to manually call onAppForegrounded or onAppBackgrounded; 
        // The SDK does it automatically!
    }
}
```

### Setting the Tester ID

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
- Kotlin 1.9+

> **Note on Backend**: The BetaFlox SDK securely connects directly to our infrastructure. **You do NOT need to configure your own Firebase project or backend servers.** Just use your API Key and Campaign ID from the dashboard.

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
