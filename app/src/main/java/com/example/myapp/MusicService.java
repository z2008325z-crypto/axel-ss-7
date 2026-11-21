package com.example.myapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MusicService extends Service implements AudioManager.OnAudioFocusChangeListener {

    public static final String ACTION_TOGGLE = "com.example.myapp.TOGGLE";
    public static final String ACTION_NEXT = "com.example.myapp.NEXT";
    public static final String ACTION_PREV = "com.example.myapp.PREV";
    public static final String ACTION_CLOSE = "com.example.myapp.CLOSE";

    private static final String CHANNEL_ID = "lord_music_channel";
    private static final int NOTIF_ID = 77;

    public interface Listener {
        void onChanged();
        void onClosed();
    }

    public class LocalBinder extends Binder {
        public MusicService getService() {
            return MusicService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final List<Song> songs = new ArrayList<Song>();
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final Random random = new Random();

    private MediaPlayer player;
    private LoudnessEnhancer enhancer;
    private int boostPct = -1; // 0..100, maps to 0..4500 millibels of extra gain
    private static final int BOOST_MB_PER_PERCENT = 45;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private AudioAttributes attrs;
    private Bitmap art;

    private int index = -1;
    private boolean shuffle = false;
    private int repeat = 0; // 0 off, 1 all, 2 one
    private boolean prepared = false;
    private boolean pausedByFocus = false;
    private boolean foreground = false;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isPlaying()) {
                togglePlay();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Themes.apply(getSharedPreferences("lord", MODE_PRIVATE).getBoolean("dark", true),
                getSharedPreferences("lord", MODE_PRIVATE).getInt("accent", 0));
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        createChannel();

        session = new MediaSession(this, "LORD MUSIC");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (!isPlaying()) togglePlay();
            }

            @Override
            public void onPause() {
                if (isPlaying()) togglePlay();
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                prev();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
            }

            @Override
            public void onStop() {
                closeAll();
            }
        });
        session.setActive(true);

        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(a)) {
            togglePlay();
        } else if (ACTION_NEXT.equals(a)) {
            next();
        } else if (ACTION_PREV.equals(a)) {
            prev();
        } else if (ACTION_CLOSE.equals(a)) {
            closeAll();
        } else if (!foreground) {
            startForegroundNow();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // لما تشيل التطبيق من الأخيرة المزيكا تكمل - مفيش stop هنا عن قصد
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        sleepHandler.removeCallbacks(sleepRun);
        try {
            unregisterReceiver(noisyReceiver);
        } catch (Exception ignored) {
        }
        releasePlayer();
        abandonFocus();
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    // ---------------- public API ----------------

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void setPlaylist(List<Song> list) {
        songs.clear();
        songs.addAll(list);
    }

    public void playIndex(int i) {
        playIndexInternal(i, 0);
    }

    public Song currentSong() {
        if (index >= 0 && index < songs.size()) return songs.get(index);
        return null;
    }

    public Bitmap getArt() {
        return art;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public int getRepeat() {
        return repeat;
    }

    public void toggleShuffle() {
        shuffle = !shuffle;
        notifyChanged();
    }

    public void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        notifyChanged();
    }

    public boolean isPlaying() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public int getPosition() {
        try {
            if (player != null && prepared) return player.getCurrentPosition();
        } catch (Exception ignored) {
        }
        return 0;
    }

    public int getDuration() {
        try {
            if (player != null && prepared) return player.getDuration();
        } catch (Exception ignored) {
        }
        return 0;
    }

    public void seekTo(int ms) {
        try {
            if (player != null && prepared) {
                player.seekTo(ms);
                refresh();
            }
        } catch (Exception ignored) {
        }
    }

    public void togglePlay() {
        if (player == null) {
            if (index >= 0) playIndexInternal(index, 0);
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
            } else {
                requestFocus();
                player.start();
            }
        } catch (Exception ignored) {
        }
        pausedByFocus = false;
        refresh();
    }

    public void next() {
        if (songs.isEmpty()) return;
        if (shuffle && songs.size() > 1) {
            int r;
            do {
                r = random.nextInt(songs.size());
            } while (r == index);
            playIndexInternal(r, 0);
        } else {
            playIndexInternal(index + 1, 0);
        }
    }

    public void prev() {
        if (songs.isEmpty()) return;
        if (getPosition() > 3000) {
            seekTo(0);
        } else {
            playIndexInternal(index - 1, 0);
        }
    }

    public void refreshNotification() {
        refresh();
    }

    public void reloadArt() {
        Song cur = currentSong();
        if (cur == null) return;
        art = loadArt(cur);
        refresh();
    }

    public void removeSong(long id) {
        int p = -1;
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).id == id) {
                p = i;
                break;
            }
        }
        if (p < 0) return;
        boolean wasCurrent = p == index;
        boolean wasPlaying = isPlaying();
        songs.remove(p);
        if (p < index) index--;
        if (wasCurrent) {
            if (songs.isEmpty()) {
                releasePlayer();
                index = -1;
                art = null;
                refresh();
            } else {
                playIndexInternal(index, 0);
                if (!wasPlaying && isPlaying()) togglePlay();
            }
        } else {
            refresh();
        }
    }

    // ---------------- sleep timer ----------------

    private final Handler sleepHandler = new Handler(Looper.getMainLooper());
    private long sleepEndAt = 0;
    private final Runnable sleepRun = new Runnable() {
        @Override
        public void run() {
            sleepEndAt = 0;
            if (player != null) {
                try {
                    if (player.isPlaying()) togglePlay();
                } catch (Exception ignored) {
                }
            }
            refresh();
        }
    };

    /** minutes <= 0 cancels the timer */
    public void setSleepTimer(int minutes) {
        sleepHandler.removeCallbacks(sleepRun);
        if (minutes <= 0) {
            sleepEndAt = 0;
        } else {
            sleepEndAt = System.currentTimeMillis() + minutes * 60000L;
            sleepHandler.postDelayed(sleepRun, minutes * 60000L);
        }
    }

    public long getSleepRemainingMs() {
        if (sleepEndAt == 0) return 0;
        return Math.max(0, sleepEndAt - System.currentTimeMillis());
    }

    public void closeAll() {
        sleepHandler.removeCallbacks(sleepRun);
        sleepEndAt = 0;
        releasePlayer();
        abandonFocus();
        index = -1;
        art = null;
        List<Listener> copy = new ArrayList<Listener>(listeners);
        for (Listener l : copy) {
            l.onClosed();
        }
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        foreground = false;
        stopSelf();
    }

    // ---------------- volume boost ----------------

    public int getBoostPercent() {
        if (boostPct < 0) boostPct = getSharedPreferences("lord", MODE_PRIVATE).getInt("boost_pct", 0);
        return boostPct;
    }

    /** Slider value 0..100. Applied live, no restart of the player needed. */
    public void setBoostPercent(int p) {
        p = Math.max(0, Math.min(100, p));
        boolean wasOff = getBoostPercent() == 0;
        boostPct = p;
        getSharedPreferences("lord", MODE_PRIVATE).edit().putInt("boost_pct", p).apply();
        if (p > 0 && wasOff) maxSystemVolume();
        applyBoost();
    }

    // يرفع صوت الميديا في النظام للحد الأقصى (بيشتغل على السماعة والهاتف والبلوتوث/إيربودز)
    private void maxSystemVolume() {
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
        } catch (Exception ignored) {
        }
    }

    private void applyBoost() {
        int gain = getBoostPercent() * BOOST_MB_PER_PERCENT;
        if (player == null || !prepared || gain == 0) {
            releaseEnhancer();
            return;
        }
        try {
            if (enhancer == null) enhancer = new LoudnessEnhancer(player.getAudioSessionId());
            enhancer.setTargetGain(gain);
            enhancer.setEnabled(true);
        } catch (Exception e) {
            releaseEnhancer();
        }
    }

    private void releaseEnhancer() {
        if (enhancer != null) {
            try {
                enhancer.release();
            } catch (Exception ignored) {
            }
            enhancer = null;
        }
    }

    // ---------------- internals ----------------

    private void playIndexInternal(int i, int attempt) {
        if (songs.isEmpty()) return;
        int n = songs.size();
        index = ((i % n) + n) % n;
        Song s = songs.get(index);

        ensureForeground();
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(attrs);
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(getApplicationContext(), s.uri);
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    onTrackEnded();
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    return true;
                }
            });
            player.prepare();
            prepared = true;
            requestFocus();
            player.start();
            if (getBoostPercent() > 0) maxSystemVolume();
            applyBoost();
            pausedByFocus = false;
            art = loadArt(s);
            refresh();
        } catch (Exception e) {
            prepared = false;
            releasePlayer();
            if (songs.size() > 1 && attempt < songs.size() - 1) {
                playIndexInternal(index + 1, attempt + 1);
            } else {
                Toast.makeText(this, "تعذر تشغيل الملف", Toast.LENGTH_SHORT).show();
                refresh();
            }
        }
    }

    private void onTrackEnded() {
        if (repeat == 2) {
            playIndexInternal(index, 0);
            return;
        }
        if (shuffle && songs.size() > 1) {
            next();
            return;
        }
        if (index < songs.size() - 1) {
            playIndexInternal(index + 1, 0);
        } else if (repeat == 1) {
            playIndexInternal(0, 0);
        } else {
            playIndexInternal(0, 0);
            try {
                if (player != null && player.isPlaying()) player.pause();
            } catch (Exception ignored) {
            }
            refresh();
        }
    }

    private void releasePlayer() {
        prepared = false;
        releaseEnhancer();
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private boolean requestFocus() {
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(this)
                    .build();
        }
        return audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (focusRequest != null && audio != null) {
            audio.abandonAudioFocusRequest(focusRequest);
        }
    }

    @Override
    public void onAudioFocusChange(int change) {
        try {
            switch (change) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    if (isPlaying()) {
                        player.pause();
                        pausedByFocus = false;
                        refresh();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    if (isPlaying()) {
                        player.pause();
                        pausedByFocus = true;
                        refresh();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (player != null) player.setVolume(0.25f, 0.25f);
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (player != null) player.setVolume(1f, 1f);
                    if (pausedByFocus && player != null && prepared) {
                        player.start();
                        pausedByFocus = false;
                        refresh();
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private Bitmap loadArt(Song s) {
        try {
            java.io.File cf = new java.io.File(getFilesDir(), "covers/" + s.id + ".jpg");
            if (cf.exists()) {
                Bitmap cb = BitmapFactory.decodeFile(cf.getAbsolutePath());
                if (cb != null) return cb;
            }
        } catch (Exception ignored) {
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(getApplicationContext(), s.uri);
            byte[] b = r.getEmbeddedPicture();
            if (b != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(b, 0, b.length, o);
                int sample = 1;
                while (o.outWidth / sample > 600) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                return BitmapFactory.decodeByteArray(b, 0, b.length, o2);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "LORD MUSIC", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent actionIntent(String action, int code) {
        Intent i = new Intent(this, MusicService.class);
        i.setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action action(int icon, String title, String action, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), title, actionIntent(action, code)).build();
    }

    private Notification buildNotification() {
        Song s = currentSong();
        Intent oi = new Intent(this, MainActivity.class);
        oi.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        oi.putExtra("page", 1);
        PendingIntent open = PendingIntent.getActivity(this, 0, oi, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        int accent = Themes.P[2];

        boolean playing = isPlaying();
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(s != null ? s.title : "LORD MUSIC")
                .setContentText(s != null ? s.artist : "")
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setColor(accent)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(action(R.drawable.ic_prev, "السابق", ACTION_PREV, 1))
                .addAction(action(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        playing ? "إيقاف مؤقت" : "تشغيل", ACTION_TOGGLE, 2))
                .addAction(action(R.drawable.ic_next, "التالي", ACTION_NEXT, 3))
                .addAction(action(R.drawable.ic_close, "إغلاق", ACTION_CLOSE, 4))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null) b.setLargeIcon(art);
        return b.build();
    }

    private void ensureForeground() {
        if (!foreground) startForegroundNow();
    }

    private void startForegroundNow() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
        foreground = true;
    }

    private void updateNotification() {
        if (!foreground) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification());
    }

    private void updateSession() {
        if (session == null) return;
        Song s = currentSong();
        if (s != null) {
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, s.duration);
            if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            session.setMetadata(mb.build());
        }
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        PlaybackState st = new PlaybackState.Builder()
                .setActions(actions)
                .setState(isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        (long) getPosition(), 1f)
                .build();
        session.setPlaybackState(st);
    }

    private void refresh() {
        updateSession();
        updateNotification();
        notifyChanged();
    }

    private void notifyChanged() {
        List<Listener> copy = new ArrayList<Listener>(listeners);
        for (Listener l : copy) {
            l.onChanged();
        }
    }
}
