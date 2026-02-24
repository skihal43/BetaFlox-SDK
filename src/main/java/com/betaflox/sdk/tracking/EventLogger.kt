package com.betaflox.sdk.tracking

import android.content.Context
import android.util.Log
import com.betaflox.sdk.BetaFloxSDK
import com.betaflox.sdk.config.SDKConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Local event queue that stores events before syncing to Firebase.
 * Uses SharedPreferences for persistence across app restarts.
 */
internal class EventLogger(
    private val context: Context,
    private val config: SDKConfig
) {
    companion object {
        private const val TAG = "EventLogger"
        private const val PREFS_EVENTS = "betaflox_events"
        private const val KEY_EVENT_QUEUE = "event_queue"
        private const val MAX_QUEUE_SIZE = 1000
    }

    object EventTypes {
        const val APP_OPEN = "app_open"
        const val APP_CLOSE = "app_close"
        const val SESSION_DURATION = "session_duration"
        const val DAILY_CHECKIN = "daily_checkin"
        const val HEARTBEAT = "heartbeat"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_EVENTS, Context.MODE_PRIVATE)
    private val eventQueue = mutableListOf<SDKEvent>()
    
    init {
        // Load any persisted events from previous session
        loadPersistedEvents()
    }
    
    private fun getDeviceHash(): String? {
        // Retrieve device hash from SDK prefs (set by DeviceFingerprint)
        return try {
            val sdkPrefs = context.getSharedPreferences(SDKConfig.PREFS_NAME, Context.MODE_PRIVATE)
            sdkPrefs.getString(SDKConfig.KEY_DEVICE_HASH, null)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Log daily check-in event.
     */
    fun logDailyCheckin(dayIndex: Int) {
        val event = SDKEvent(
            id = UUID.randomUUID().toString(),
            eventType = EventTypes.DAILY_CHECKIN,
            timestamp = System.currentTimeMillis(),
            testerId = config.testerId ?: "",
            deviceHash = getDeviceHash(),
            campaignId = config.campaignId,
            packageName = getPackageNameSafe(),
            data = mapOf("dayIndex" to dayIndex),
            fraudFlags = getFraudFlagsSafe()
        )
        queueEvent(event)
    }

    private fun queueEvent(event: SDKEvent) {
        synchronized(eventQueue) {
            // Cap queue size to prevent unbounded growth
            if (eventQueue.size >= MAX_QUEUE_SIZE) {
                eventQueue.removeAt(0) // Drop oldest event
                Log.w(TAG, "Event queue full, dropping oldest event")
            }
            eventQueue.add(event)
            persistEvents()
        }
        Log.d(TAG, "Event logged: ${event.eventType} (queue size: ${eventQueue.size})")
    }
    
    /**
     * Log an event to the queue.
     * 
     * @param eventType The type of event (app_open, app_close, session_duration)
     * @param data Additional event data
     */
    fun logEvent(eventType: String, data: Map<String, Any> = emptyMap()) {
        val event = SDKEvent(
            id = UUID.randomUUID().toString(),
            eventType = eventType,
            timestamp = System.currentTimeMillis(),
            testerId = config.testerId ?: "",
            deviceHash = getDeviceHash(),
            campaignId = config.campaignId,
            packageName = getPackageNameSafe(),
            data = data,
            fraudFlags = getFraudFlagsSafe()
        )
        
        queueEvent(event)
    }
    
    /**
     * Safely get fraud flags, returning empty map if SDK not fully initialized.
     */
    private fun getFraudFlagsSafe(): Map<String, Boolean> {
        return try {
            if (BetaFloxSDK.isInitialized()) {
                BetaFloxSDK.getFraudFlags()
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get fraud flags: ${e.message}")
            emptyMap()
        }
    }
    
    /**
     * Safely get package name, returning empty string if not available.
     */
    private fun getPackageNameSafe(): String {
        return try {
            BetaFloxSDK.getPackageName()
        } catch (e: Exception) {
            context.packageName
        }
    }
    
    /**
     * Get all pending events for sync.
     */
    fun getPendingEvents(): List<SDKEvent> {
        synchronized(eventQueue) {
            return eventQueue.toList()
        }
    }
    
    /**
     * Get pending events up to a limit.
     */
    fun getPendingEvents(limit: Int): List<SDKEvent> {
        synchronized(eventQueue) {
            return eventQueue.take(limit)
        }
    }
    
    /**
     * Remove events that have been successfully synced.
     */
    fun removeEvents(eventIds: List<String>) {
        synchronized(eventQueue) {
            eventQueue.removeAll { it.id in eventIds }
            persistEvents()
        }
        Log.d(TAG, "Removed ${eventIds.size} synced events")
    }
    
    /**
     * Get the count of pending events.
     */
    fun getPendingCount(): Int {
        synchronized(eventQueue) {
            return eventQueue.size
        }
    }
    
    /**
     * Clear all pending events.
     */
    fun clearEvents() {
        synchronized(eventQueue) {
            eventQueue.clear()
            prefs.edit().remove(KEY_EVENT_QUEUE).apply()
        }
    }
    
    private fun persistEvents() {
        try {
            val jsonArray = JSONArray()
            eventQueue.forEach { event ->
                jsonArray.put(event.toJson())
            }
            prefs.edit().putString(KEY_EVENT_QUEUE, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist events", e)
        }
    }
    
    private fun loadPersistedEvents() {
        try {
            val json = prefs.getString(KEY_EVENT_QUEUE, null) ?: return
            val jsonArray = JSONArray(json)
            
            for (i in 0 until jsonArray.length()) {
                val eventJson = jsonArray.getJSONObject(i)
                val event = SDKEvent.fromJson(eventJson)
                eventQueue.add(event)
            }
            
            Log.d(TAG, "Loaded ${eventQueue.size} persisted events")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted events", e)
        }
    }
}

/**
 * Data class representing an SDK event.
 */
internal data class SDKEvent(
    val id: String,
    val eventType: String,
    val timestamp: Long,
    val testerId: String,
    val deviceHash: String? = null,
    val campaignId: String,
    val packageName: String,
    val data: Map<String, Any>,
    val fraudFlags: Map<String, Boolean>
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("eventType", eventType)
            put("timestamp", timestamp)
            put("testerId", testerId)
            put("deviceHash", deviceHash)
            put("campaignId", campaignId)
            put("packageName", packageName)
            put("data", JSONObject(data))
            put("fraudFlags", JSONObject(fraudFlags.mapValues { it.value }))
        }
    }
    
    fun toFirestoreMap(): Map<String, Any?> {
        return mapOf(
            "eventType" to eventType,
            "timestamp" to timestamp,
            "testerId" to testerId,
            "deviceHash" to deviceHash,
            "campaignId" to campaignId,
            "packageName" to packageName,
            "fraudFlags" to fraudFlags,
            "eventData" to data // Include all custom data
        )
    }
    
    companion object {
        fun fromJson(json: JSONObject): SDKEvent {
            val dataJson = json.optJSONObject("data") ?: JSONObject()
            val fraudJson = json.optJSONObject("fraudFlags") ?: JSONObject()
            
            val dataMap = mutableMapOf<String, Any>()
            dataJson.keys().forEach { key ->
                dataMap[key] = dataJson.get(key)
            }
            
            val fraudMap = mutableMapOf<String, Boolean>()
            fraudJson.keys().forEach { key ->
                fraudMap[key] = fraudJson.getBoolean(key)
            }
            
            return SDKEvent(
                id = json.getString("id"),
                eventType = json.getString("eventType"),
                timestamp = json.getLong("timestamp"),
                testerId = json.optString("testerId", ""),
                deviceHash = json.optString("deviceHash", "").takeIf { it.isNotEmpty() },
                campaignId = json.getString("campaignId"),
                packageName = json.getString("packageName"),
                data = dataMap,
                fraudFlags = fraudMap
            )
        }
    }
}

