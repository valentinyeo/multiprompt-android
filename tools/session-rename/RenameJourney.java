package dev.multiprompt.proof;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import org.json.JSONArray;
import org.json.JSONObject;

public class RenameJourney extends Instrumentation {
    private String phase;
    private Context context;
    private File output;
    private static final String NAME = "Renamed session";

    @Override public void onCreate(Bundle args) {
        phase = args.getString("phase", "rename");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            context = getTargetContext();
            output = new File(context.getExternalFilesDir(null), "rename-proof");
            output.mkdirs();
            if (phase.equals("seed")) {
                seed();
                result.putString("stream", "RENAME_SEED_OK\n");
            } else {
                context.startActivity(new Intent().setClassName(context.getPackageName(), "dev.multiprompt.companion.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                waitNode("Work");
                action("Work", AccessibilityNodeInfo.ACTION_CLICK);
                if (phase.equals("rename")) {
                    AccessibilityNodeInfo row = waitEither("Original session", "Old agent title");
                    shot("before");
                    perform(row, AccessibilityNodeInfo.ACTION_LONG_CLICK);
                    action("Rename", AccessibilityNodeInfo.ACTION_CLICK);
                    AccessibilityNodeInfo field = waitEditable();
                    Bundle text = new Bundle();
                    text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, NAME);
                    require(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text), "Name field rejected input");
                    long started = SystemClock.elapsedRealtime();
                    action("Rename", AccessibilityNodeInfo.ACTION_CLICK);
                    long deadline = started + 2000;
                    while ((find(NAME) == null || find("Rename session") != null) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
                    require(find(NAME) != null && find("Rename session") == null, "STALE_INBOX_AFTER_RENAME");
                    long elapsed = SystemClock.elapsedRealtime() - started;
                    require(elapsed <= 2000, "RENAME_EXCEEDED_TWO_SECONDS");
                    shot("inbox");
                    action(NAME, AccessibilityNodeInfo.ACTION_CLICK);
                    waitNode("Session actions");
                    waitNode(NAME);
                    shot("reader");
                    Files.writeString(new File(output, "timing.json").toPath(), "{\"inbox_milliseconds\":" + elapsed + "}");
                } else {
                    waitNode(NAME);
                    require(find("Old agent title") == null, "Stale title returned after restart");
                    shot("reopened");
                    JSONObject pending = new JSONObject(context.getSharedPreferences("account_layout", 0).getString("layout::rename-proof", ""));
                    JSONObject layout = pending.getJSONObject("edited");
                    require(layout.getJSONArray("tabs").getJSONObject(0).getString("name").equals(NAME), "Rename was not persisted");
                    Files.writeString(new File(output, "rename-layout.json").toPath(), layout.toString());
                }
                result.putString("stream", "RENAME_" + phase.toUpperCase() + "_OK\n");
            }
            finish(-1, result);
        } catch (Throwable failure) {
            try { shot("failure"); } catch (Throwable ignored) {}
            result.putString("stream", "RENAME_FAILED: " + failure + "\n");
            finish(0, result);
        }
    }

    private void seed() throws Exception {
        JSONObject login = new JSONObject().put("account_id", "rename-proof").put("device_id", "rename-proof")
            .put("refresh_token", "x".repeat(43)).put("access_token", "x".repeat(43)).put("expires_at", Long.MAX_VALUE);
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder("multiprompt_companion_secrets_v1", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, generator.generateKey());
        byte[] encrypted = cipher.doFinal(login.toString().getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[cipher.getIV().length + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, packed, 0, cipher.getIV().length);
        System.arraycopy(encrypted, 0, packed, cipher.getIV().length, encrypted.length);
        save("encrypted_secrets", "account_device_v1", Base64.encodeToString(packed, Base64.NO_WRAP));
        JSONObject host = new JSONObject().put("id", "rename-host").put("label", "Hetzner").put("hostname", "127.0.0.1")
            .put("port", 1).put("username", "valentin").put("keySecretId", "missing-fixture-key");
        save("hosts", "profiles_json", new JSONArray().put(host).toString());
        JSONObject tab = new JSONObject().put("kind", "tmux").put("name", "Original session").put("tmux_session", "work-main")
            .put("tmux_host", 0).put("workspace", "Work").put("favorite", false);
        JSONObject layout = new JSONObject().put("schema", 1).put("revision", 1)
            .put("workspaces", new JSONArray().put(new JSONObject().put("name", "Work")).put(new JSONObject().put("name", "Notes")))
            .put("tabs", new JSONArray().put(tab));
        save("account_layout", "layout::rename-proof", new JSONObject().put("base", layout).put("edited", layout).toString());
        save("account_layout", "host::0", "rename-host");
        JSONObject session = new JSONObject().put("name", "work-main").put("windows", 1).put("title", "Old agent title")
            .put("windowName", "Old agent title").put("paneCommand", "codex").put("lastActivityEpochSeconds", System.currentTimeMillis() / 1000);
        save("session_cache", "host::rename-host", new JSONArray().put(session).toString());
        context.getSharedPreferences("updates", 0).edit().putLong("last_check_ms", System.currentTimeMillis()).commit();
    }

    private void save(String preferences, String key, String value) {
        require(context.getSharedPreferences(preferences, 0).edit().putString(key, value).commit(), "Fixture save failed");
    }

    private AccessibilityNodeInfo search(AccessibilityNodeInfo node, String text, boolean editable) {
        if (node == null) return null;
        if (editable ? node.isEditable() : text.contentEquals(node.getText() == null ? "" : node.getText()) || text.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = search(node.getChild(i), text, editable);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo find(String text) {
        return search(getUiAutomation().getRootInActiveWindow(), text, false);
    }

    private AccessibilityNodeInfo waitEither(String first, String second) {
        long deadline = SystemClock.elapsedRealtime() + 60000;
        do {
            AccessibilityNodeInfo node = find(first);
            if (node == null) node = find(second);
            if (node != null) return node;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Missing visible text: " + first);
    }

    private AccessibilityNodeInfo waitNode(String text) { return waitEither(text, text); }

    private AccessibilityNodeInfo waitEditable() {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            AccessibilityNodeInfo node = search(getUiAutomation().getRootInActiveWindow(), "", true);
            if (node != null) return node;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Missing editable name field");
    }

    private void perform(AccessibilityNodeInfo node, int action) {
        while (node != null) {
            if (node.performAction(action)) return;
            node = node.getParent();
        }
        throw new AssertionError("Visible action unavailable");
    }

    private void action(String text, int action) { perform(waitNode(text), action); }

    private void shot(String name) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(output, name + ".png"))) {
            require(getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, stream), "Screenshot failed");
        }
    }

    private void require(boolean condition, String error) { if (!condition) throw new AssertionError(error); }
}
