package tk.glucodata;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

/** Android focus for short announcements. Never changes the user's stream volume. */
final class SpeechAudioFocus implements SpeechFocus.Audio {
    // A distinct listener also gives speech a distinct Android focus client from Notify's
    // alarm request. Abandoning speech must not abandon an alarm that started during it.
    private final AudioFocusRequest request = new AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setOnAudioFocusChangeListener(change -> {})
            .build();
    private AudioManager manager;
    private boolean held;

    @Override
    public void request() {
        if (held) return;
        manager = (AudioManager) Applic.app.getSystemService(Context.AUDIO_SERVICE);
        if (manager != null) {
            held = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    @Override
    public void abandon() {
        if (held) {
            held = false;
            manager.abandonAudioFocusRequest(request);
        }
    }
}
