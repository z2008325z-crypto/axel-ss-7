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
    private static final int REQ_BG = 2;
    private static final int REQ_COVER = 3;
    private static final int REQ_ICON = 4;
    private static final int REQ_DEL = 5;
    private static final long TRASH_MS = 12L * 24 * 60 * 60 * 1000;

    private MusicService service;
    private boolean bound = false;
    private SharedPreferences prefs;
    private int themeIndex = 0;
    private int dim = 55;
    private int page = 0;
    private boolean dragging = false;
    private boolean loaded = false;

    private final List<Song> allSongs = new ArrayList<Song>();
    private final List<Song> shown = new ArrayList<Song>();
    private final List<Song> trashed = new ArrayList<Song>();
    private final java.util.Map<Long, Long> trashTimes = new java.util.HashMap<Long, Long>();
    private Song coverTarget;
    private List<Song> delPending;
    private TextView trashPill, iconSwitch;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // cover cache
    private final LruCache<Long, Bitmap> artCache = new LruCache<Long, Bitmap>(120);
    private final Set<Long> noArt = Collections.synchronizedSet(new HashSet<Long>());
    private final Set<Long> pending = Collections.synchronizedSet(new HashSet<Long>());
    private final ExecutorService artPool = Executors.newFixedThreadPool(2);

    // shell
    private FrameLayout root, pageHost;
    private ImageView bgImage;
    private View overlay;
    private LinearLayout content, navBar;
    private final View[] pages = new View[3];
    private final LinearLayout[] navCells = new LinearLayout[3];
    private final ImageView[] navIcons = new ImageView[3];
    private final TextView[] navLabels = new TextView[3];

    // songs page
    private TextView headerTitle, headerSub, emptyView;
    private EditText searchBox;
    private ListView listView;
    private SongAdapter adapter;
    private LinearLayout miniCard;
    private ImageView miniArt, miniPlay, miniNext;
    private TextView miniTitle, miniArtist;

    // player page
    private TextView pLabel, pTitle, pArtist, curTime, totalTime;
    private FrameLayout artRing;
    private TextView btnBoost;
    private ImageView artBig, btnShuffle, btnPrev, btnPlay, btnNext, btnRepeat;
    private SeekBar seek;
    private ObjectAnimator spin;

    // settings page
    private TextView settingsTitle;
    private LinearLayout themeGrid;
    private SeekBar dimSeek;
    private TextView closeBtn;
    private final List<TextView> primaryTexts = new ArrayList<TextView>();
    private final List<TextView> secondaryTexts = new ArrayList<TextView>();
    private final List<View> cards = new ArrayList<View>();
    private final List<TextView> pills = new ArrayList<TextView>();

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("axel", MODE_PRIVATE);
        themeIndex = Math.min(prefs.getInt("theme_v2", 0), Themes.T.length - 1);
        dim = prefs.getInt("dim", 55);

        loadTrashTimes();
        buildUi();
        setContentView(root);
        root.requestApplyInsets();
        loadBackgroundFromFile();
        applyTheme();
        applyLauncherIcon(themeIndex);
        showPage(0);
        registerBackCallback();

        if (hasPermission()) {
            loadSongs();
        } else {
            requestPerms();
        }
        updateEmptyText();

        bindService(new Intent(this, MusicService.class), conn, Context.BIND_AUTO_CREATE);

        navBar.setTranslationY(dp(120));
        navBar.animate().translationY(0).setDuration(600).setInterpolator(new DecelerateInterpolator()).start();

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i != null && i.getIntExtra("page", -1) == 1) {
            showPage(1);
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
        if (spin != null) spin.cancel();
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

    // الرجوع: الإعدادات (2) -> المشغل (1) -> الأغاني (0) -> خروج من التطبيق
    private boolean goBackOnePage() {
        if (page > 0) {
            showPage(page - 1);
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
                        updateTrashPill();
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
            iv.setImageResource(R.drawable.ic_music_note);
            iv.setColorFilter(tint);
        }
    }

    // ================================================================ UI building

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private ImageView iconButton(int res) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
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

        bgImage = new ImageView(this);
        bgImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bgImage, new FrameLayout.LayoutParams(-1, -1));

        overlay = new View(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

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

        pages[0] = buildSongsPage();
        pages[1] = buildPlayerPage();
        pages[2] = buildSettingsPage();
        for (int i = 0; i < 3; i++) {
            pageHost.addView(pages[i], new FrameLayout.LayoutParams(-1, -1));
        }
        pages[1].setVisibility(View.GONE);
        pages[2].setVisibility(View.GONE);

        buildNav();
    }

    // ---------------------------------------------------------------- songs page

    private View buildSongsPage() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(14), dp(20), dp(6));
        headerTitle = new TextView(this);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setLetterSpacing(0.14f);
        headerTitle.setSingleLine();
        headerSub = new TextView(this);
        headerSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        headerSub.setText("مكتبتك الموسيقية");
        head.addView(headerTitle, new LinearLayout.LayoutParams(-2, -2));
        head.addView(headerSub, new LinearLayout.LayoutParams(-2, -2));
        p.addView(head, new LinearLayout.LayoutParams(-1, -2));

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
                showSongMenu(shown.get(position));
                return true;
            }
        });
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= shown.size()) return;
                Song s = shown.get(position);
                Song cur = service != null ? service.currentSong() : null;
                if (cur != null && cur.id == s.id) {
                    showPage(1);
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
                showPage(1);
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
        p.addView(pLabel, new LinearLayout.LayoutParams(-2, -2));

        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int sz = Math.min(dp(290), Math.min(w - dp(96), (int) (h * 0.36f)));

        artRing = new FrameLayout(this);
        artRing.setPadding(dp(5), dp(5), dp(5), dp(5));
        artRing.setElevation(dp(10));
        artBig = new ImageView(this);
        ovalOutline(artBig);
        artRing.addView(artBig, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(sz, sz);
        alp.topMargin = dp(26);
        p.addView(artRing, alp);

        spin = ObjectAnimator.ofFloat(artRing, "rotation", 0f, 360f);
        spin.setDuration(16000);
        spin.setRepeatCount(ObjectAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());

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
        btnShuffle.setPadding(dp(9), dp(9), dp(9), dp(9));
        btnRepeat.setPadding(dp(9), dp(9), dp(9), dp(9));
        addCtl(r3, btnShuffle, 40);
        addCtl(r3, btnPrev, 52);
        addCtl(r3, btnPlay, 72);
        addCtl(r3, btnNext, 52);
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
        settingsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        settingsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        settingsTitle.setPadding(dp(20), 0, dp(20), dp(6));
        primaryTexts.add(settingsTitle);
        p.addView(settingsTitle, new LinearLayout.LayoutParams(-1, -2));

        // themes
        LinearLayout c1 = card(p);
        c1.addView(sectionTitle("الثيمات والألوان"));
        c1.addView(desc("اختر لون التطبيق اللي يعجبك"));
        themeGrid = new LinearLayout(this);
        themeGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = dp(10);
        c1.addView(themeGrid, glp);

        // background
        LinearLayout c2 = card(p);
        c2.addView(sectionTitle("الخلفية"));
        c2.addView(desc("حط صورة من المعرض تظهر ورا التطبيق"));
        LinearLayout row = new LinearLayout(this);
        TextView pick = pill("اختيار صورة");
        TextView remove = pill("إزالة الخلفية");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage(REQ_BG);
            }
        });
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new File(getFilesDir(), "bg.jpg").delete();
                bgImage.setImageDrawable(null);
                applyOverlay();
                Toast.makeText(MainActivity.this, "تم إزالة الخلفية", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1f);
        l1.rightMargin = dp(6);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, -2, 1f);
        l2.leftMargin = dp(6);
        row.addView(pick, l1);
        row.addView(remove, l2);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(12);
        c2.addView(row, rlp);

        TextView dimLabel = desc("تعتيم الخلفية");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = dp(14);
        c2.addView(dimLabel, dlp);
        dimSeek = new SeekBar(this);
        dimSeek.setMax(90);
        dimSeek.setProgress(dim);
        dimSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                if (fromUser) {
                    dim = pr;
                    applyOverlay();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putInt("dim", dim).apply();
            }
        });
        c2.addView(dimSeek, new LinearLayout.LayoutParams(-1, -2));

        // app icon
        LinearLayout c4 = card(p);
        c4.addView(sectionTitle("أيقونة التطبيق"));
        c4.addView(desc("الأيقونة بتتغير مع لون الثيم، أو اختار صورة من المعرض"));
        iconSwitch = pill("");
        iconSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean now = !prefs.getBoolean("icon_follow", true);
                prefs.edit().putBoolean("icon_follow", now).apply();
                if (now) applyLauncherIcon(themeIndex);
                updateIconSwitch();
            }
        });
        TextView customIcon = pill("أيقونة من المعرض");
        customIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage(REQ_ICON);
            }
        });
        LinearLayout irow = new LinearLayout(this);
        LinearLayout.LayoutParams i1 = new LinearLayout.LayoutParams(0, -2, 1f);
        i1.rightMargin = dp(6);
        LinearLayout.LayoutParams i2 = new LinearLayout.LayoutParams(0, -2, 1f);
        i2.leftMargin = dp(6);
        irow.addView(iconSwitch, i1);
        irow.addView(customIcon, i2);
        LinearLayout.LayoutParams irlp = new LinearLayout.LayoutParams(-1, -2);
        irlp.topMargin = dp(12);
        c4.addView(irow, irlp);
        TextView iconNote = desc("الأيقونة المخصصة بتتضاف كاختصار على الشاشة الرئيسية");
        LinearLayout.LayoutParams inlp = new LinearLayout.LayoutParams(-2, -2);
        inlp.topMargin = dp(10);
        c4.addView(iconNote, inlp);
        updateIconSwitch();

        // volume boost
        LinearLayout cBoost = card(p);
        cBoost.addView(sectionTitle("تعزيز الصوت"));
        cBoost.addView(desc("يرفع صوت الهاتف والسماعة والإيربودز للحد الأقصى، ويعلّي صوت الأغنية نفسها"));
        btnBoost = pill("");
        btnBoost.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service == null) return;
                service.cycleBoost();
                if (service.getBoost() >= 3) {
                    Toast.makeText(MainActivity.this, "تحذير: المستوى الأقصى ممكن يسبب تشويش وضرر للسمع، خفّضه لو الصوت اتشوّه", Toast.LENGTH_LONG).show();
                }
            }
        });
        btnBoost.setText("🔊 تعزيز الصوت: متوقف");
        LinearLayout.LayoutParams bblp = new LinearLayout.LayoutParams(-1, -2);
        bblp.topMargin = dp(12);
        cBoost.addView(btnBoost, bblp);


        // trash
        LinearLayout c5 = card(p);
        c5.addView(sectionTitle("سلة المهملات"));
        c5.addView(desc("الأغاني المحذوفة بتفضل هنا 12 يوم وبعدها تتحذف نهائياً"));
        trashPill = pill("");
        trashPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTrash();
            }
        });
        LinearLayout.LayoutParams tplp = new LinearLayout.LayoutParams(-1, -2);
        tplp.topMargin = dp(12);
        c5.addView(trashPill, tplp);
        updateTrashPill();

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

        TextView foot = desc("AXEL MUSIC  •  v1.0");
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(16);
        p.addView(foot, flp);
        return sv;
    }

    private void buildThemeGrid() {
        themeGrid.removeAllViews();
        int[] cur = Themes.T[themeIndex];
        int per = 4;
        LinearLayout row = null;
        for (int i = 0; i < Themes.T.length; i++) {
            if (i % per == 0) {
                row = new LinearLayout(this);
                themeGrid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            final int idx = i;
            int[] t = Themes.T[i];

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, dp(8), 0, dp(8));

            View dot = new View(this);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{t[1], t[2]});
            g.setShape(GradientDrawable.OVAL);
            if (i == themeIndex) g.setStroke(dp(3), cur[3]);
            else g.setStroke(dp(1), 0x55888888);
            dot.setBackground(g);
            cell.addView(dot, new LinearLayout.LayoutParams(dp(46), dp(46)));

            TextView name = new TextView(this);
            name.setText(Themes.NAMES[i]);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            name.setGravity(Gravity.CENTER);
            name.setTextColor(i == themeIndex ? cur[2] : cur[4]);
            name.setPadding(0, dp(5), 0, 0);
            cell.addView(name, new LinearLayout.LayoutParams(-2, -2));

            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    themeIndex = idx;
                    prefs.edit().putInt("theme_v2", idx).apply();
                    applyTheme();
                    applyLauncherIcon(idx);
                    if (service != null) service.refreshNotification();
                }
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        int rem = Themes.T.length % per;
        if (rem != 0 && row != null) {
            for (int k = rem; k < per; k++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
    }

    // ---------------------------------------------------------------- bottom nav

    private void buildNav() {
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setGravity(Gravity.CENTER_VERTICAL);
        navBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        navBar.setElevation(dp(10));

        int[] icons = {R.drawable.ic_library, R.drawable.ic_play_circle, R.drawable.ic_settings};
        String[] labels = {"الأغاني", "المشغل", "الإعدادات"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(8), dp(4), dp(7));

            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            cell.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));

            TextView lb = new TextView(this);
            lb.setText(labels[i]);
            lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            lb.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(-2, -2);
            lbp.topMargin = dp(2);
            cell.addView(lb, lbp);

            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPage(idx);
                }
            });
            navCells[i] = cell;
            navIcons[i] = ic;
            navLabels[i] = lb;
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1f);
            np.setMargins(dp(4), 0, dp(4), 0);
            navBar.addView(cell, np);
        }
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.setMargins(dp(14), dp(4), dp(14), dp(10));
        content.addView(navBar, nlp);
    }

    private void showPage(int p) {
        page = p;
        for (int i = 0; i < 3; i++) {
            if (i == p) {
                pages[i].setVisibility(View.VISIBLE);
                pages[i].setAlpha(0f);
                pages[i].animate().alpha(1f).setDuration(220).start();
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
        int[] t = Themes.T[themeIndex];
        for (int i = 0; i < 3; i++) {
            boolean sel = i == page;
            navCells[i].setBackground(sel ? roundRect(withAlpha(t[2], 0x30), 22) : null);
            navIcons[i].setColorFilter(sel ? t[2] : t[4]);
            navLabels[i].setTextColor(sel ? t[2] : t[4]);
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

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private void applyOverlay() {
        int[] t = Themes.T[themeIndex];
        boolean hasImg = bgImage.getDrawable() != null;
        overlay.setVisibility(hasImg ? View.VISIBLE : View.GONE);
        int a = dim * 255 / 100;
        overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{withAlpha(t[0], a * 3 / 5), withAlpha(t[0], Math.min(255, a * 5 / 4))}));
    }

    private void applyTheme() {
        int[] t = Themes.T[themeIndex];

        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{t[0], t[1]}));
        applyOverlay();

        // songs page header
        SpannableString ss = new SpannableString("AXEL MUSIC");
        ss.setSpan(new ForegroundColorSpan(t[2]), 0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new ForegroundColorSpan(t[3]), 5, 10, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        headerTitle.setText(ss);
        headerTitle.setShadowLayer(16, 0, 0, withAlpha(t[2], 0xAA));
        headerSub.setTextColor(t[4]);

        searchBox.setBackground(roundRect(t[5], 23));
        searchBox.setTextColor(t[3]);
        searchBox.setHintTextColor(t[4]);
        emptyView.setTextColor(t[4]);

        // mini player
        GradientDrawable mc = roundRect(withAlpha(t[1], 0xEE), 22);
        mc.setStroke(dp(1), withAlpha(t[2], 0x66));
        miniCard.setBackground(mc);
        miniTitle.setTextColor(t[3]);
        miniArtist.setTextColor(t[4]);
        miniArt.setBackground(roundRect(t[5], 12));
        miniPlay.setBackground(circle(t[2]));
        miniPlay.setColorFilter(t[0]);
        miniNext.setColorFilter(t[3]);

        // player page
        pLabel.setTextColor(t[2]);
        pTitle.setTextColor(t[3]);
        pArtist.setTextColor(t[4]);
        curTime.setTextColor(t[4]);
        totalTime.setTextColor(t[4]);
        seek.setProgressTintList(ColorStateList.valueOf(t[2]));
        seek.setThumbTintList(ColorStateList.valueOf(t[2]));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(t[3], 0x44)));
        artRing.setBackground(circle(t[2]));
        artBig.setBackground(circle(t[1]));
        btnPlay.setBackground(circle(t[2]));
        btnPlay.setColorFilter(t[0]);
        btnPrev.setColorFilter(t[3]);
        btnNext.setColorFilter(t[3]);

        // settings page
        for (TextView x : primaryTexts) x.setTextColor(t[3]);
        for (TextView x : secondaryTexts) x.setTextColor(t[4]);
        for (View c : cards) c.setBackground(roundRect(t[5], 22));
        for (TextView x : pills) {
            x.setTextColor(t[2]);
            GradientDrawable g = roundRect(withAlpha(t[2], 0x26), 16);
            g.setStroke(dp(1), withAlpha(t[2], 0x66));
            x.setBackground(g);
        }
        dimSeek.setProgressTintList(ColorStateList.valueOf(t[2]));
        dimSeek.setThumbTintList(ColorStateList.valueOf(t[2]));
        dimSeek.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(t[3], 0x44)));
        buildThemeGrid();

        // nav
        GradientDrawable nb = roundRect(withAlpha(t[1], 0xF2), 30);
        nb.setStroke(dp(1), withAlpha(t[2], 0x55));
        navBar.setBackground(nb);
        updateNav();

        int flags = getWindow().getDecorView().getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (themeIndex == Themes.LIGHT_INDEX) flags |= light;
        else flags &= ~light;
        getWindow().getDecorView().setSystemUiVisibility(flags);

        updateUi();
    }

    // ================================================================ custom background

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
        if (req == REQ_BG) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final boolean ok = saveBackground(u);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (ok) {
                                loadBackgroundFromFile();
                                applyOverlay();
                            } else {
                                Toast.makeText(MainActivity.this, "تعذر تحميل الصورة", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            }).start();
        } else if (req == REQ_COVER && coverTarget != null) {
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

    private boolean saveBackground(Uri u) {
        try {
            Bitmap bmp = decodeUri(u, 1800);
            if (bmp == null) return false;
            FileOutputStream fo = new FileOutputStream(new File(getFilesDir(), "bg.jpg"));
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, fo);
            fo.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void loadBackgroundFromFile() {
        File f = new File(getFilesDir(), "bg.jpg");
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            bgImage.setImageBitmap(b);
        } else {
            bgImage.setImageDrawable(null);
        }
    }

    // ================================================================ state -> UI

    private static String fmt(int ms) {
        int total = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }

    private void updateUi() {
        if (root == null || pages[2] == null || dimSeek == null) return;
        int[] t = Themes.T[themeIndex];
        Song s = service != null ? service.currentSong() : null;
        boolean playing = service != null && service.isPlaying();

        String title = s == null ? "اختر أغنية" : s.title;
        String artist = s == null ? "AXEL MUSIC" : s.artist;
        pTitle.setText(title);
        pArtist.setText(artist);
        miniTitle.setText(title);
        miniArtist.setText(artist);
        miniCard.setVisibility(s != null ? View.VISIBLE : View.GONE);

        Bitmap art = service != null ? service.getArt() : null;
        setCover(artBig, art, t[2], dp(70));
        setCover(miniArt, art, t[2], dp(12));

        int pp = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        btnPlay.setImageResource(pp);
        btnPlay.setColorFilter(t[0]);
        miniPlay.setImageResource(pp);
        miniPlay.setColorFilter(t[0]);

        if (btnBoost != null) {
            String[] bl = {"متوقف", "عالي", "أعلى", "قوي جدًا", "أقصى"};
            int b = service != null ? service.getBoost() : 0;
            btnBoost.setText("🔊 تعزيز الصوت: " + bl[b]);
        }

        boolean shuf = service != null && service.isShuffle();
        int rep = service != null ? service.getRepeat() : 0;
        btnShuffle.setColorFilter(shuf ? t[2] : t[4]);
        btnRepeat.setImageResource(rep == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        btnRepeat.setColorFilter(rep != 0 ? t[2] : t[4]);

        if (playing) {
            if (!spin.isStarted()) spin.start();
            else if (spin.isPaused()) spin.resume();
        } else if (spin.isRunning()) {
            spin.pause();
        }

        adapter.notifyDataSetChanged();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
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
        int[] t = Themes.T[themeIndex];
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
        int[] t = Themes.T[themeIndex];
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(t[3]);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(4), 0, dp(4), dp(12));
        return tv;
    }

    private View sheetRow(int icon, String label, int color, View.OnClickListener l) {
        int[] t = Themes.T[themeIndex];
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(14), dp(10));
        r.setBackground(roundRect(withAlpha(t[3], 0x0D), 18));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(color);
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
        int[] t = Themes.T[themeIndex];
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
        final int[] t = Themes.T[themeIndex];
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
        final int[] t = Themes.T[themeIndex];
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

    private void updateTrashPill() {
        if (trashPill != null) trashPill.setText("فتح السلة (" + trashed.size() + ")");
    }

    private void trashSong(Song s) {
        trashTimes.put(Long.valueOf(s.id), Long.valueOf(System.currentTimeMillis()));
        saveTrash();
        allSongs.remove(s);
        trashed.add(s);
        if (service != null) service.removeSong(s.id);
        filter(searchBox.getText().toString());
        updateCount();
        updateTrashPill();
        updateUi();
        Toast.makeText(this, "اتنقلت لسلة المهملات وهتتحذف نهائياً بعد 12 يوم", Toast.LENGTH_LONG).show();
    }

    private void restoreSong(Song s) {
        trashTimes.remove(Long.valueOf(s.id));
        saveTrash();
        trashed.remove(s);
        allSongs.add(s);
        Collections.sort(allSongs, new java.util.Comparator<Song>() {
            @Override
            public int compare(Song a, Song b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        filter(searchBox.getText().toString());
        updateCount();
        updateTrashPill();
    }

    private void showTrash() {
        final int[] t = Themes.T[themeIndex];
        LinearLayout b = sheetBox();
        final Dialog d = newSheet(b);
        b.addView(sheetTitle("سلة المهملات"));

        if (trashed.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("السلة فاضية");
            e.setTextColor(t[4]);
            e.setGravity(Gravity.CENTER);
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            e.setPadding(0, dp(24), 0, dp(24));
            b.addView(e, new LinearLayout.LayoutParams(-1, -2));
            d.show();
            return;
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        long now = System.currentTimeMillis();
        for (final Song s : new ArrayList<Song>(trashed)) {
            Long tt = trashTimes.get(Long.valueOf(s.id));
            long left = tt == null ? 0 : TRASH_MS - (now - tt.longValue());
            long days = Math.max(0, (left + 86400000L - 1) / 86400000L);

            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(14), dp(8), dp(8), dp(8));
            r.setBackground(roundRect(withAlpha(t[3], 0x0D), 18));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView n = new TextView(this);
            n.setText(s.title);
            n.setTextColor(t[3]);
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            n.setSingleLine();
            n.setEllipsize(TextUtils.TruncateAt.END);
            TextView dd = new TextView(this);
            dd.setText(days == 0 ? "هتتحذف دلوقتي" : "باقي " + days + " يوم");
            dd.setTextColor(t[4]);
            dd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            col.addView(n, new LinearLayout.LayoutParams(-1, -2));
            col.addView(dd, new LinearLayout.LayoutParams(-1, -2));
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

            ImageView rs = iconButton(R.drawable.ic_restore);
            rs.setColorFilter(t[2]);
            rs.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    restoreSong(s);
                    d.dismiss();
                    showTrash();
                }
            });
            ImageView df = iconButton(R.drawable.ic_delete_forever);
            df.setColorFilter(0xFFFF6B6B);
            df.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    List<Song> one = new ArrayList<Song>();
                    one.add(s);
                    deletePermanently(one);
                }
            });
            r.addView(rs, new LinearLayout.LayoutParams(dp(44), dp(44)));
            r.addView(df, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            list.addView(r, lp);
        }
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list, new FrameLayout.LayoutParams(-1, -2));
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.5f);
        b.addView(sv, new LinearLayout.LayoutParams(-1, trashed.size() > 4 ? maxH : -2));

        TextView empty = dialogButton("إفراغ السلة نهائياً", false);
        empty.setTextColor(0xFFFF6B6B);
        empty.setBackground(roundRect(0x33FF5252, 16));
        empty.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                deletePermanently(new ArrayList<Song>(trashed));
            }
        });
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, -2);
        el.topMargin = dp(8);
        b.addView(empty, el);
        d.show();
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
        updateTrashPill();
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
                    .setShortLabel("AXEL MUSIC")
                    .setIcon(Icon.createWithAdaptiveBitmap(squareBitmap(src, 432)))
                    .setIntent(li)
                    .build();
            sm.requestPinShortcut(info, null);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر إنشاء الأيقونة", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean aliasEnabled(int i) {
        ComponentName cn = new ComponentName(this, "com.example.myapp.Icon" + i);
        int st = getPackageManager().getComponentEnabledSetting(cn);
        if (st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
        if (st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return false;
        return i == 0; // manifest default
    }

    private void applyLauncherIcon(int idx) {
        if (!prefs.getBoolean("icon_follow", true)) return;
        try {
            PackageManager pm = getPackageManager();
            if (!aliasEnabled(idx)) {
                pm.setComponentEnabledSetting(new ComponentName(this, "com.example.myapp.Icon" + idx),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            }
            for (int i = 0; i < Themes.T.length; i++) {
                if (i != idx && aliasEnabled(i)) {
                    pm.setComponentEnabledSetting(new ComponentName(this, "com.example.myapp.Icon" + i),
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void updateIconSwitch() {
        if (iconSwitch != null) {
            iconSwitch.setText(prefs.getBoolean("icon_follow", true)
                    ? "الأيقونة مع الثيم: شغال" : "الأيقونة مع الثيم: متوقف");
        }
    }

    // ================================================================ list adapter

    private class Row {
        LinearLayout outer, inner;
        ImageView cover, more;
        TextView title, artist, dur;

        Row() {
            Context c = MainActivity.this;
            outer = new LinearLayout(c);
            outer.setPadding(dp(12), dp(3), dp(12), dp(3));
            inner = new LinearLayout(c);
            inner.setGravity(Gravity.CENTER_VERTICAL);
            inner.setPadding(dp(8), dp(8), dp(4), dp(8));
            outer.addView(inner, new LinearLayout.LayoutParams(-1, -2));

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
            more.setImageResource(R.drawable.ic_more);
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
            int[] t = Themes.T[themeIndex];
            Song s = shown.get(p);
            Song cur = service != null ? service.currentSong() : null;
            boolean isCur = cur != null && cur.id == s.id;

            r.title.setText(s.title);
            r.artist.setText(s.artist);
            r.dur.setText(fmt((int) s.duration));
            r.title.setTextColor(isCur ? t[2] : t[3]);
            r.artist.setTextColor(t[4]);
            r.dur.setTextColor(isCur ? t[2] : t[4]);
            r.more.setColorFilter(t[4]);
            final Song fs = s;
            r.more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSongMenu(fs);
                }
            });

            r.inner.setBackground(roundRect(isCur ? withAlpha(t[2], 0x30) : Color.TRANSPARENT, 20));
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
}
