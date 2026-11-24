package com.clonsapps.sample.split;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    static {
        System.loadLibrary("sample");
    }

    static native String nativeHello();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("native", nativeHello());
        extra.put("density", String.valueOf(getResources().getDisplayMetrics().densityDpi));
        TextView tv = new TextView(this);
        tv.setPadding(48, 48, 48, 48);
        tv.setText(getString(R.string.app_name) + "\n\n" + Marker.write(this, extra));
        setContentView(tv);
    }
}
