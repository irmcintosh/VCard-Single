package com.mackseoul.vcardnative.platform.sharing;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

// Runs as the test APK's separate UID, without the target APK's Kotlin runtime.
public final class ContactReceiverActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri uri = Objects.requireNonNull(getIntent().getParcelableExtra(Intent.EXTRA_STREAM));
        String action = Objects.requireNonNull(getIntent().getStringExtra("resultAction"));
        String content = null;
        boolean readDenied = false;
        try (InputStream input = Objects.requireNonNull(getContentResolver().openInputStream(uri))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            content = new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (SecurityException | IOException error) {
            readDenied = true;
        }
        boolean writeDenied = false;
        try (OutputStream output = Objects.requireNonNull(getContentResolver().openOutputStream(uri))) {
            output.write(0);
        } catch (SecurityException | IOException error) {
            writeDenied = true;
        }
        sendBroadcast(new Intent(action).setPackage("com.mackseoul.vcardnative")
            .putExtra("content", content).putExtra("readDenied", readDenied).putExtra("writeDenied", writeDenied));
        finish();
    }
}
