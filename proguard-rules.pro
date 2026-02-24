# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK directory.

# BetaFlox SDK ProGuard Rules

# Keep all public API classes
-keep public class com.betaflox.sdk.BetaFloxSDK { *; }
-keep public class com.betaflox.sdk.config.SDKConfig { *; }

# Keep Firebase classes
-keep class com.google.firebase.** { *; }

# Don't warn about missing classes
-dontwarn com.google.firebase.**
