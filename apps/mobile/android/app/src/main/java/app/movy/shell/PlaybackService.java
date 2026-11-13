package app.movy.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlaybackService extends Service implements AudioManager.OnAudioFocusChangeListener {
    public static final String ACTION_PLAY = "app.movy.shell.ACTION_PLAY";
    public static final String ACTION_PAUSE = "app.movy.shell.ACTION_PAUSE";
    public static final String ACTION_NEXT = "app.movy.shell.ACTION_NEXT";
    public static final String ACTION_PREV = "app.movy.shell.ACTION_PREV";
    public static final String ACTION_STOP = "app.movy.shell.ACTION_STOP";

    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 1001;
    private static final String TAG = "PlaybackService";
    private static final long INACTIVE_TIMEOUT_MS = 2 * 60 * 1000L; // 2 minutes

    public interface ActionCallback {
        void onPlay();
        void onPause();
        void onNext();
        void onPrev();
        void onSeekTo(long positionMs);
    }

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private ActionCallback callback;
    private boolean isPlaying = false;
    private boolean resumeOnFocusGain = false;
    private String currentTitle = "YouTube Freemium";
    private String currentArtist = "YouTube";
    private String currentArtworkUrl = null;
    private Bitmap currentArtworkBitmap = null;
    private long currentDurationMs = 0;
    private long currentPositionMs = 0;

    private final Runnable stopInactiveTask = this::stopServiceSafely;

    public class LocalBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initMediaSession();
        initLocks();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    public void setActionCallback(ActionCallback cb) {
        this.callback = cb;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.movy_notification_channel_playback),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Controls for background audio playback");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void initMediaSession() {
        mediaSession = new MediaSession(this, "YouTubeFreemiumSession");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (callback != null) callback.onPlay();
            }

            @Override
            public void onPause() {
                if (callback != null) callback.onPause();
            }

            @Override
            public void onSkipToNext() {
                if (callback != null) callback.onNext();
            }

            @Override
            public void onSkipToPrevious() {
                if (callback != null) callback.onPrev();
            }

            @Override
            public void onSeekTo(long pos) {
                if (callback != null) callback.onSeekTo(pos);
            }

            @Override
            public void onStop() {
                if (callback != null) callback.onPause();
                stopServiceSafely();
            }
        });

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mediaSession.setSessionActivity(pi);
        mediaSession.setActive(true);
    }

    private void initLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YouTubeFreemium:PlaybackWakeLock");
            wakeLock.setReferenceCounted(false);
        }

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "YouTubeFreemium:PlaybackWifiLock");
            wifiLock.setReferenceCounted(false);
        }
    }

    public void updateState(boolean playing, String title, String artist, String artworkUrl, long durationSec, long positionSec) {
        this.isPlaying = playing;
        if (title != null && !title.isEmpty()) this.currentTitle = title;
        if (artist != null && !artist.isEmpty()) this.currentArtist = artist;
        this.currentDurationMs = durationSec * 1000L;
        this.currentPositionMs = positionSec * 1000L;

        handler.removeCallbacks(stopInactiveTask);

        if (playing) {
            acquireLocks();
            requestAudioFocus();
        } else {
            releaseLocks();
            handler.postDelayed(stopInactiveTask, INACTIVE_TIMEOUT_MS);
        }

        updateMediaSessionPlaybackState();

        if (artworkUrl != null && !artworkUrl.equals(this.currentArtworkUrl)) {
            this.currentArtworkUrl = artworkUrl;
            loadArtworkBitmap(artworkUrl);
        } else {
            updateNotification();
        }
    }

    private void updateMediaSessionPlaybackState() {
        if (mediaSession == null) return;

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;

        int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, currentPositionMs, 1.0f);
        mediaSession.setPlaybackState(b.build());

        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, currentDurationMs);
        if (currentArtworkBitmap != null) {
            mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentArtworkBitmap);
        }
        mediaSession.setMetadata(mb.build());
    }

    private void loadArtworkBitmap(String urlStr) {
        executor.execute(() -> {
            Bitmap bmp = null;
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.connect();
                try (InputStream in = conn.getInputStream()) {
                    bmp = BitmapFactory.decodeStream(in);
                }
            } catch (Exception e) {
                // ignore
            }
            final Bitmap result = bmp;
            handler.post(() -> {
                currentArtworkBitmap = result;
                updateMediaSessionPlaybackState();
                updateNotification();
            });
        });
    }

    private void updateNotification() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent prevPi = buildActionPendingIntent(ACTION_PREV, 1);
        PendingIntent playPausePi = buildActionPendingIntent(isPlaying ? ACTION_PAUSE : ACTION_PLAY, 2);
        PendingIntent nextPi = buildActionPendingIntent(ACTION_NEXT, 3);
        PendingIntent stopPi = buildActionPendingIntent(ACTION_STOP, 4);

        int playIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playTitle = isPlaying ? "Pause" : "Play";

        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, CHANNEL_ID);
        } else {
            nb = new Notification.Builder(this);
        }

        nb.setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setSubText("YouTube Freemium")
                .setSmallIcon(R.drawable.ic_play)
                .setContentIntent(contentPi)
                .setDeleteIntent(stopPi)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying)
                .setShowWhen(false);

        if (currentArtworkBitmap != null) {
            nb.setLargeIcon(currentArtworkBitmap);
        }

        nb.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", prevPi).build());
        nb.addAction(new Notification.Action.Builder(playIcon, playTitle, playPausePi).build());
        nb.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", nextPi).build());

        Notification.MediaStyle style = new Notification.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2);
        nb.setStyle(style);

        Notification notification = nb.build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private PendingIntent buildActionPendingIntent(String action, int reqCode) {
        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            if (ACTION_PLAY.equals(act)) {
                if (callback != null) callback.onPlay();
            } else if (ACTION_PAUSE.equals(act)) {
                if (callback != null) callback.onPause();
            } else if (ACTION_NEXT.equals(act)) {
                if (callback != null) callback.onNext();
            } else if (ACTION_PREV.equals(act)) {
                if (callback != null) callback.onPrev();
            } else if (ACTION_STOP.equals(act)) {
                if (callback != null) callback.onPause();
                stopServiceSafely();
            }
        }
        return START_NOT_STICKY;
    }

    private void acquireLocks() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(12 * 60 * 60 * 1000L); // max 12 hours safety
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    // The WebView (Chromium) owns audio focus and pauses/ducks itself on calls and other apps.
    // Requesting focus here made it fight the page: focus loss paused the video instantly.
    private void requestAudioFocus() { }

    private void abandonAudioFocus() { }

    @Override
    public void onAudioFocusChange(int focusChange) { }

    public void stopServiceSafely() {
        isPlaying = false;
        releaseLocks();
        abandonAudioFocus();
        if (mediaSession != null) {
            mediaSession.setActive(false);
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopServiceSafely();
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
