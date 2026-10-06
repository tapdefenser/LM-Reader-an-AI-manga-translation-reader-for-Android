package com.lmreader.reliability;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.DocumentsContract;

/** Grants only the generated fixture tree to the instrumentation target. */
public class FaultGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String authority = getIntent().getStringExtra("authority");
        if (authority == null) authority = FaultDocumentsProvider.AUTHORITY;
        grantUriPermission(getIntent().getStringExtra("target"),
            DocumentsContract.buildTreeDocumentUri(authority, getIntent().getStringExtra("tree")),
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        finish();
    }
}
