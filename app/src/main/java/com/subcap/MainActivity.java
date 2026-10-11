package com.subcap;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** 화면: 시작/중지, 기록 보기, Claude로 보내기, 기록 지우기. */
public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 100;

    private EditText editTop;
    private EditText editBottom;
    private EditText editSec;
    private TextView txtInfo;
    private TextView txtLog;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        editTop = findViewById(R.id.editTop);
        editBottom = findViewById(R.id.editBottom);
        editSec = findViewById(R.id.editSec);
        txtInfo = findViewById(R.id.txtInfo);
        txtLog = findViewById(R.id.txtLog);

        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        editTop.setText(String.valueOf(cfg.getInt("top", 35)));
        editBottom.setText(String.valueOf(cfg.getInt("bottom", 55)));
        editSec.setText(String.valueOf(cfg.getInt("sec", 1)));

        findViewById(R.id.btnStart).setOnClickListener(v -> start());
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            refresh();
        });
        findViewById(R.id.btnShare).setOnClickListener(v -> share());
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            Store.clear(this);
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    private void refresh() {
        SharedPreferences p = getSharedPreferences("cfg", MODE_PRIVATE);
        txtInfo.setText((CaptureService.running ? "● 기록 중" : "○ 멈춤")
                + "\n마지막으로 읽은 글자: " + p.getString("last", ""));
        String t = Store.read(this);
        txtLog.setText(t.isEmpty() ? "아직 기록이 없어요" : t);
    }

    private static int toInt(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private void start() {
        int top = Math.max(0, Math.min(98, toInt(editTop, 35)));
        int bottom = Math.max(top + 1, Math.min(100, toInt(editBottom, 55)));
        int sec = Math.max(1, Math.min(10, toInt(editSec, 1)));
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putInt("top", top).putInt("bottom", bottom).putInt("sec", sec).apply();

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent i = new Intent(this, CaptureService.class);
            i.putExtra("code", resultCode);
            i.putExtra("data", data);
            startForegroundService(i);
            Toast.makeText(this, "시작! 이제 EBS 영상을 재생하세요", Toast.LENGTH_LONG).show();
        }
    }

    private void share() {
        String t = Store.read(this).trim();
        if (t.isEmpty()) {
            Toast.makeText(this, "기록이 없어요", Toast.LENGTH_SHORT).show();
            return;
        }
        String prompt = "아래는 강연 영상의 자막을 화면에서 읽어 기록한 글이에요(글자 인식 오류가 조금 있을 수 있어요). "
                + "전문용어 없이 쉽게, 한 줄 요약 / 강연의 흐름 / 핵심 주장 5가지 / 자동화·수익화에 써먹을 아이디어 3가지로 정리해 주세요.\n\n";
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, prompt + t);
        startActivity(Intent.createChooser(i, "Claude로 보내기"));
    }
}
