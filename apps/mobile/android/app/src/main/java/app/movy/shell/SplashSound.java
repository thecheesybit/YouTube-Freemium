package app.movy.shell;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Tiny synthesized chimes for the first-launch splash (no audio assets). */
final class SplashSound {
    private static final int RATE = 22050;

    private SplashSound() {}

    /** Rising arpeggio when the app name appears. */
    static void intro() {
        play(new float[]{523.25f, 659.25f, 783.99f, 1046.5f}, 95, 650, 0.30f);
    }

    /** Warm resolving chord when the credit appears. */
    static void credit() {
        play(new float[]{392.0f, 523.25f, 659.25f}, 70, 800, 0.26f);
    }

    private static void play(final float[] notes, final int stepMs, final int tailMs, final float gain) {
        new Thread(() -> {
            try {
                int total = (int) ((notes.length - 1) * stepMs * RATE / 1000L) + tailMs * RATE / 1000;
                short[] pcm = new short[total];
                for (int n = 0; n < notes.length; n++) {
                    int start = (int) (n * stepMs * RATE / 1000L);
                    for (int i = 0; start + i < total; i++) {
                        double t = i / (double) RATE;
                        double env = Math.exp(-4.2 * t) * Math.min(1.0, i / (RATE * 0.004));
                        double s = Math.sin(2 * Math.PI * notes[n] * t) + 0.25 * Math.sin(4 * Math.PI * notes[n] * t);
                        int v = pcm[start + i] + (int) (s * env * gain * 9000);
                        pcm[start + i] = (short) Math.max(-32000, Math.min(32000, v));
                    }
                }
                final AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(pcm.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC).build();
                track.write(pcm, 0, pcm.length);
                track.play();
                Thread.sleep(total * 1000L / RATE + 200);
                track.release();
            } catch (Throwable ignored) {
            }
        }, "splash-sound").start();
    }
}
