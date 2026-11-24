package com.clonsapps.sample.multidex;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("multidex", com.clonsapps.sample.multidex.gen.Big69.m999() == 69999 ? "ok" : "fail");
        TextView tv = new TextView(this);
        tv.setPadding(48, 48, 48, 48);
        tv.setText(getString(R.string.app_name) + "\n\n" + Marker.write(this, extra));
        setContentView(tv);
    }
}
