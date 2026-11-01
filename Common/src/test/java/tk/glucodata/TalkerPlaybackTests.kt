package tk.glucodata

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTextToSpeech

/** Exercise the real Talker queue and Android focus requests; no native library or TTS service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SpeechTestApplication::class,
    shadows = [SpeechTestNatives::class, SpeechTestEngine::class])
class TalkerPlaybackTests {
    private lateinit var audio: AudioManager
    private lateinit var talker: Talker

    @Before fun setUp() {
        Applic.app = RuntimeEnvironment.getApplication() as SpeechTestApplication
        audio = Applic.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        SpeechTestNatives.usage = AudioAttributes.USAGE_MEDIA
        Notify.makenotification_audio()
        Talker.notifyfocus = false
        talker = Talker(Applic.app)
        talker.engineReady = true
    }

    @After fun tearDown() {
        Talker.notifyfocus = false
        talker.destruct()
        Notify.doTurnFocusoff()
    }

    @Test fun announcementsRespectMutedAndLowSystemVolumeOnEveryConfiguredStream() {
        for ((usage, stream) in listOf(
            AudioAttributes.USAGE_MEDIA to AudioManager.STREAM_MUSIC,
            AudioAttributes.USAGE_NOTIFICATION to AudioManager.STREAM_NOTIFICATION,
            AudioAttributes.USAGE_ALARM to AudioManager.STREAM_ALARM,
        )) {
            SpeechTestNatives.usage = usage
            Notify.makenotification_audio()
            for (volume in listOf(0, 1)) {
                audio.setStreamVolume(stream, volume, 0)
                val before = audio.getStreamVolume(stream)
                assertTrue(talker.speak("5.8", true))
                assertEquals("usage=$usage volume=$volume", before, audio.getStreamVolume(stream))
                assertEquals(usage, SpeechTestEngine.attributes!!.usage)
                assertEquals("5.8", shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
            }
        }
    }

    @Test fun routineSpeechRequestsDuckingBeforeTheWordsStart() {
        assertTrue(talker.speak("5.8"))
        val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, request.focusGain)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        talker.destruct()
        assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun stoppingSpeechCannotAbandonAnAlarmThatStartedDuringIt() {
        assertTrue(talker.speak("5.8"))
        val speechFocus = shadowOf(audio).lastAudioFocusRequest
        val speechRequest = speechFocus.audioFocusRequest
        Notify.doTurnFocuson()
        val alarmFocus = shadowOf(audio).lastAudioFocusRequest
        val alarmRequest = alarmFocus.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, alarmRequest.focusGain)
        assertNotSame(speechFocus.listener, alarmFocus.listener)
        talker.destruct()
        assertSame(speechRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
        Notify.doTurnFocusoff()
        assertSame(alarmRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun speechDuringAnAlarmLeavesFocusWithTheAlarm() {
        Notify.doTurnFocuson()
        val alarmRequest = shadowOf(audio).lastAudioFocusRequest
        Talker.notifyfocus = true
        assertTrue(talker.speak("5.8"))
        assertSame(alarmRequest, shadowOf(audio).lastAudioFocusRequest)
        talker.destruct()
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun refusedFocusIsNotAbandonedAsIfGranted() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertTrue(talker.speak("5.8"))
        talker.destruct()
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun completionErrorAndStopReleaseSpeechFocusButLeadInDoesNot() {
        for (ending in listOf("done", "error", "stop")) {
            assertTrue(talker.speak("5.8"))
            val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
            val listener = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).utteranceProgressListener
            val wordsId = Talker::class.java.getDeclaredField("lastWordsId").apply {
                isAccessible = true
            }.get(talker) as String
            val previouslyAbandoned = shadowOf(audio).lastAbandonedAudioFocusRequest
            listener.onDone(LeadIn.ID)
            assertSame(previouslyAbandoned, shadowOf(audio).lastAbandonedAudioFocusRequest)
            when (ending) {
                "done" -> listener.onDone(wordsId)
                "error" -> listener.onError(wordsId)
                "stop" -> listener.onStop(wordsId, true)
            }
            assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
        }
    }

    @Test fun lateCallbackCannotUnduckANewerAnnouncement() {
        val focus = SpeechFocus(SpeechAudioFocus())
        focus.claim("5.8#1")
        val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        focus.claim("5.9#2")
        focus.release("5.8#1")
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        focus.release("5.9#2")
        assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun completionDuringAlarmReleasesSpeech() = speechEndingDuringAlarm("done")

    @Test fun errorDuringAlarmReleasesSpeech() = speechEndingDuringAlarm("error")

    @Test fun stopDuringAlarmReleasesSpeech() = speechEndingDuringAlarm("stop")

    private fun speechEndingDuringAlarm(ending: String) {
        val owner = Talker::class.java.getDeclaredField("lastWordsId").apply { isAccessible = true }
        assertTrue(talker.speak("5.7"))
        val earlierId = owner.get(talker) as String
        assertTrue(talker.speak("5.8"))
        val currentId = owner.get(talker) as String
        val speechRequest = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        val listener = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).utteranceProgressListener

        // A loss alarm (or an alarm without spoken values) starts while routine speech owns
        // ducking focus. No closing announcement will replace that speech owner later.
        Notify.doTurnFocuson()
        Talker.notifyfocus = true
        val alarmRequest = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        listener.onStop(earlierId, true)
        listener.onDone(LeadIn.ID)
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)

        when (ending) {
            "done" -> listener.onDone(currentId)
            "error" -> listener.onError(currentId)
            "stop" -> listener.onStop(currentId, true)
        }
        assertSame(speechRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)

        Talker.notifyfocus = false
        Notify.doTurnFocusoff()
        assertSame(alarmRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
        // Speech is already released: shutdown must not be needed to restore media.
        talker.destruct()
        assertSame(alarmRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }
}

@Implements(TextToSpeech::class)
class SpeechTestEngine : ShadowTextToSpeech() {
    @Implementation fun setAudioAttributes(value: AudioAttributes): Int {
        attributes = value
        return TextToSpeech.SUCCESS
    }

    companion object {
        var attributes: AudioAttributes? = null
    }
}

class SpeechTestApplication : Applic() {
    override fun onCreate() {}
}

@Implements(Natives::class)
class SpeechTestNatives {
    companion object {
        var usage = AudioAttributes.USAGE_MEDIA
        @JvmStatic @Implementation fun getSoundType(): Int = usage
    }
}
