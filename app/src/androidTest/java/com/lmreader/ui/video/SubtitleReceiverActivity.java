package com.lmreader.ui.video;

import android.app.Activity;
import android.os.Bundle;
import android.net.Uri;
import android.content.Intent;
import java.io.*;
import org.json.JSONObject;

/** Runs under the test APK UID, proving that the target app granted read access. */
public class SubtitleReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        JSONObject result = new JSONObject();
        try {
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) >= 0) { if (bytes.size() + n > 16000000) throw new IOException("too large"); bytes.write(buffer, 0, n); }
            }
            result.put("ok", true).put("contents", bytes.toString("UTF-8"));
        } catch (Exception failure) { try { result.put("ok", false).put("error", failure.toString()); } catch (Exception ignored) {} }
        try {
            File root = new File(getFilesDir(), "reliability-documents").getCanonicalFile();
            File output = new File(root, getIntent().getStringExtra("result")).getCanonicalFile();
            if (!output.getPath().startsWith(root.getPath() + File.separator)) throw new IOException("invalid result path");
            output.getParentFile().mkdirs();
            try (FileOutputStream stream = new FileOutputStream(output)) { stream.write(result.toString().getBytes("UTF-8")); }
        } catch (Exception ignored) {}
        finish();
    }
}
