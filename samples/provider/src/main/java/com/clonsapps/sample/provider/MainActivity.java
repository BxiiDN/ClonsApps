package com.clonsapps.sample.provider;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    static final String LEGACY_AUTHORITY = "com.clonsapps.sample.provider.notes";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Map<String, String> extra = new LinkedHashMap<>();
        // Pola yang benar: authority dibentuk dari package name saat runtime.
        Uri uri = Uri.parse("content://" + getPackageName() + ".notes/rows");
        try (Cursor cur = getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                extra.put("provider", cur.getString(0) + "|" + cur.getString(1) + "|" + cur.getInt(2));
            } else {
                extra.put("provider", "empty");
            }
        } catch (RuntimeException e) {
            extra.put("provider", "error:" + e.getClass().getSimpleName());
        }
        // Pola yang rawan: string authority ditulis langsung (dideteksi ClonApps saat Periksa).
        Log.d("SampleProvider", LEGACY_AUTHORITY);
        TextView tv = new TextView(this);
        tv.setPadding(48, 48, 48, 48);
        tv.setText(getString(R.string.app_name) + "\n\n" + Marker.write(this, extra));
        setContentView(tv);
    }
}
