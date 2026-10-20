package com.limelight;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Local license/source notice screen. No network or user data is read. */
public final class MicBridgeNotices extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        text.setTextIsSelectable(true);
        try (InputStream in = getAssets().open("micbridge-notices.txt");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            text.setText(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) {
            text.setText("License notices unavailable. See the corresponding source release asset.");
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
    }
}
