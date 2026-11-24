package com.clonsapps.sample.simple;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Menulis files/marker.properties: bukti identitas, data terpisah, dan fitur yang berjalan. */
final class Marker {
    private Marker() {
    }

    static String write(Context c, Map<String, String> extra) {
        SharedPreferences p = c.getSharedPreferences("marker", Context.MODE_PRIVATE);
        int launches = p.getInt("launches", 0) + 1;
        p.edit().putInt("launches", launches).commit();
        ApplicationInfo ai = c.getApplicationInfo();
        StringBuilder sb = new StringBuilder();
        sb.append("package=").append(c.getPackageName()).append('\n');
        sb.append("uid=").append(Process.myUid()).append('\n');
        sb.append("dataDir=").append(ai.dataDir).append('\n');
        sb.append("launches=").append(launches).append('\n');
        sb.append("dexFiles=").append(countDex(ai.sourceDir)).append('\n');
        sb.append("splits=").append(ai.splitNames == null ? "" : String.join(",", ai.splitNames)).append('\n');
        for (Map.Entry<String, String> e : extra.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        String text = sb.toString();
        File dir = c.getFilesDir();
        File tmp = new File(dir, "marker.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        } catch (Exception e) {
            return "error=" + e;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(new File(dir, "marker.properties"));
        return text;
    }

    private static int countDex(String apk) {
        int n = 0;
        try (ZipFile z = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    n++;
                }
            }
        } catch (Exception e) {
            return -1;
        }
        return n;
    }
}
