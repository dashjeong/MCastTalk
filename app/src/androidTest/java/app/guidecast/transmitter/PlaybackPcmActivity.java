package app.guidecast.transmitter;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;

/** Standalone test-APK activity; Java avoids relying on the target APK's Kotlin runtime. */
public final class PlaybackPcmActivity extends Activity {
    public static final String EXTRA_PCM_BYTES = "pcm_bytes";
    public static final String EXTRA_SAMPLE_RATE_HZ = "sample_rate_hz";

    private AudioTrack audioTrack;
    private Thread playbackThread;
    private static volatile PlaybackPcmActivity activeInstance;

    /** Test-only deterministic cleanup between instrumentation methods and preview cycles. */
    public static void finishActivePlayback() {
        PlaybackPcmActivity current = activeInstance;
        if (current != null) current.finishAndRemoveTask();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeInstance = this;
        TextView label = new TextView(this);
        label.setText("GuideCast capturable Korean speech");
        label.setTextSize(22f);
        label.setPadding(48, 96, 48, 48);
        setContentView(label);

        int sampleRateHz = getIntent().getIntExtra(EXTRA_SAMPLE_RATE_HZ, 0);
        byte[] pcm = getIntent().getByteArrayExtra(EXTRA_PCM_BYTES);
        if (sampleRateHz < 8_000 || sampleRateHz > 48_000 || pcm == null ||
                pcm.length == 0 || pcm.length % 2 != 0) {
            finish();
            return;
        }

        int minBufferBytes = AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBufferBytes <= 0) {
            finish();
            return;
        }
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minBufferBytes, sampleRateHz * 2))
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            finish();
            return;
        }

        audioTrack = track;
        track.play();
        playbackThread = new Thread(() -> {
            int offset = 0;
            while (offset < pcm.length && !Thread.currentThread().isInterrupted()) {
                int written = track.write(
                        pcm,
                        offset,
                        pcm.length - offset,
                        AudioTrack.WRITE_BLOCKING
                );
                if (written <= 0) return;
                offset += written;
            }
            int expectedFrames = pcm.length / 2;
            long deadline = SystemClock.elapsedRealtime() + 3_000L;
            while (!Thread.currentThread().isInterrupted() &&
                    track.getPlaybackHeadPosition() < expectedFrames &&
                    SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(20L);
            }
            runOnUiThread(this::finish);
        }, "guidecast-capturable-speech");
        playbackThread.start();
    }

    @Override
    protected void onDestroy() {
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (IllegalStateException ignored) {
                // The emulator may already have stopped the track.
            }
            audioTrack.release();
            audioTrack = null;
        }
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }
}
