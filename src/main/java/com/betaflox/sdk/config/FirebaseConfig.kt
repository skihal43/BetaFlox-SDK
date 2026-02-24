package com.betaflox.sdk.config

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Handles Firebase initialization for the BetaFlox SDK.
 * Uses programmatic initialization so host apps don't need google-services.json.
 */
internal object FirebaseConfig {
    
    private const val TAG = "FirebaseConfig"
    private const val FIREBASE_APP_NAME = "betaflox_sdk"
    
    // BetaFlox Firebase project credentials
    private const val PROJECT_ID = "betaflox-ffb7b"
    private const val APPLICATION_ID = "1:355310242108:android:ad7c7a8ff01a548d067639"
    private const val API_KEY = "AIzaSyDr3N7PGBkKycWMlYKBcGS6ONTJfqSO-Ns"
    private const val STORAGE_BUCKET = "betaflox-ffb7b.firebasestorage.app"
    
    private var firebaseApp: FirebaseApp? = null
    
    /**
     * Initialize Firebase with SDK's own configuration.
     * This creates a separate FirebaseApp instance named "betaflox_sdk"
     * so it doesn't conflict with the host app's Firebase configuration.
     * 
     * @param context Application context
     * @return The initialized FirebaseApp, or null if initialization failed
     */
    fun initialize(context: Context): FirebaseApp? {
        // Return existing instance if already initialized
        firebaseApp?.let { return it }
        
        return try {
            // Check if our app already exists
            val existingApp = try {
                FirebaseApp.getInstance(FIREBASE_APP_NAME)
            } catch (e: IllegalStateException) {
                null
            }
            
            if (existingApp != null) {
                Log.d(TAG, "Firebase app '$FIREBASE_APP_NAME' already exists")
                firebaseApp = existingApp
                return existingApp
            }
            
            // Create Firebase options with SDK credentials
            val options = FirebaseOptions.Builder()
                .setProjectId(PROJECT_ID)
                .setApplicationId(APPLICATION_ID)
                .setApiKey(API_KEY)
                .setStorageBucket(STORAGE_BUCKET)
                .build()
            
            // Initialize with a unique name so it doesn't conflict with host app
            val app = FirebaseApp.initializeApp(context, options, FIREBASE_APP_NAME)
            firebaseApp = app
            
            Log.i(TAG, "Firebase initialized successfully for BetaFlox SDK")
            app
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase for SDK", e)
            null
        }
    }
    
    /**
     * Get the SDK's FirebaseApp instance.
     * Returns null if not initialized.
     */
    fun getFirebaseApp(): FirebaseApp? = firebaseApp
}
