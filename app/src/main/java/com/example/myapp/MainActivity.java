package com.example.myapp;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements MusicService.Listener {

    private static final int REQ_PERM = 1;
    private static final int REQ_COVER = 3;
    private static final int REQ_ICON = 4;
    private static final int REQ_DEL = 5;
    private static final long TRASH_MS = 12L * 24 * 60 * 60 * 1000;

    private static final int P_SONGS = 0, P_PLAYER = 1, P_TRASH = 2, P_SETTINGS = 3, NPAGES = 4;

    private MusicService service;
    private boolean bound = false;
    private SharedPreferences prefs;
    private int page = 0;
    private boolean dragging = false;
    private boolean boostTouching = false;
    private boolean loaded = false;
    private boolean selMode = false;

    private final List<Song> allSongs = new ArrayList<Song>();
    private final List<Song> shown = new ArrayList<Song>();
    private final List<Song> trashed = new ArrayList<Song>();
    private final java.util.Map<Long, Long> trashTimes = new java.util.HashMap<Long, Long>();
    private final Set<Long> selSongs = new HashSet<Long>();
    private final Set<Long> selTrash = new HashSet<Long>();
    private Song coverTarget;
    private List<Song> delPending;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // cover cache
    private final LruCache<Long, Bitmap> artCache = new LruCache<Long, Bitmap>(120);
    private final Set<Long> noArt = Collections.synchronizedSet(new HashSet<Long>());
    private final Set<Long> pending = Collections.synchronizedSet(new HashSet<Long>());
    private final ExecutorService artPool = Executors.newFixedThreadPool(2);

    // shell
    private FrameLayout root, pageHost;
    private LinearLayout content, navBar;
    private final View[] pages = new View[NPAGES];
    private final LinearLayout[] navCells = new LinearLayout[NPAGES];
    private final ImageView[] navIcons = new ImageView[NPAGES];
    private final TextView[] navLabels = new TextView[NPAGES];

    // songs page
    private TextView headerTitle, headerSub, emptyView;
    private LinearLayout normalHead, selBar;
    private TextView selCount, selAll;
    private ImageView selCancel, selDelete;
    private EditText searchBox;
    private ListView listView;
    private SongAdapter adapter;
    private LinearLayout miniCard;
    private ImageView miniArt, miniPlay, miniNext;
    private TextView miniTitle, miniArtist;

    // player page
    private TextView pLabel, pTitle, pArtist, curTime, totalTime;
    private FrameLayout artFrame;
    private ImageView artBig, btnShuffle, btnPrev, btnPlay, btnNext, btnRepeat;
    private SeekBar seek;

    // trash page
    private TextView trashTitle, trashSub, trashEmpty, tAll, tRestore, tDelete;
    private ListView trashList;
    private TrashAdapter trashAdapter;

    // settings page
    private TextView settingsTitle, boostLabel, boostWarn, closeBtn;
    private SeekBar boostSeek;
    private final List<TextView> primaryTexts = new ArrayList<TextView>();
    private final List<TextView> secondaryTexts = new ArrayList<TextView>();
    private final List<View> cards = new ArrayList<View>();
    private final List<TextView> pills = new ArrayList<TextView>();
    private final List<ImageView> homeBtns = new ArrayList<ImageView>();
    private final List<ImageView> themeBtns = new ArrayList<ImageView>();
    private final FrameLayout[] navPills = new FrameLayout[NPAGES];
    private TextView themePill, sleepStatus;
    private View navDiv;

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("lord", MODE_PRIVATE);
        Themes.set(prefs.getBoolean("dark_mode", true));
        new File(getFilesDir(), "bg.jpg").delete(); // الخلفية المخصصة اتلغت

        setupEdgeToEdge();
        loadTrashTimes();
        buildUi();
        setContentView(root);
        root.requestApplyInsets();
        applyTheme();
        showPage(P_SONGS);
        registerBackCallback();

        if (hasPermission()) {
            loadSongs();
        } else {
            requestPerms();
        }
        updateEmptyText();

        bindService(new Intent(this, MusicService.class), conn, Context.BIND_AUTO_CREATE);

        handleIntent(getIntent());
    }

    private void setupEdgeToEdge() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i != null && i.getIntExtra("page", -1) == 1) {
            showPage(P_PLAYER);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerBackCallback();
        handler.post(ticker);
        updateUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        artPool.shutdownNow();
        if (bound) {
            if (service != null) service.removeListener(this);
            try {
                unbindService(conn);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        super.onDestroy();
    }

    // الرجوع: أي صفحة -> الأغاني -> خروج من التطبيق
    private boolean goBackOnePage() {
        if (selMode) {
            clearSongSelection();
            return true;
        }
        if (!selTrash.isEmpty()) {
            selTrash.clear();
            refreshTrashUi();
            return true;
        }
        if (page != P_SONGS) {
            showPage(P_SONGS);
            return true;
        }
        return false;
    }

    private Object backCallback;

    private void registerBackCallback() {
        // مع targetSdk 33+ نظام أندرويد مش بيستدعي onBackPressed، فلازم نسجل callback
        if (Build.VERSION.SDK_INT >= 33 && backCallback == null) {
            final android.window.OnBackInvokedCallback cb = new android.window.OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    if (!goBackOnePage()) {
                        unregisterBackCallback();
                        MainActivity.this.onBackPressed();
                    }
                }
            };
            backCallback = cb;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
        }
    }

    private void unregisterBackCallback() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (!goBackOnePage()) {
            super.onBackPressed();
        }
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MusicService.LocalBinder) binder).getService();
            bound = true;
            service.addListener(MainActivity.this);
            updateUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override
    public void onChanged() {
        updateUi();
    }

    @Override
    public void onClosed() {
        finish();
    }

    // ================================================================ permissions & songs

    private boolean hasPermission() {
        String p = Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_AUDIO"
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerms() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.READ_MEDIA_AUDIO", "android.permission.POST_NOTIFICATIONS"}, REQ_PERM);
        } else {
            if (Build.VERSION.SDK_INT >= 29) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (hasPermission()) loadSongs();
            updateEmptyText();
        }
    }

    private void updateEmptyText() {
        if (!hasPermission()) {
            emptyView.setText("محتاج صلاحية الوصول للموسيقى\nاضغط هنا للسماح");
        } else if (!loaded) {
            emptyView.setText("جاري التحميل…");
        } else {
            emptyView.setText("مفيش أغاني على الجهاز");
        }
    }

    private void loadSongs() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Song> out = new ArrayList<Song>();
                Cursor c = null;
                try {
                    String[] proj = {
                            MediaStore.Audio.Media._ID,
                            MediaStore.Audio.Media.TITLE,
                            MediaStore.Audio.Media.ARTIST,
                            MediaStore.Audio.Media.DURATION
                    };
                    c = getContentResolver().query(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            proj,
                            MediaStore.Audio.Media.IS_MUSIC + "!=0",
                            null,
                            MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC");
                    if (c != null) {
                        while (c.moveToNext()) {
                            long id = c.getLong(0);
                            String t = c.getString(1);
                            String a = c.getString(2);
                            long d = c.getLong(3);
                            if (a == null || "<unknown>".equals(a)) a = "Unknown";
                            if (t == null) t = "Unknown";
                            out.add(new Song(id, t, a, d,
                                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)));
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.close();
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        allSongs.clear();
                        trashed.clear();
                        List<Song> expired = new ArrayList<Song>();
                        Set<Long> present = new HashSet<Long>();
                        long now = System.currentTimeMillis();
                        for (Song sg : out) {
                            present.add(Long.valueOf(sg.id));
                            String ot = prefs.getString("ov_t_" + sg.id, null);
                            if (ot != null) sg.title = ot;
                            Long tt = trashTimes.get(Long.valueOf(sg.id));
                            if (tt != null) {
                                trashed.add(sg);
                                if (now - tt.longValue() >= TRASH_MS) expired.add(sg);
                            } else {
                                allSongs.add(sg);
                            }
                        }
                        List<Long> stale = new ArrayList<Long>();
                        for (Long k : trashTimes.keySet()) {
                            if (!present.contains(k)) stale.add(k);
                        }
                        if (!stale.isEmpty()) {
                            for (Long k : stale) trashTimes.remove(k);
                            saveTrash();
                        }
                        loaded = true;
                        updateCount();
                        refreshTrashUi();
                        filter(searchBox.getText().toString());
                        updateEmptyText();
                        if (!expired.isEmpty()) deletePermanently(expired);
                    }
                });
            }
        }).start();
    }

    private void filter(String q) {
        String s = q == null ? "" : q.trim().toLowerCase();
        shown.clear();
        for (Song song : allSongs) {
            if (s.length() == 0
                    || song.title.toLowerCase().contains(s)
                    || song.artist.toLowerCase().contains(s)) {
                shown.add(song);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void playAt(int pos) {
        if (service == null || pos < 0 || pos >= shown.size()) return;
        startForegroundService(new Intent(this, MusicService.class));
        service.setPlaylist(new ArrayList<Song>(shown));
        service.playIndex(pos);
    }

    // ================================================================ covers

    private Bitmap loadThumb(Song s) {
        Bitmap b = null;
        try {
            File cf = new File(getFilesDir(), "covers/" + s.id + ".jpg");
            if (cf.exists()) {
                BitmapFactory.Options co = new BitmapFactory.Options();
                co.inSampleSize = 2;
                b = BitmapFactory.decodeFile(cf.getAbsolutePath(), co);
                if (b != null) return b;
            }
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                b = getContentResolver().loadThumbnail(s.uri, new Size(192, 192), null);
            } catch (Exception ignored) {
            }
        }
        if (b == null) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(getApplicationContext(), s.uri);
                byte[] d = r.getEmbeddedPicture();
                if (d != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(d, 0, d.length, o);
                    int sample = 1;
                    while (o.outWidth / sample > 384) sample *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    b = BitmapFactory.decodeByteArray(d, 0, d.length, o2);
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
        }
        return b;
    }

    private void requestArt(final Song s, final ImageView target) {
        if (!pending.add(s.id)) return;
        try {
            artPool.execute(new Runnable() {
                @Override
                public void run() {
                    final Bitmap bm = loadThumb(s);
                    if (bm != null) artCache.put(s.id, bm);
                    else noArt.add(s.id);
                    pending.remove(s.id);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (bm != null && Long.valueOf(s.id).equals(target.getTag())) {
                                target.setPadding(0, 0, 0, 0);
                                target.clearColorFilter();
                                target.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                target.setImageBitmap(bm);
                            }
                        }
                    });
                }
            });
        } catch (Exception e) {
            pending.remove(s.id);
        }
    }

    private void setCover(ImageView iv, Bitmap b, int tint, int phPad) {
        if (b != null) {
            iv.setPadding(0, 0, 0, 0);
            iv.clearColorFilter();
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(b);
        } else {
            iv.setPadding(phPad, phPad, phPad, phPad);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            setIc(iv, R.drawable.ic_music_note);
            tint(iv, tint);
        }
    }

    // ================================================================ UI building

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int iconKind(int res) {
        if (res == R.drawable.ic_library) return Icon3D.LIBRARY;
        if (res == R.drawable.ic_play_circle) return Icon3D.PLAYER;
        if (res == R.drawable.ic_delete || res == R.drawable.ic_delete_forever) return Icon3D.TRASH;
        if (res == R.drawable.ic_settings) return Icon3D.SETTINGS;
        if (res == R.drawable.ic_play) return Icon3D.PLAY;
        if (res == R.drawable.ic_pause) return Icon3D.PAUSE;
        if (res == R.drawable.ic_next) return Icon3D.NEXT;
        if (res == R.drawable.ic_prev) return Icon3D.PREV;
        if (res == R.drawable.ic_shuffle) return Icon3D.SHUFFLE;
        if (res == R.drawable.ic_repeat) return Icon3D.REPEAT;
        if (res == R.drawable.ic_repeat_one) return Icon3D.REPEAT_ONE;
        if (res == R.drawable.ic_music_note) return Icon3D.MUSIC;
        if (res == R.drawable.ic_close) return Icon3D.CLOSE;
        if (res == R.drawable.ic_check) return Icon3D.CHECK;
        if (res == R.drawable.ic_more) return Icon3D.MORE;
        if (res == R.drawable.ic_image) return Icon3D.IMAGE;
        if (res == R.drawable.ic_edit) return Icon3D.EDIT;
        if (res == R.drawable.ic_restore) return Icon3D.RESTORE;
        return -1;
    }

    private void setIc(ImageView iv, int res) {
        int k = iconKind(res);
        if (k >= 0) iv.setImageDrawable(new Icon3D(k));
        else iv.setImageResource(res);
    }

    private void setKind(ImageView iv, int kind) {
        iv.setImageDrawable(new Icon3D(kind));
    }

    private void tint(ImageView iv, int color) {
        Drawable d = iv.getDrawable();
        if (d instanceof Icon3D) {
            ((Icon3D) d).setColor(color);
            iv.clearColorFilter();
        } else {
            iv.setColorFilter(color);
        }
    }

    private ImageView roundIconBtn(int kind) {
        ImageView h = new ImageView(this);
        h.setScaleType(ImageView.ScaleType.FIT_CENTER);
        h.setPadding(dp(11), dp(11), dp(11), dp(11));
        setKind(h, kind);
        return h;
    }

    /** Back to the main (songs) screen. */
    private ImageView makeHomeBtn() {
        ImageView h = roundIconBtn(Icon3D.HOME);
        h.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(P_SONGS);
            }
        });
        homeBtns.add(h);
        return h;
    }

    /** Dark / light toggle. */
    private ImageView makeThemeBtn() {
        ImageView h = roundIconBtn(Icon3D.MOON);
        h.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTheme();
            }
        });
        themeBtns.add(h);
        return h;
    }

    private void toggleTheme() {
        boolean d = !Themes.dark;
        Themes.set(d);
        prefs.edit().putBoolean("dark_mode", d).apply();
        applyTheme();
        if (service != null) service.refreshNotification();
    }

    private void updateSystemBars() {
        boolean light = !Themes.dark;
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        } else {
            int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (light) {
                f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(f);
        }
    }

    private void startSleep(int minutes) {
        if (service == null) {
            Toast.makeText(this, "شغّل أغنية الأول", Toast.LENGTH_SHORT).show();
            return;
        }
        service.setSleepTimer(minutes);
        Toast.makeText(this, "الموسيقى هتتوقف بعد " + minutes + " دقيقة", Toast.LENGTH_SHORT).show();
        refreshSleepStatus();
    }

    private void refreshSleepStatus() {
        if (sleepStatus == null) return;
        long left = service != null ? service.getSleepRemainingMs() : 0;
        sleepStatus.setText(left > 0 ? "هتتوقف بعد  " + fmt((int) left) : "المؤقت مقفول");
    }

    private ImageView iconButton(int res) {
        ImageView iv = new ImageView(this);
        setIc(iv, res);
        iv.setPadding(dp(10), dp(10), dp(10), dp(10));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    private void roundOutline(View v, final int radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    private void ovalOutline(View v) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        v.setClipToOutline(true);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);


        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                content.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                        in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
                return in;
            }
        });

        pageHost = new FrameLayout(this);
        content.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        pages[P_SONGS] = buildSongsPage();
        pages[P_PLAYER] = buildPlayerPage();
        pages[P_TRASH] = buildTrashPage();
        pages[P_SETTINGS] = buildSettingsPage();
        for (int i = 0; i < NPAGES; i++) {
            pageHost.addView(pages[i], new FrameLayout.LayoutParams(-1, -1));
            if (i != P_SONGS) pages[i].setVisibility(View.GONE);
        }

        buildNav();
    }

    // ---------------------------------------------------------------- songs page

    private View buildSongsPage() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);

        FrameLayout headHost = new FrameLayout(this);

        normalHead = new LinearLayout(this);
        normalHead.setOrientation(LinearLayout.VERTICAL);
        normalHead.setPadding(dp(20), dp(14), dp(20), dp(6));
        headerTitle = new TextView(this);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setLetterSpacing(0.14f);
        headerTitle.setSingleLine();
        headerSub = new TextView(this);
        headerSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        headerSub.setText("مكتبتك الموسيقية");
        LinearLayout hrow = new LinearLayout(this);
        hrow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout hcol = new LinearLayout(this);
        hcol.setOrientation(LinearLayout.VERTICAL);
        hcol.addView(headerTitle, new LinearLayout.LayoutParams(-2, -2));
        hcol.addView(headerSub, new LinearLayout.LayoutParams(-2, -2));
        hrow.addView(hcol, new LinearLayout.LayoutParams(0, -2, 1f));
        hrow.addView(makeThemeBtn(), new LinearLayout.LayoutParams(dp(46), dp(46)));
        normalHead.addView(hrow, new LinearLayout.LayoutParams(-1, -2));
        headHost.addView(normalHead, new FrameLayout.LayoutParams(-1, -2));

        // selection bar (long-press a song to enter)
        selBar = new LinearLayout(this);
        selBar.setGravity(Gravity.CENTER_VERTICAL);
        selBar.setPadding(dp(12), dp(16), dp(12), dp(8));
        selBar.setVisibility(View.GONE);
        selCancel = iconButton(R.drawable.ic_close);
        selCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearSongSelection();
            }
        });
        selCount = new TextView(this);
        selCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        selCount.setTypeface(Typeface.DEFAULT_BOLD);
        selCount.setPadding(dp(10), 0, dp(10), 0);
        selAll = pill("تحديد الكل");
        selAll.setPadding(dp(14), dp(9), dp(14), dp(9));
        selAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAllSongs();
            }
        });
        selDelete = iconButton(R.drawable.ic_delete);
        selDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteSelected();
            }
        });
        selBar.addView(selCancel, new LinearLayout.LayoutParams(dp(44), dp(44)));
        selBar.addView(selCount, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams salp = new LinearLayout.LayoutParams(-2, -2);
        salp.rightMargin = dp(8);
        selBar.addView(selAll, salp);
        selBar.addView(selDelete, new LinearLayout.LayoutParams(dp(44), dp(44)));
        headHost.addView(selBar, new FrameLayout.LayoutParams(-1, -2));

        p.addView(headHost, new LinearLayout.LayoutParams(-1, -2));

        searchBox = new EditText(this);
        searchBox.setHint("ابحث عن أغنية أو فنان…");
        searchBox.setSingleLine();
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchBox.setPadding(dp(18), 0, dp(18), 0);
        searchBox.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter(s.toString());
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(46));
        slp.setMargins(dp(16), dp(6), dp(16), dp(8));
        p.addView(searchBox, slp);

        FrameLayout listFrame = new FrameLayout(this);
        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setVerticalScrollBarEnabled(false);
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, dp(8));
        adapter = new SongAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= shown.size()) return true;
                if (selMode) toggleSongSelection(shown.get(position));
                else startSelection(shown.get(position));
                return true;
            }
        });
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= shown.size()) return;
                if (selMode) {
                    toggleSongSelection(shown.get(position));
                    return;
                }
                Song s = shown.get(position);
                Song cur = service != null ? service.currentSong() : null;
                if (cur != null && cur.id == s.id) {
                    showPage(P_PLAYER);
                } else {
                    playAt(position);
                }
            }
        });

        emptyView = new TextView(this);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        emptyView.setPadding(dp(32), dp(32), dp(32), dp(32));
        emptyView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasPermission()) requestPerms();
            }
        });
        listFrame.addView(listView, new FrameLayout.LayoutParams(-1, -1));
        listFrame.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        listView.setEmptyView(emptyView);
        p.addView(listFrame, new LinearLayout.LayoutParams(-1, 0, 1f));

        // mini player
        miniCard = new LinearLayout(this);
        miniCard.setGravity(Gravity.CENTER_VERTICAL);
        miniCard.setPadding(dp(10), dp(9), dp(10), dp(9));
        miniCard.setElevation(dp(6));
        miniCard.setVisibility(View.GONE);
        miniCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(P_PLAYER);
            }
        });

        miniArt = new ImageView(this);
        roundOutline(miniArt, 12);
        miniCard.addView(miniArt, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);
        miniTitle = new TextView(this);
        miniTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        miniTitle.setTypeface(Typeface.DEFAULT_BOLD);
        miniTitle.setSingleLine();
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniArtist = new TextView(this);
        miniArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        miniArtist.setSingleLine();
        miniArtist.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(miniTitle, new LinearLayout.LayoutParams(-1, -2));
        col.addView(miniArtist, new LinearLayout.LayoutParams(-1, -2));
        miniCard.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        miniPlay = iconButton(R.drawable.ic_play);
        miniNext = iconButton(R.drawable.ic_next);
        miniPlay.setPadding(dp(9), dp(9), dp(9), dp(9));
        miniPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) service.togglePlay();
            }
        });
        miniNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null && service.currentSong() != null) service.next();
            }
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(40), dp(40));
        mp.leftMargin = dp(4);
        miniCard.addView(miniNext, new LinearLayout.LayoutParams(dp(40), dp(40)));
        miniCard.addView(miniPlay, mp);

        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.setMargins(dp(12), dp(4), dp(12), dp(8));
        p.addView(miniCard, mlp);
        return p;
    }

    // ---------------------------------------------------------------- player page

    private View buildPlayerPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);

        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        p.setPadding(dp(24), dp(20), dp(24), dp(12));
        sv.addView(p, new FrameLayout.LayoutParams(-1, -2));

        pLabel = new TextView(this);
        pLabel.setText("يتم التشغيل الآن");
        pLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pLabel.setLetterSpacing(0.1f);
        pLabel.setGravity(Gravity.CENTER);
        LinearLayout topRow = new LinearLayout(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.addView(makeHomeBtn(), new LinearLayout.LayoutParams(dp(46), dp(46)));
        topRow.addView(pLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        topRow.addView(makeThemeBtn(), new LinearLayout.LayoutParams(dp(46), dp(46)));
        p.addView(topRow, new LinearLayout.LayoutParams(-1, -2));

        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int sz = Math.min(dp(300), Math.min(w - dp(80), (int) (h * 0.40f)));

        // fixed rounded square cover (no rotation)
        artFrame = new FrameLayout(this);
        artFrame.setElevation(dp(14));
        roundOutline(artFrame, 30);
        artBig = new ImageView(this);
        artFrame.addView(artBig, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(sz, sz);
        alp.topMargin = dp(16);
        p.addView(artFrame, alp);

        pTitle = new TextView(this);
        pTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        pTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pTitle.setGravity(Gravity.CENTER);
        pTitle.setSingleLine();
        pTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        pTitle.setMarqueeRepeatLimit(-1);
        pTitle.setSelected(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(26);
        p.addView(pTitle, tlp);

        pArtist = new TextView(this);
        pArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        pArtist.setGravity(Gravity.CENTER);
        pArtist.setSingleLine();
        pArtist.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(-1, -2);
        arlp.topMargin = dp(4);
        p.addView(pArtist, arlp);

        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        curTime = new TextView(this);
        curTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        curTime.setText("0:00");
        totalTime = new TextView(this);
        totalTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        totalTime.setText("0:00");
        seek = new SeekBar(this);
        seek.setMax(1);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                if (fromUser) curTime.setText(fmt(pr));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                if (service != null) service.seekTo(sb.getProgress());
            }
        });
        r2.addView(curTime, new LinearLayout.LayoutParams(-2, -2));
        r2.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
        r2.addView(totalTime, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(-1, -2);
        r2lp.topMargin = dp(22);
        p.addView(r2, r2lp);

        LinearLayout r3 = new LinearLayout(this);
        r3.setGravity(Gravity.CENTER);
        btnShuffle = iconButton(R.drawable.ic_shuffle);
        btnPrev = iconButton(R.drawable.ic_prev);
        btnPlay = iconButton(R.drawable.ic_play);
        btnNext = iconButton(R.drawable.ic_next);
        btnRepeat = iconButton(R.drawable.ic_repeat);
        btnPlay.setPadding(dp(18), dp(18), dp(18), dp(18));
        btnPrev.setPadding(dp(14), dp(14), dp(14), dp(14));
        btnNext.setPadding(dp(14), dp(14), dp(14), dp(14));
        btnShuffle.setPadding(dp(9), dp(9), dp(9), dp(9));
        btnRepeat.setPadding(dp(9), dp(9), dp(9), dp(9));
        addCtl(r3, btnShuffle, 40);
        addCtl(r3, btnPrev, 58);
        addCtl(r3, btnPlay, 76);
        addCtl(r3, btnNext, 58);
        addCtl(r3, btnRepeat, 40);
        LinearLayout.LayoutParams r3lp = new LinearLayout.LayoutParams(-1, -2);
        r3lp.topMargin = dp(10);
        p.addView(r3, r3lp);

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service == null) return;
                if (service.currentSong() == null) {
                    playAt(0);
                } else {
                    service.togglePlay();
                }
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null && service.currentSong() != null) service.next();
            }
        });
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null && service.currentSong() != null) service.prev();
            }
        });
        btnShuffle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) service.toggleShuffle();
            }
        });
        btnRepeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) service.cycleRepeat();
            }
        });
        return sv;
    }

    private void addCtl(LinearLayout row, ImageView v, int size) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        lp.setMargins(dp(4), 0, dp(4), 0);
        row.addView(v, lp);
    }

    // ---------------------------------------------------------------- settings page

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        primaryTexts.add(t);
        return t;
    }

    private TextView desc(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        secondaryTexts.add(t);
        return t;
    }

    private TextView pill(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        pills.add(t);
        return t;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        cards.add(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(16), dp(8), dp(16), dp(4));
        parent.addView(c, lp);
        return c;
    }

    private View buildSettingsPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(0, dp(14), 0, dp(16));
        sv.addView(p, new FrameLayout.LayoutParams(-1, -2));

        settingsTitle = new TextView(this);
        settingsTitle.setText("الإعدادات");
        settingsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        settingsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        settingsTitle.setPadding(dp(20), 0, dp(20), dp(6));
        primaryTexts.add(settingsTitle);
        settingsTitle.setPadding(dp(20), 0, dp(8), dp(6));
        LinearLayout sHead = new LinearLayout(this);
        sHead.setGravity(Gravity.CENTER_VERTICAL);
        sHead.setPadding(0, 0, dp(16), 0);
        sHead.addView(settingsTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        sHead.addView(makeHomeBtn(), new LinearLayout.LayoutParams(dp(46), dp(46)));
        p.addView(sHead, new LinearLayout.LayoutParams(-1, -2));

        // volume boost: a slider
        LinearLayout cBoost = card(p);
        cBoost.addView(sectionTitle("تعزيز الصوت"));
        cBoost.addView(desc("اسحب الشريط لرفع صوت الأغنية. أول ما تحركه، صوت الجهاز بيتحط على الحد الأقصى"));
        boostLabel = new TextView(this);
        boostLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        boostLabel.setTypeface(Typeface.DEFAULT_BOLD);
        primaryTexts.add(boostLabel);
        LinearLayout.LayoutParams bllp = new LinearLayout.LayoutParams(-2, -2);
        bllp.topMargin = dp(10);
        cBoost.addView(boostLabel, bllp);
        boostSeek = new SeekBar(this);
        boostSeek.setMax(100);
        int savedBoost = prefs.getInt("boost_pct", 0);
        boostSeek.setProgress(savedBoost);
        updateBoostLabel(savedBoost);
        boostSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                updateBoostLabel(pr);
                if (fromUser && service != null) service.setBoostPercent(pr);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                boostTouching = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                boostTouching = false;
                if (service != null) service.setBoostPercent(sb.getProgress());
            }
        });
        cBoost.addView(boostSeek, new LinearLayout.LayoutParams(-1, -2));
        boostWarn = new TextView(this);
        boostWarn.setText("تحذير: المستوى العالي ممكن يسبب تشويش وضرر للسمع، خفّضه لو الصوت اتشوّه");
        boostWarn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        boostWarn.setTextColor(0xFFFF8A80);
        boostWarn.setVisibility(View.GONE);
        LinearLayout.LayoutParams bwlp = new LinearLayout.LayoutParams(-2, -2);
        bwlp.topMargin = dp(8);
        cBoost.addView(boostWarn, bwlp);
        updateBoostLabel(savedBoost);

        // sleep timer
        LinearLayout cSleep = card(p);
        cSleep.addView(sectionTitle("مؤقت النوم"));
        cSleep.addView(desc("الموسيقى هتتوقف لوحدها بعد المدة اللي تختارها"));
        LinearLayout sRow = new LinearLayout(this);
        int[] mins = {15, 30, 45, 60};
        for (int i = 0; i < mins.length; i++) {
            final int m = mins[i];
            TextView chip = pill(m + " د");
            chip.setPadding(dp(4), dp(11), dp(4), dp(11));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startSleep(m);
                }
            });
            LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(0, -2, 1f);
            chlp.setMargins(dp(3), 0, dp(3), 0);
            sRow.addView(chip, chlp);
        }
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(-1, -2);
        srlp.topMargin = dp(12);
        cSleep.addView(sRow, srlp);
        sleepStatus = desc("المؤقت مقفول");
        sleepStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams sslp = new LinearLayout.LayoutParams(-2, -2);
        sslp.topMargin = dp(10);
        cSleep.addView(sleepStatus, sslp);
        TextView cancelSleep = pill("إلغاء المؤقت");
        cancelSleep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) service.setSleepTimer(0);
                refreshSleepStatus();
            }
        });
        LinearLayout.LayoutParams csp = new LinearLayout.LayoutParams(-1, -2);
        csp.topMargin = dp(10);
        cSleep.addView(cancelSleep, csp);

        // theme
        LinearLayout cTheme = card(p);
        cTheme.addView(sectionTitle("المظهر"));
        cTheme.addView(desc("بدّل بين الوضع الداكن والفاتح (رمادي)"));
        themePill = pill("");
        themePill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTheme();
            }
        });
        LinearLayout.LayoutParams tplp = new LinearLayout.LayoutParams(-1, -2);
        tplp.topMargin = dp(12);
        cTheme.addView(themePill, tplp);

        // home-screen shortcut with a custom icon
        LinearLayout c4 = card(p);
        c4.addView(sectionTitle("أيقونة مخصصة"));
        c4.addView(desc("اختار صورة من المعرض وتتضاف كاختصار على الشاشة الرئيسية"));
        TextView customIcon = pill("أيقونة من المعرض");
        customIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage(REQ_ICON);
            }
        });
        LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(-1, -2);
        ciLp.topMargin = dp(12);
        c4.addView(customIcon, ciLp);

        // app
        LinearLayout c3 = card(p);
        c3.addView(sectionTitle("التطبيق"));
        c3.addView(desc("لو قفلت التطبيق من هنا الموسيقى هتتوقف تماماً"));
        closeBtn = new TextView(this);
        closeBtn.setText("إيقاف الموسيقى وإغلاق التطبيق");
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setPadding(dp(14), dp(12), dp(14), dp(12));
        closeBtn.setTextColor(0xFFFF6B6B);
        closeBtn.setBackground(roundRect(0x33FF5252, 16));
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) {
                    service.closeAll();
                } else {
                    finish();
                }
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(12);
        c3.addView(closeBtn, clp);

        TextView foot = desc("LORD MUSIC  •  v1.0");
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(18);
        p.addView(foot, flp);
        TextView sig = desc("© 2026 FLASH Team. All Rights Reserved");
        sig.setGravity(Gravity.CENTER);
        sig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams sglp = new LinearLayout.LayoutParams(-1, -2);
        sglp.topMargin = dp(4);
        p.addView(sig, sglp);
        return sv;
    }

    private void updateBoostLabel(int pr) {
        boostLabel.setText(pr + "%");
        if (boostWarn != null) boostWarn.setVisibility(pr >= 80 ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- trash page

    private View buildTrashPage() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(14), dp(20), dp(6));
        trashTitle = new TextView(this);
        trashTitle.setText("المحذوفات");
        trashTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        trashTitle.setTypeface(Typeface.DEFAULT_BOLD);
        primaryTexts.add(trashTitle);
        trashSub = desc("");
        trashSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.addView(trashTitle, new LinearLayout.LayoutParams(-2, -2));
        tcol.addView(trashSub, new LinearLayout.LayoutParams(-2, -2));
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(makeHomeBtn(), new LinearLayout.LayoutParams(dp(46), dp(46)));
        p.addView(head, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(16), dp(8), dp(16), dp(8));
        tAll = pill("تحديد الكل");
        tRestore = pill("استرجاع");
        tDelete = pill("إفراغ السلة");
        TextView[] bp = {tAll, tRestore, tDelete};
        for (int i = 0; i < bp.length; i++) {
            bp[i].setPadding(dp(8), dp(11), dp(8), dp(11));
            bp[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            bp[i].setSingleLine();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            bar.addView(bp[i], lp);
        }
        tAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (trashed.isEmpty()) return;
                if (allTrashSelected()) {
                    selTrash.clear();
                } else {
                    for (Song s : trashed) selTrash.add(Long.valueOf(s.id));
                }
                refreshTrashUi();
            }
        });
        tRestore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<Song> l = selectedTrash();
                if (l.isEmpty()) {
                    Toast.makeText(MainActivity.this, "حدد أغاني الأول", Toast.LENGTH_SHORT).show();
                    return;
                }
                restoreSongs(l);
            }
        });
        tDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (trashed.isEmpty()) return;
                List<Song> l = selectedTrash();
                if (l.isEmpty()) l = new ArrayList<Song>(trashed);
                deletePermanently(l);
            }
        });
        p.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout frame = new FrameLayout(this);
        trashList = new ListView(this);
        trashList.setDivider(null);
        trashList.setDividerHeight(0);
        trashList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        trashList.setVerticalScrollBarEnabled(false);
        trashList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        trashList.setClipToPadding(false);
        trashList.setPadding(0, 0, 0, dp(8));
        trashAdapter = new TrashAdapter();
        trashList.setAdapter(trashAdapter);
        trashList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= trashed.size()) return;
                Long k = Long.valueOf(trashed.get(position).id);
                if (!selTrash.remove(k)) selTrash.add(k);
                refreshTrashUi();
            }
        });
        trashEmpty = new TextView(this);
        trashEmpty.setText("السلة فاضية");
        trashEmpty.setGravity(Gravity.CENTER);
        trashEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        secondaryTexts.add(trashEmpty);
        frame.addView(trashList, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(trashEmpty, new FrameLayout.LayoutParams(-1, -1));
        trashList.setEmptyView(trashEmpty);
        p.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1f));
        return p;
    }



    // ---------------------------------------------------------------- bottom nav

    private void buildNav() {
        navDiv = new View(this);
        content.addView(navDiv, new LinearLayout.LayoutParams(-1, dp(1)));

        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setGravity(Gravity.CENTER_VERTICAL);
        navBar.setPadding(dp(4), dp(8), dp(4), dp(8));

        int[] icons = {R.drawable.ic_library, R.drawable.ic_play_circle, R.drawable.ic_delete, R.drawable.ic_settings};
        String[] labels = {"الأغاني", "المشغل", "المحذوفات", "الإعدادات"};
        for (int i = 0; i < NPAGES; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);

            FrameLayout pill = new FrameLayout(this);
            ImageView ic = new ImageView(this);
            ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            setIc(ic, icons[i]);
            pill.addView(ic, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
            cell.addView(pill, new LinearLayout.LayoutParams(dp(60), dp(34)));

            TextView lb = new TextView(this);
            lb.setText(labels[i]);
            lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            lb.setGravity(Gravity.CENTER);
            lb.setSingleLine();
            LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(-2, -2);
            lbp.topMargin = dp(3);
            cell.addView(lb, lbp);

            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPage(idx);
                }
            });
            navCells[i] = cell;
            navPills[i] = pill;
            navIcons[i] = ic;
            navLabels[i] = lb;
            navBar.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        content.addView(navBar, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showPage(int p) {
        page = p;
        for (int i = 0; i < NPAGES; i++) {
            if (i == p) {
                pages[i].animate().cancel();
                pages[i].setVisibility(View.VISIBLE);
                pages[i].setAlpha(0f);
                pages[i].setTranslationY(dp(14));
                pages[i].animate().alpha(1f).translationY(0f).setDuration(260)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else {
                pages[i].animate().cancel();
                pages[i].setVisibility(View.GONE);
            }
        }
        updateNav();
        updateUi();
    }

    private void updateNav() {
        if (navBar == null) return;
        int[] t = Themes.P;
        for (int i = 0; i < NPAGES; i++) {
            boolean sel = i == page;
            navPills[i].setBackground(sel ? roundRect(withAlpha(t[2], Themes.dark ? 0x40 : 0x30), 17) : null);
            tint(navIcons[i], sel ? t[3] : t[4]);
            navLabels[i].setTextColor(sel ? t[3] : t[4]);
            navLabels[i].setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    // ================================================================ theming

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private static int mix(int a, int b, float f) {
        return Color.argb(255,
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    /** Metallic accent circle (play button, checkmarks). */
    private GradientDrawable goldCircle() {
        int[] t = Themes.P;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{mix(t[2], 0xFFFFFFFF, 0.45f), t[2], mix(t[2], 0xFF000000, 0.35f)});
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(1), 0x55FFFFFF);
        return g;
    }

    /** Clear raised circle behind prev / next / home / theme buttons. */
    private GradientDrawable ctlCircle() {
        int[] t = Themes.P;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{mix(t[0], t[3], 0.22f), mix(t[1], t[3], 0.06f)});
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(2), withAlpha(t[3], 0x70));
        return g;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private void applyTheme() {
        int[] t = Themes.P;

        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{t[0], t[1]}));
        // songs page header
        SpannableString ss = new SpannableString("LORD MUSIC");
        ss.setSpan(new ForegroundColorSpan(t[2]), 0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new ForegroundColorSpan(t[4]), 5, 10, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        headerTitle.setText(ss);
        headerSub.setTextColor(t[4]);

        searchBox.setBackground(roundRect(t[5], 23));
        searchBox.setTextColor(t[3]);
        searchBox.setHintTextColor(t[4]);
        emptyView.setTextColor(t[4]);

        // selection bar
        selCount.setTextColor(t[3]);
        tint(selCancel, t[3]);
        selCancel.setBackground(circle(withAlpha(t[3], 0x1A)));
        tint(selDelete, 0xFFFF6B6B);
        selDelete.setBackground(circle(0x33FF5252));

        // mini player
        GradientDrawable mc = roundRect(withAlpha(t[1], 0xEE), 22);
        mc.setStroke(dp(1), withAlpha(t[2], 0x55));
        miniCard.setBackground(mc);
        miniTitle.setTextColor(t[3]);
        miniArtist.setTextColor(t[4]);
        miniArt.setBackground(roundRect(t[5], 12));
        miniPlay.setBackground(goldCircle());
        tint(miniPlay, t[0]);
        tint(miniNext, t[3]);

        // player page
        pLabel.setTextColor(t[4]);
        pTitle.setTextColor(t[3]);
        pArtist.setTextColor(t[4]);
        curTime.setTextColor(t[4]);
        totalTime.setTextColor(t[4]);
        seek.setProgressTintList(ColorStateList.valueOf(t[2]));
        seek.setThumbTintList(ColorStateList.valueOf(t[2]));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(t[3], 0x44)));
        GradientDrawable af = roundRect(t[5], 30);
        af.setStroke(dp(2), withAlpha(t[2], 0x99));
        artFrame.setBackground(af);
        btnPlay.setBackground(goldCircle());
        tint(btnPlay, t[0]);
        tint(btnPrev, t[3]);
        tint(btnNext, t[3]);
        btnPrev.setBackground(ctlCircle());
        btnNext.setBackground(ctlCircle());
        btnPrev.setElevation(dp(6));
        btnNext.setElevation(dp(6));
        miniNext.setBackground(ctlCircle());

        // settings + trash pages
        for (TextView x : primaryTexts) x.setTextColor(t[3]);
        for (TextView x : secondaryTexts) x.setTextColor(t[4]);
        for (View c : cards) {
            GradientDrawable cg = roundRect(t[5], 24);
            cg.setStroke(dp(1), withAlpha(t[2], 0x40));
            c.setBackground(cg);
        }
        for (TextView x : pills) {
            x.setTextColor(t[2]);
            GradientDrawable g = roundRect(withAlpha(t[2], 0x1A), 16);
            g.setStroke(dp(1), withAlpha(t[2], 0x66));
            x.setBackground(g);
        }
        tDelete.setTextColor(0xFFFF6B6B);
        tDelete.setBackground(roundRect(0x33FF5252, 16));
        boostSeek.setProgressTintList(ColorStateList.valueOf(t[2]));
        boostSeek.setThumbTintList(ColorStateList.valueOf(t[2]));
        boostSeek.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(t[3], 0x44)));

        // nav
        navBar.setBackgroundColor(t[1]);
        navDiv.setBackgroundColor(withAlpha(t[3], 0x24));
        for (ImageView h : homeBtns) {
            h.setBackground(ctlCircle());
            h.setElevation(dp(4));
            tint(h, t[3]);
        }
        for (ImageView h : themeBtns) {
            setKind(h, Themes.dark ? Icon3D.SUN : Icon3D.MOON);
            h.setBackground(ctlCircle());
            h.setElevation(dp(4));
            tint(h, t[3]);
        }
        themePill.setText(Themes.dark ? "الوضع الفاتح" : "الوضع الداكن");
        updateSystemBars();
        updateNav();

        updateUi();
    }

    // ================================================================ activity results

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_DEL) {
            if (res == RESULT_OK && delPending != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    finishDeleted(delPending);
                    delPending = null;
                } else {
                    List<Song> again = new ArrayList<Song>(delPending);
                    delPending = null;
                    deletePermanently(again);
                }
            } else {
                delPending = null;
            }
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        if (req == REQ_COVER && coverTarget != null) {
            final Song target = coverTarget;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final boolean ok = saveCover(target.id, decodeUri(u, 1200));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (ok) refreshCoverFor(target.id);
                            else Toast.makeText(MainActivity.this, "تعذر تحميل الصورة", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }).start();
        } else if (req == REQ_ICON) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Bitmap bm = decodeUri(u, 1024);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (bm != null) createCustomShortcut(bm);
                            else Toast.makeText(MainActivity.this, "تعذر تحميل الصورة", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }).start();
        }
    }

    // ================================================================ state -> UI

    private static String fmt(int ms) {
        int total = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }

    private void updateUi() {
        if (root == null || pages[P_SETTINGS] == null || boostSeek == null || trashAdapter == null) return;
        int[] t = Themes.P;
        Song s = service != null ? service.currentSong() : null;
        boolean playing = service != null && service.isPlaying();

        String title = s == null ? "اختر أغنية" : s.title;
        String artist = s == null ? "LORD MUSIC" : s.artist;
        pTitle.setText(title);
        pArtist.setText(artist);
        miniTitle.setText(title);
        miniArtist.setText(artist);
        miniCard.setVisibility(s != null ? View.VISIBLE : View.GONE);

        Bitmap art = service != null ? service.getArt() : null;
        setCover(artBig, art, t[4], dp(70));
        setCover(miniArt, art, t[4], dp(12));

        int pp = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        setIc(btnPlay, pp);
        tint(btnPlay, t[0]);
        setIc(miniPlay, pp);
        tint(miniPlay, t[0]);

        if (service != null && !boostTouching) {
            int bp = service.getBoostPercent();
            if (boostSeek.getProgress() != bp) boostSeek.setProgress(bp);
            updateBoostLabel(bp);
        }

        boolean shuf = service != null && service.isShuffle();
        int rep = service != null ? service.getRepeat() : 0;
        tint(btnShuffle, shuf ? t[3] : t[4]);
        setIc(btnRepeat, rep == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        tint(btnRepeat, rep != 0 ? t[3] : t[4]);

        adapter.notifyDataSetChanged();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (page == P_SETTINGS) refreshSleepStatus();
            Song s = service != null ? service.currentSong() : null;
            if (s != null) {
                int dur = service.getDuration();
                int pos = service.getPosition();
                if (dur <= 0) dur = (int) s.duration;
                seek.setMax(Math.max(dur, 1));
                if (!dragging) {
                    seek.setProgress(pos);
                    curTime.setText(fmt(pos));
                }
                totalTime.setText(fmt(dur));
            } else {
                seek.setMax(1);
                seek.setProgress(0);
                curTime.setText("0:00");
                totalTime.setText("0:00");
            }
            handler.postDelayed(this, 400);
        }
    };

    // ================================================================ bottom sheets

    private Dialog newSheet(View content) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(content);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(-1, -2);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.setDimAmount(0.55f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return d;
    }

    private LinearLayout sheetBox() {
        int[] t = Themes.P;
        LinearLayout b = new LinearLayout(this);
        b.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(18), dp(10), dp(18), dp(22));
        GradientDrawable g = new GradientDrawable();
        g.setColor(withAlpha(t[1], 0xFA));
        float r = dp(28);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        g.setStroke(dp(1), withAlpha(t[2], 0x55));
        b.setBackground(g);
        View h = new View(this);
        h.setBackground(roundRect(withAlpha(t[3], 0x44), 3));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(40), dp(5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(14);
        b.addView(h, hp);
        return b;
    }

    private TextView sheetTitle(String s) {
        int[] t = Themes.P;
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(t[3]);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(4), 0, dp(4), dp(12));
        return tv;
    }

    private View sheetRow(int icon, String label, int color, View.OnClickListener l) {
        int[] t = Themes.P;
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(14), dp(10));
        r.setBackground(roundRect(withAlpha(t[3], 0x0D), 18));
        ImageView iv = new ImageView(this);
        setIc(iv, icon);
        tint(iv, color);
        iv.setPadding(dp(9), dp(9), dp(9), dp(9));
        iv.setBackground(circle(withAlpha(color, 0x26)));
        r.addView(iv, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(color == 0xFFFF6B6B ? color : t[3]);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setPadding(dp(14), 0, 0, 0);
        r.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private TextView dialogButton(String text, boolean primary) {
        int[] t = Themes.P;
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(14), dp(13), dp(14), dp(13));
        if (primary) {
            b.setTextColor(t[0]);
            b.setBackground(roundRect(t[2], 16));
        } else {
            b.setTextColor(t[2]);
            GradientDrawable g = roundRect(withAlpha(t[2], 0x26), 16);
            g.setStroke(dp(1), withAlpha(t[2], 0x66));
            b.setBackground(g);
        }
        return b;
    }

    private void showSongMenu(final Song s) {
        final int[] t = Themes.P;
        LinearLayout b = sheetBox();
        final Dialog d = newSheet(b);

        LinearLayout hd = new LinearLayout(this);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        hd.setPadding(dp(4), 0, dp(4), dp(14));
        ImageView cv = new ImageView(this);
        roundOutline(cv, 14);
        cv.setBackground(roundRect(t[5], 14));
        setCover(cv, artCache.get(s.id), t[4], dp(12));
        hd.addView(cv, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        TextView tt = new TextView(this);
        tt.setText(s.title);
        tt.setTextColor(t[3]);
        tt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tt.setTypeface(Typeface.DEFAULT_BOLD);
        tt.setSingleLine();
        tt.setEllipsize(TextUtils.TruncateAt.END);
        TextView aa = new TextView(this);
        aa.setText(s.artist);
        aa.setTextColor(t[4]);
        aa.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        aa.setSingleLine();
        aa.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(tt, new LinearLayout.LayoutParams(-1, -2));
        col.addView(aa, new LinearLayout.LayoutParams(-1, -2));
        hd.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        b.addView(hd, new LinearLayout.LayoutParams(-1, -2));

        b.addView(sheetRow(R.drawable.ic_image, "تعديل الصورة", t[2], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                coverTarget = s;
                pickImage(REQ_COVER);
            }
        }));
        if (new File(getFilesDir(), "covers/" + s.id + ".jpg").exists()) {
            b.addView(sheetRow(R.drawable.ic_restore, "استرجاع الصورة الأصلية", t[2], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    new File(getFilesDir(), "covers/" + s.id + ".jpg").delete();
                    refreshCoverFor(s.id);
                }
            }));
        }
        b.addView(sheetRow(R.drawable.ic_edit, "تعديل الاسم", t[2], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                showRenameDialog(s);
            }
        }));
        b.addView(sheetRow(R.drawable.ic_delete, "حذف", 0xFFFF6B6B, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                trashSong(s);
            }
        }));
        d.show();
    }

    private void showRenameDialog(final Song s) {
        final int[] t = Themes.P;
        LinearLayout b = sheetBox();
        final Dialog d = newSheet(b);
        b.addView(sheetTitle("تعديل الاسم"));

        final EditText et = new EditText(this);
        et.setText(s.title);
        et.setSelectAllOnFocus(true);
        et.setSingleLine();
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        et.setTextColor(t[3]);
        et.setHintTextColor(t[4]);
        et.setHint("اسم الأغنية");
        et.setPadding(dp(16), 0, dp(16), 0);
        et.setBackground(roundRect(t[5], 18));
        b.addView(et, new LinearLayout.LayoutParams(-1, dp(52)));

        LinearLayout row = new LinearLayout(this);
        TextView cancel = dialogButton("إلغاء", false);
        TextView save = dialogButton("حفظ", true);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1f);
        l1.rightMargin = dp(6);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, -2, 1f);
        l2.leftMargin = dp(6);
        row.addView(cancel, l1);
        row.addView(save, l2);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = dp(16);
        b.addView(row, rl);

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String n = et.getText().toString().trim();
                if (n.length() == 0) {
                    Toast.makeText(MainActivity.this, "اكتب اسم الأول", Toast.LENGTH_SHORT).show();
                    return;
                }
                s.title = n;
                prefs.edit().putString("ov_t_" + s.id, n).apply();
                d.dismiss();
                adapter.notifyDataSetChanged();
                if (service != null) service.refreshNotification();
                updateUi();
            }
        });
        d.show();
        et.requestFocus();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    // ================================================================ trash

    private void loadTrashTimes() {
        trashTimes.clear();
        Set<String> st = prefs.getStringSet("trash", null);
        if (st == null) return;
        for (String x : st) {
            int k = x.indexOf(':');
            if (k <= 0) continue;
            try {
                trashTimes.put(Long.valueOf(Long.parseLong(x.substring(0, k))),
                        Long.valueOf(Long.parseLong(x.substring(k + 1))));
            } catch (Exception ignored) {
            }
        }
    }

    private void saveTrash() {
        Set<String> st = new HashSet<String>();
        for (java.util.Map.Entry<Long, Long> e : trashTimes.entrySet()) {
            st.add(e.getKey() + ":" + e.getValue());
        }
        prefs.edit().putStringSet("trash", st).apply();
    }

    private void updateCount() {
        headerSub.setText(allSongs.size() + " أغنية");
    }



    private void trashSong(Song s) {
        List<Song> one = new ArrayList<Song>();
        one.add(s);
        trashSongs(one);
    }

    private void trashSongs(List<Song> list) {
        if (list.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Song s : list) {
            trashTimes.put(Long.valueOf(s.id), Long.valueOf(now));
            allSongs.remove(s);
            trashed.add(s);
            if (service != null) service.removeSong(s.id);
        }
        saveTrash();
        filter(searchBox.getText().toString());
        updateCount();
        refreshTrashUi();
        updateUi();
        Toast.makeText(this, list.size() == 1
                ? "اتنقلت لسلة المحذوفات وهتتحذف نهائياً بعد 12 يوم"
                : "اتنقل " + list.size() + " أغنية لسلة المحذوفات وهيتحذفوا نهائياً بعد 12 يوم",
                Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- selection (songs page)

    private void startSelection(Song s) {
        selMode = true;
        selSongs.clear();
        selSongs.add(Long.valueOf(s.id));
        updateSelectionUi();
        try {
            listView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignored) {
        }
    }

    private void toggleSongSelection(Song s) {
        Long k = Long.valueOf(s.id);
        if (!selSongs.remove(k)) selSongs.add(k);
        updateSelectionUi();
    }

    private void clearSongSelection() {
        selMode = false;
        selSongs.clear();
        updateSelectionUi();
    }

    private boolean allShownSelected() {
        if (shown.isEmpty()) return false;
        for (Song s : shown) {
            if (!selSongs.contains(Long.valueOf(s.id))) return false;
        }
        return true;
    }

    private void selectAllSongs() {
        if (allShownSelected()) {
            selSongs.clear();
        } else {
            for (Song s : shown) selSongs.add(Long.valueOf(s.id));
        }
        updateSelectionUi();
    }

    private void deleteSelected() {
        if (selSongs.isEmpty()) {
            Toast.makeText(this, "حدد أغاني الأول", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Song> l = new ArrayList<Song>();
        for (Song s : allSongs) {
            if (selSongs.contains(Long.valueOf(s.id))) l.add(s);
        }
        clearSongSelection();
        trashSongs(l);
    }

    private void updateSelectionUi() {
        if (selBar == null) return;
        selBar.setVisibility(selMode ? View.VISIBLE : View.GONE);
        normalHead.setVisibility(selMode ? View.GONE : View.VISIBLE);
        selCount.setText(selSongs.isEmpty() ? "اختار أغاني" : selSongs.size() + " محدد");
        selAll.setText(allShownSelected() ? "إلغاء التحديد" : "تحديد الكل");
        selDelete.setAlpha(selSongs.isEmpty() ? 0.35f : 1f);
        adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- trash page logic

    private List<Song> selectedTrash() {
        List<Song> l = new ArrayList<Song>();
        for (Song s : trashed) {
            if (selTrash.contains(Long.valueOf(s.id))) l.add(s);
        }
        return l;
    }

    private boolean allTrashSelected() {
        if (trashed.isEmpty()) return false;
        for (Song s : trashed) {
            if (!selTrash.contains(Long.valueOf(s.id))) return false;
        }
        return true;
    }

    private void refreshTrashUi() {
        if (trashAdapter == null) return;
        Set<Long> ids = new HashSet<Long>();
        for (Song s : trashed) ids.add(Long.valueOf(s.id));
        selTrash.retainAll(ids);
        boolean any = !selTrash.isEmpty();
        boolean empty = trashed.isEmpty();
        trashSub.setText(empty ? "مفيش أغاني محذوفة"
                : trashed.size() + " أغنية  •  بتتحذف نهائياً بعد 12 يوم");
        tAll.setText(allTrashSelected() ? "إلغاء التحديد" : "تحديد الكل");
        tAll.setAlpha(empty ? 0.35f : 1f);
        tRestore.setAlpha(any ? 1f : 0.35f);
        tDelete.setText(any ? "حذف نهائي (" + selTrash.size() + ")" : "إفراغ السلة");
        tDelete.setAlpha(empty ? 0.35f : 1f);
        trashAdapter.notifyDataSetChanged();
    }

    private void restoreSongs(List<Song> list) {
        if (list.isEmpty()) return;
        for (Song s : list) {
            trashTimes.remove(Long.valueOf(s.id));
            trashed.remove(s);
            allSongs.add(s);
        }
        saveTrash();
        selTrash.clear();
        Collections.sort(allSongs, new java.util.Comparator<Song>() {
            @Override
            public int compare(Song a, Song b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        filter(searchBox.getText().toString());
        updateCount();
        refreshTrashUi();
        Toast.makeText(this, "اتسترجع " + list.size() + " أغنية", Toast.LENGTH_SHORT).show();
    }





    // Permanent delete of the real audio file. Android asks the user to confirm (that's a system rule).
    private void deletePermanently(List<Song> list) {
        if (list.isEmpty()) return;
        delPending = new ArrayList<Song>(list);
        if (Build.VERSION.SDK_INT >= 30) {
            List<Uri> us = new ArrayList<Uri>();
            for (Song s : delPending) us.add(s.uri);
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), us);
                startIntentSenderForResult(pi.getIntentSender(), REQ_DEL, null, 0, 0, 0);
            } catch (Exception e) {
                delPending = null;
                Toast.makeText(this, "تعذر الحذف النهائي", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        List<Song> done = new ArrayList<Song>();
        for (Song s : delPending) {
            try {
                int n = getContentResolver().delete(s.uri, null, null);
                if (n > 0) done.add(s);
            } catch (SecurityException e) {
                if (Build.VERSION.SDK_INT >= 29 && e instanceof RecoverableSecurityException) {
                    finishDeleted(done);
                    delPending.removeAll(done);
                    try {
                        startIntentSenderForResult(((RecoverableSecurityException) e).getUserAction()
                                .getActionIntent().getIntentSender(), REQ_DEL, null, 0, 0, 0);
                    } catch (Exception ignored) {
                        delPending = null;
                    }
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        finishDeleted(done);
        delPending = null;
    }

    private void finishDeleted(List<Song> done) {
        if (done == null || done.isEmpty()) return;
        for (Song s : done) {
            trashTimes.remove(Long.valueOf(s.id));
            trashed.remove(s);
            allSongs.remove(s);
            new File(getFilesDir(), "covers/" + s.id + ".jpg").delete();
            prefs.edit().remove("ov_t_" + s.id).apply();
        }
        saveTrash();
        filter(searchBox.getText().toString());
        updateCount();
        refreshTrashUi();
        Toast.makeText(this, "تم الحذف نهائياً", Toast.LENGTH_SHORT).show();
    }

    // ================================================================ images / icon

    private void pickImage(int req) {
        Intent i = new Intent(Intent.ACTION_PICK);
        i.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            Intent o = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            o.addCategory(Intent.CATEGORY_OPENABLE);
            o.setType("image/*");
            try {
                startActivityForResult(o, req);
            } catch (Exception e2) {
                Toast.makeText(this, "مفيش تطبيق معرض متاح", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private Bitmap decodeUri(Uri u, int max) {
        try {
            InputStream in = getContentResolver().openInputStream(u);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            int sample = 1;
            while (o.outWidth / sample > max || o.outHeight / sample > max) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            in = getContentResolver().openInputStream(u);
            Bitmap bmp = BitmapFactory.decodeStream(in, null, o2);
            in.close();
            if (bmp == null) return null;
            int rot = 0;
            try {
                InputStream ei = getContentResolver().openInputStream(u);
                ExifInterface ex = new ExifInterface(ei);
                int ori = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                ei.close();
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            } catch (Exception ignored) {
            }
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap squareBitmap(Bitmap b, int size) {
        int s = Math.min(b.getWidth(), b.getHeight());
        Bitmap c = Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
        return Bitmap.createScaledBitmap(c, size, size, true);
    }

    private boolean saveCover(long id, Bitmap b) {
        if (b == null) return false;
        try {
            File dir = new File(getFilesDir(), "covers");
            dir.mkdirs();
            FileOutputStream fo = new FileOutputStream(new File(dir, id + ".jpg"));
            squareBitmap(b, 600).compress(Bitmap.CompressFormat.JPEG, 92, fo);
            fo.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshCoverFor(long id) {
        artCache.remove(Long.valueOf(id));
        noArt.remove(Long.valueOf(id));
        pending.remove(Long.valueOf(id));
        Song cur = service != null ? service.currentSong() : null;
        if (cur != null && cur.id == id) service.reloadArt();
        adapter.notifyDataSetChanged();
        updateUi();
    }

    private void createCustomShortcut(Bitmap src) {
        try {
            ShortcutManager sm = (ShortcutManager) getSystemService(Context.SHORTCUT_SERVICE);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                Toast.makeText(this, "اللانشر عندك مش بيدعم إضافة اختصارات", Toast.LENGTH_LONG).show();
                return;
            }
            Intent li = new Intent(this, MainActivity.class);
            li.setAction(Intent.ACTION_MAIN);
            ShortcutInfo info = new ShortcutInfo.Builder(this, "custom_" + System.currentTimeMillis())
                    .setShortLabel("LORD MUSIC")
                    .setIcon(Icon.createWithAdaptiveBitmap(squareBitmap(src, 432)))
                    .setIntent(li)
                    .build();
            sm.requestPinShortcut(info, null);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر إنشاء الأيقونة", Toast.LENGTH_SHORT).show();
        }
    }







    // ================================================================ list adapters

    private void styleMark(ImageView mark, boolean on) {
        int[] t = Themes.P;
        if (on) {
            mark.setBackground(goldCircle());
            setIc(mark, R.drawable.ic_check);
            tint(mark, t[0]);
        } else {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setStroke(dp(2), withAlpha(t[3], 0x66));
            mark.setBackground(g);
            mark.setImageDrawable(null);
            mark.clearColorFilter();
        }
    }

    private ImageView newMark(Context c) {
        ImageView m = new ImageView(c);
        m.setPadding(dp(4), dp(4), dp(4), dp(4));
        return m;
    }

    private class Row {
        LinearLayout outer, inner;
        ImageView mark, cover, more;
        TextView title, artist, dur;

        Row() {
            Context c = MainActivity.this;
            outer = new LinearLayout(c);
            outer.setPadding(dp(12), dp(3), dp(12), dp(3));
            inner = new LinearLayout(c);
            inner.setGravity(Gravity.CENTER_VERTICAL);
            inner.setPadding(dp(8), dp(8), dp(4), dp(8));
            outer.addView(inner, new LinearLayout.LayoutParams(-1, -2));

            mark = newMark(c);
            mark.setVisibility(View.GONE);
            LinearLayout.LayoutParams mk = new LinearLayout.LayoutParams(dp(26), dp(26));
            mk.rightMargin = dp(10);
            inner.addView(mark, mk);

            cover = new ImageView(c);
            roundOutline(cover, 14);
            inner.addView(cover, new LinearLayout.LayoutParams(dp(56), dp(56)));

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, dp(8), 0);
            title = new TextView(c);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            artist = new TextView(c);
            artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            artist.setSingleLine();
            artist.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(title, new LinearLayout.LayoutParams(-1, -2));
            col.addView(artist, new LinearLayout.LayoutParams(-1, -2));
            inner.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

            dur = new TextView(c);
            dur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            inner.addView(dur, new LinearLayout.LayoutParams(-2, -2));

            more = new ImageView(c);
            setIc(more, R.drawable.ic_more);
            more.setPadding(dp(6), dp(6), dp(6), dp(6));
            LinearLayout.LayoutParams mlp2 = new LinearLayout.LayoutParams(dp(38), dp(38));
            mlp2.leftMargin = dp(6);
            inner.addView(more, mlp2);
        }
    }

    private class SongAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int p) {
            return shown.get(p);
        }

        @Override
        public long getItemId(int p) {
            return shown.get(p).id;
        }

        @Override
        public View getView(int p, View cv, ViewGroup parent) {
            Row r;
            if (cv == null) {
                r = new Row();
                cv = r.outer;
                cv.setTag(r);
            } else {
                r = (Row) cv.getTag();
            }
            int[] t = Themes.P;
            Song s = shown.get(p);
            Song cur = service != null ? service.currentSong() : null;
            boolean isCur = cur != null && cur.id == s.id;
            boolean isSel = selMode && selSongs.contains(Long.valueOf(s.id));

            r.title.setText(s.title);
            r.artist.setText(s.artist);
            r.dur.setText(fmt((int) s.duration));
            r.title.setTextColor(isCur ? t[2] : t[3]);
            r.artist.setTextColor(t[4]);
            r.dur.setTextColor(isCur ? t[2] : t[4]);
            tint(r.more, t[4]);
            r.more.setVisibility(selMode ? View.INVISIBLE : View.VISIBLE);
            final Song fs = s;
            r.more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSongMenu(fs);
                }
            });

            r.mark.setVisibility(selMode ? View.VISIBLE : View.GONE);
            if (selMode) styleMark(r.mark, isSel);
            r.inner.setBackground(roundRect(isSel ? withAlpha(t[3], 0x26)
                    : isCur ? withAlpha(t[3], 0x18) : Color.TRANSPARENT, 20));
            r.cover.setBackground(roundRect(t[5], 14));

            r.cover.setTag(Long.valueOf(s.id));
            Bitmap b = artCache.get(s.id);
            if (b != null) {
                setCover(r.cover, b, t[4], 0);
            } else {
                setCover(r.cover, null, isCur ? t[2] : t[4], dp(15));
                if (!noArt.contains(s.id)) requestArt(s, r.cover);
            }
            return cv;
        }
    }

    private class TRow {
        LinearLayout outer, inner;
        ImageView mark;
        TextView title, sub;

        TRow() {
            Context c = MainActivity.this;
            outer = new LinearLayout(c);
            outer.setPadding(dp(16), dp(3), dp(16), dp(3));
            inner = new LinearLayout(c);
            inner.setGravity(Gravity.CENTER_VERTICAL);
            inner.setPadding(dp(14), dp(12), dp(14), dp(12));
            outer.addView(inner, new LinearLayout.LayoutParams(-1, -2));

            mark = newMark(c);
            LinearLayout.LayoutParams mk = new LinearLayout.LayoutParams(dp(26), dp(26));
            mk.rightMargin = dp(14);
            inner.addView(mark, mk);

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(c);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            sub = new TextView(c);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setSingleLine();
            col.addView(title, new LinearLayout.LayoutParams(-1, -2));
            col.addView(sub, new LinearLayout.LayoutParams(-1, -2));
            inner.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        }
    }

    private class TrashAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return trashed.size();
        }

        @Override
        public Object getItem(int p) {
            return trashed.get(p);
        }

        @Override
        public long getItemId(int p) {
            return trashed.get(p).id;
        }

        @Override
        public View getView(int p, View cv, ViewGroup parent) {
            TRow r;
            if (cv == null) {
                r = new TRow();
                cv = r.outer;
                cv.setTag(r);
            } else {
                r = (TRow) cv.getTag();
            }
            int[] t = Themes.P;
            Song s = trashed.get(p);
            boolean on = selTrash.contains(Long.valueOf(s.id));
            Long tt = trashTimes.get(Long.valueOf(s.id));
            long left = tt == null ? 0 : TRASH_MS - (System.currentTimeMillis() - tt.longValue());
            long days = Math.max(0, (left + 86400000L - 1) / 86400000L);

            r.title.setText(s.title);
            r.title.setTextColor(t[3]);
            r.sub.setText(days == 0 ? "هتتحذف دلوقتي" : "باقي " + days + " يوم");
            r.sub.setTextColor(t[4]);
            styleMark(r.mark, on);
            r.inner.setBackground(roundRect(on ? withAlpha(t[3], 0x26) : withAlpha(t[3], 0x0D), 18));
            return cv;
        }
    }
}
