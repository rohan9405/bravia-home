package io.github.rohan9405.ambient;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Waits for Google's screensaver and, while it runs, now and then shows a news story over it, full screen: the photo,
 * the topic and publisher, the headline, the date and a QR code to read more, for a while, then Google's wallpapers
 * again. The story never takes focus, so the remote still goes to the screensaver, and it goes the moment the
 * screensaver stops. Stories are fetched every hour, in the background.
 */
public class AmbientService extends Service {
    static final String ACTION_REFRESH = "io.github.rohan9405.ambient.REFRESH";
    private static final String TAG = "AmbientNews";
    private static final long REFRESH_MS = 60L * 60 * 1000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Loads a story's photo; its own thread, so a story never waits for a fetch that is under way. */
    private final ExecutorService art = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private Prefs prefs;
    private Stories stories;
    private WindowManager wm;
    private View slide;
    private boolean dreaming;

    private final Runnable showNext = this::showNext;
    private final Runnable hideSlide = () -> hide(true);
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = String.valueOf(i.getAction());
            if (Intent.ACTION_DREAMING_STARTED.equals(a)) {
                dreaming = true;
                if (System.currentTimeMillis() - stories.updated() > REFRESH_MS) refresh();
                boolean preview = prefs.previewOnce();
                if (preview) prefs.set("preview", false);
                schedule(preview ? 4000 : gap());
            } else {
                dreaming = false;
                main.removeCallbacks(showNext);
                main.removeCallbacks(hideSlide);
                hide(false);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        stories = new Stories(this);
        wm = getSystemService(WindowManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("ambient", getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, "ambient").setContentTitle(getString(R.string.notification))
                .setSmallIcon(R.drawable.icon).build());
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_DREAMING_STARTED);
        f.addAction(Intent.ACTION_DREAMING_STOPPED);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(events, f);
        main.post(refreshTick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!new Prefs(this).enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) refresh();
        return START_STICKY;
    }

    /** Fetches in the background, while the screen is on (not in standby, when no one would see the stories). */
    private void refresh() {
        if (!getSystemService(android.os.PowerManager.class).isInteractive()) return;
        io.execute(() -> {
            long t = System.currentTimeMillis();
            stories.refresh(prefs);
            Log.i(TAG, "refreshed: " + stories.count(null) + " stories (Google News " + stories.count(News.GOOGLE)
                    + ", TheNewsAPI " + stories.count(News.THENEWS) + ") in " + (System.currentTimeMillis() - t) + " ms");
        });
    }

    private long gap() {
        int[] g = Prefs.GAPS[prefs.gap()];
        return 1000L * (g[0] + random.nextInt(g[1] - g[0] + 1));
    }

    private void schedule(long ms) {
        main.removeCallbacks(showNext);
        main.postDelayed(showNext, ms);
    }

    private void showNext() {
        if (!dreaming) return;
        Stories.Ready r = slide == null && Settings.canDrawOverlays(this) ? stories.next(prefs) : null;
        if (r == null) {
            schedule(gap());
            return;
        }
        art.execute(() -> {
            Bitmap photo = BitmapFactory.decodeFile(r.photo.getPath());
            Bitmap code = qr(r.story.link, px(150));
            main.post(() -> show(r.story, photo, code));
        });
    }

    private void show(News.Story s, Bitmap photo, Bitmap code) {
        if (!dreaming || photo == null || slide != null) {
            if (dreaming) schedule(gap());
            return;
        }
        slide = buildSlide(s, photo, code);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        try {
            slide.setAlpha(0f);
            wm.addView(slide, lp);
            slide.animate().alpha(1f).setDuration(900).start();
            Log.i(TAG, "showing: " + s.source + " · " + s.title);
            main.postDelayed(hideSlide, prefs.length() * 1000L);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not show the story: " + e);
            slide = null;
            schedule(gap());
        }
    }

    /** Fades the story out (or, when the screensaver stops, removes it at once) and waits for the next. */
    private void hide(boolean fade) {
        View v = slide;
        if (v == null) return;
        slide = null;
        if (!fade) {
            v.animate().cancel();
            remove(v);
            return;
        }
        v.animate().alpha(0f).setDuration(700).withEndAction(() -> remove(v)).start();
        schedule(gap());
    }

    private void remove(View v) {
        try {
            wm.removeView(v);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
    }

    /** The full-screen story: the photo, a shade where the text sits, the text bottom-left, the QR code bottom-right. */
    private View buildSlide(News.Story s, Bitmap photo, Bitmap code) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(photo);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new LayerDrawable(new Drawable[] {
                new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xE6060810, 0x59060810, 0x00060810, 0x00060810}),
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0x8C060810, 0x00060810, 0x00060810})}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView kicker = label(11, 0xD9FFFFFF, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        kicker.setText((topicName(s.topic) + "   ·   " + s.source).toUpperCase(Locale.ROOT));
        kicker.setLetterSpacing(0.16f);
        text.addView(kicker);
        TextView title = label(24, Color.WHITE, Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setText(s.title);
        title.setMaxLines(3);
        title.setLineSpacing(0, 1.08f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = px(8);
        tl.bottomMargin = px(8);
        text.addView(title, tl);
        TextView date = label(12, 0xBFFFFFFF, Typeface.create("sans-serif", Typeface.NORMAL));
        date.setText(when(s.published));
        text.addView(date);
        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(px(520), -2, Gravity.BOTTOM | Gravity.START);
        textLp.leftMargin = px(48);
        textLp.bottomMargin = px(44);
        root.addView(text, textLp);

        if (code != null) {
            LinearLayout qr = new LinearLayout(this);
            qr.setOrientation(LinearLayout.VERTICAL);
            qr.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView q = new ImageView(this);
            q.setImageBitmap(code);
            q.setClipToOutline(true);
            GradientDrawable white = new GradientDrawable();
            white.setColor(Color.WHITE);
            white.setCornerRadius(px(8));
            q.setBackground(white);
            qr.addView(q, new LinearLayout.LayoutParams(px(64), px(64)));
            TextView scan = label(10, 0xBFFFFFFF, Typeface.create("sans-serif", Typeface.NORMAL));
            scan.setText(R.string.scan_to_read);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.topMargin = px(6);
            qr.addView(scan, sl);
            FrameLayout.LayoutParams qrLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            qrLp.rightMargin = px(44);
            qrLp.bottomMargin = px(44);
            root.addView(qr, qrLp);
        }
        return root;
    }

    private TextView label(int sp, int colour, Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(face);
        t.setShadowLayer(px(8), 0, px(1), 0x99000000);
        return t;
    }

    private String topicName(String topic) {
        return getString(SettingsActivity.topicLabel(topic, true));
    }

    /** "Sat, 31 Oct · 12:39 AM · 10 h ago"; empty when the date is unknown. */
    private String when(long ms) {
        if (ms <= 0) return "";
        long min = Math.max(0, (System.currentTimeMillis() - ms) / 60000);
        String ago = min < 60 ? getString(R.string.ago_minutes, (int) min) : getString(R.string.ago_hours, (int) (min / 60));
        return new SimpleDateFormat("EEE, d MMM · h:mm a", Locale.getDefault()).format(new Date(ms)) + "  ·  " + ago;
    }

    /** The article's address as a QR code, black on white with a narrow margin; null if it cannot be made. */
    static Bitmap qr(String url, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? 0xFF0B0D12 : 0xFFFFFFFF;
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (WriterException | IllegalArgumentException e) {
            return null;
        }
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(events);
        main.removeCallbacksAndMessages(null);
        hide(false);
        io.shutdownNow();
        art.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
