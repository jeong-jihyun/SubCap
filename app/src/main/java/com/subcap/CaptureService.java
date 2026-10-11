package com.subcap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 화면을 일정 간격으로 캡처해 자막 글자를 읽고 기록하는 포그라운드 서비스.
 * 새 자막이 일정 시간 없으면 영상이 끝난 것으로 보고 마무리한 뒤 완료 알림을 띄운다.
 */
public class CaptureService extends Service {

    public static volatile boolean running = false;

    private static final String CH_RUN = "subcap";
    private static final String CH_DONE = "subcap_done";
    private static final long IDLE_FINISH_MS = 30_000L;

    private static final Pattern TIME_ONLY = Pattern.compile("^[0-9: /]+$");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]");

    private final Handler ui = new Handler(Looper.getMainLooper());

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private TextRecognizer recognizer;

    private int topPct = 35;
    private int bottomPct = 55;
    private long intervalMs = 1000L;
    private long startTime;
    private boolean stopping;
    private boolean hasAny;
    private long lastChange;

    // 같은 자막이 여러 번 읽히므로 하나로 합치기 위한 임시 보관
    private String pendingText = "";
    private String pendingNorm = "";
    private long pendingTime;

    @Override
    public void onCreate() {
        super.onCreate();
        recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_RUN, "자막 기록", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CH_RUN)
                .setContentTitle("자막 기록 중")
                .setContentText("영상을 재생해 두세요")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }

        int code = intent.getIntExtra("code", 0);
        Intent data = readData(intent);
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        topPct = cfg.getInt("top", 35);
        bottomPct = cfg.getInt("bottom", 55);
        intervalMs = cfg.getInt("sec", 1) * 1000L;

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (!stopping) stopSelf();
            }
        }, null);

        int w;
        int h;
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            w = wm.getCurrentWindowMetrics().getBounds().width();
            h = wm.getCurrentWindowMetrics().getBounds().height();
        } else {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        int dpi = getResources().getDisplayMetrics().densityDpi;

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay(
                "subcap", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);

        thread = new HandlerThread("subcap-capture");
        thread.start();
        handler = new Handler(thread.getLooper());
        running = true;
        startTime = SystemClock.elapsedRealtime();
        lastChange = startTime;
        handler.postDelayed(tick, 500);
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent readData(Intent i) {
        if (Build.VERSION.SDK_INT >= 33) {
            return i.getParcelableExtra("data", Intent.class);
        }
        return i.getParcelableExtra("data");
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || handler == null) return;
            try {
                capture();
            } catch (Exception ignored) {
            }
            if (hasAny && SystemClock.elapsedRealtime() - lastChange > IDLE_FINISH_MS) {
                ui.post(CaptureService.this::finishAndNotify);
                return;
            }
            handler.postDelayed(this, intervalMs);
        }
    };

    /** 화면 한 장을 가져와 자막 영역만 잘라 글자 인식에 넘긴다. */
    private void capture() {
        Image img = (reader == null) ? null : reader.acquireLatestImage();
        if (img == null) return;
        try {
            Image.Plane plane = img.getPlanes()[0];
            int bmpW = plane.getRowStride() / plane.getPixelStride();
            int ih = img.getHeight();
            Bitmap full = Bitmap.createBitmap(bmpW, ih, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(plane.getBuffer());

            int y0 = clamp(ih * topPct / 100, 0, ih - 2);
            int y1 = clamp(ih * bottomPct / 100, y0 + 1, ih);
            Bitmap crop = Bitmap.createBitmap(full, 0, y0, Math.min(img.getWidth(), full.getWidth()), y1 - y0);
            if (crop != full) full.recycle();
            recognize(crop);
        } finally {
            img.close();
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void recognize(final Bitmap crop) {
        InputImage image = InputImage.fromBitmap(crop, 0);
        recognizer.process(image)
                .addOnSuccessListener(res -> {
                    StringBuilder sb = new StringBuilder();
                    for (Text.TextBlock block : res.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            String t = line.getText().trim();
                            if (t.length() >= 2 && !TIME_ONLY.matcher(t).matches()) {
                                if (sb.length() > 0) sb.append(' ');
                                sb.append(t);
                            }
                        }
                    }
                    crop.recycle();
                    handleText(sb.toString());
                })
                .addOnFailureListener(e -> crop.recycle());
    }

    private static Set<String> bigrams(String s) {
        Set<String> r = new HashSet<>();
        if (s.length() < 2) {
            r.add(s);
            return r;
        }
        for (int i = 0; i < s.length() - 1; i++) r.add(s.substring(i, i + 2));
        return r;
    }

    /** 두 글이 같은 자막(글자 인식 오차 포함)인지 판단한다. */
    private static boolean similar(String a, String b) {
        Set<String> x = bigrams(a);
        Set<String> y = bigrams(b);
        int denom = Math.max(x.size(), y.size());
        if (denom == 0) return false;
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        return (double) inter.size() / denom >= 0.6;
    }

    private void handleText(String raw) {
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("last", raw).apply();
        String n = NON_ALNUM.matcher(raw).replaceAll("");
        if (n.length() < 3) return;
        long now = SystemClock.elapsedRealtime() - startTime;

        if (pendingNorm.isEmpty()) {
            pendingText = raw; pendingNorm = n; pendingTime = now;
            touch();
            return;
        }
        if (similar(pendingNorm, n)) {
            if (n.length() > pendingNorm.length()) {   // 더 온전히 읽힌 쪽을 채택
                pendingText = raw;
                pendingNorm = n;
            }
            return;
        }
        flush();
        pendingText = raw; pendingNorm = n; pendingTime = now;
        touch();
    }

    private void touch() {
        hasAny = true;
        lastChange = SystemClock.elapsedRealtime();
    }

    private void flush() {
        if (pendingText.isEmpty()) return;
        int s = (int) (pendingTime / 1000);
        Store.append(this, String.format(Locale.US, "[%02d:%02d] %s", s / 60, s % 60, pendingText));
        pendingText = "";
        pendingNorm = "";
    }

    /** 새 자막이 30초 동안 없으면 영상이 끝난 것으로 보고 마무리 + 알림. */
    private void finishAndNotify() {
        if (stopping) return;
        flush();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, "기록 완료", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_DONE)
                .setContentTitle("자막 기록 완료")
                .setContentText("눌러서 앱을 열고 'Claude로 보내기'를 누르세요")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(2, n);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        flush();
        try { if (display != null) display.release(); } catch (Exception ignored) { }
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        try { if (recognizer != null) recognizer.close(); } catch (Exception ignored) { }
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
