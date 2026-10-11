package com.subcap;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 읽은 자막을 앱 전용 폴더의 텍스트 파일에 쌓아 두는 저장소. */
final class Store {

    private Store() { }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "subtitles.txt");
    }

    static synchronized void append(Context c, String line) {
        try (FileOutputStream out = new FileOutputStream(file(c), true)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    static synchronized String read(Context c) {
        File f = file(c);
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static synchronized void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
    }
}
