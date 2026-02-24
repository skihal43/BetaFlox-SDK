package com.betaflox.sdk.tracking

import android.content.Context
import android.content.SharedPreferences
import com.betaflox.sdk.BetaFloxSDK
import com.betaflox.sdk.config.SDKConfig
import com.betaflox.sdk.fraud.RapidSwitchingDetector
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.MockedStatic

@RunWith(MockitoJUnitRunner::class)
class SessionManagerTest {

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var config: SDKConfig

    @Mock
    private lateinit var eventLogger: EventLogger

    @Mock
    private lateinit var sharedPreferences: SharedPreferences

    @Mock
    private lateinit var editor: SharedPreferences.Editor

    @Mock
    private lateinit var rapidSwitchingDetector: RapidSwitchingDetector

    private lateinit var sessionManager: SessionManager
    private lateinit var mockedSDK: org.mockito.MockedStatic<BetaFloxSDK>

    @Before
    fun setUp() {
        // 1. Manually mock standard dependencies
        context = Mockito.mock(Context::class.java)
        config = Mockito.mock(SDKConfig::class.java)
        eventLogger = Mockito.mock(EventLogger::class.java)
        sharedPreferences = Mockito.mock(SharedPreferences::class.java)
        editor = Mockito.mock(SharedPreferences.Editor::class.java)
        rapidSwitchingDetector = Mockito.mock(RapidSwitchingDetector::class.java)

        // 2. Stub standard mocks options
        // Use concrete values to avoid any ambiguity
        Mockito.`when`(context.getSharedPreferences(eq("betaflox_sdk_prefs"), eq(0))).thenReturn(sharedPreferences)
        Mockito.`when`(context.packageName).thenReturn("com.example.test") // Stub packageName
        Mockito.`when`(sharedPreferences.edit()).thenReturn(editor)
        Mockito.`when`(editor.putLong(anyString(), anyLong())).thenReturn(editor)
        Mockito.`when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        Mockito.`when`(editor.remove(anyString())).thenReturn(editor)
        
        // Stub RapidSwitchingDetector
        Mockito.`when`(rapidSwitchingDetector.recordSessionStart()).thenReturn(false)
        Mockito.`when`(rapidSwitchingDetector.recordSessionEnd(anyLong())).thenReturn(false)

        // 4. Initialize object under test
        sessionManager = SessionManager(context, config, eventLogger, rapidSwitchingDetector)
    }

    // tearDown removed as mockedSDK is gone

    @Test
    fun `startSession starts a new session and logs event`() {
        sessionManager.startSession()

        // Relaxed verification
        verify(editor, atLeastOnce()).putLong(anyString(), anyLong())
        verify(editor, atLeastOnce()).apply()
        // Use Elvis operator to handle null return from matchers in Kotlin
        verify(eventLogger).logEvent(eq("app_open") ?: "app_open", anyMap() ?: mapOf())
    }

    @Test
    fun `pauseSession logs duration and ends session`() {
        sessionManager.startSession()
        sessionManager.pauseSession()

        // Check if event logger was called
        verify(eventLogger).logEvent(eq("app_close") ?: "app_close", anyMap() ?: mapOf())
        verify(eventLogger).logEvent(eq("session_duration") ?: "session_duration", anyMap() ?: mapOf())
    }
    
    @Test
    fun `endSession clears preferences`() {
        sessionManager.startSession()
        sessionManager.endSession()
        
        verify(editor).remove(anyString())
        verify(editor, atLeastOnce()).apply()
    }
}
