package dev.multiprompt.verification;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;

public class Observe extends Instrumentation {
    private Bundle arguments;
    private File evidence;
    private static final String LATEST = "LONG_CHAT_LATEST_1156";

    @Override public void onCreate(Bundle values) {
        super.onCreate(values);
        arguments = values;
        start();
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String value) {
        if (node == null) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (node.isVisibleToUser() && !bounds.isEmpty()
                && (value.contentEquals(node.getText() == null ? "" : node.getText())
                || value.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(node.getChild(i), value);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo node(String value) {
        getUiAutomation().clearCache();
        return find(getUiAutomation().getRootInActiveWindow(), value);
    }

    private void waitFor(String value, long deadline) {
        while (node(value) == null) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError("VISIBLE_REPLY_MISSING: " + value);
            SystemClock.sleep(20);
        }
    }

    private void click(String value) {
        waitFor(value, SystemClock.elapsedRealtime() + 30000);
        AccessibilityNodeInfo target = node(value);
        while (target != null && !target.isClickable()) target = target.getParent();
        if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Click failed: " + value);
    }

    private void report(JSONObject row) {
        Bundle result = new Bundle();
        result.putString("sample", row.toString());
        sendStatus(0, result);
    }

    private int screenshot(String name) throws Exception {
        AccessibilityNodeInfo latest = node(LATEST);
        if (latest == null) throw new AssertionError("Visible screenshot text missing");
        Rect bounds = new Rect();
        latest.getBoundsInScreen(bounds);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Screenshot missing");
        int ink = 0;
        for (int y = Math.max(0, bounds.top); y < Math.min(bitmap.getHeight(), bounds.bottom); y++) {
            for (int x = Math.max(0, bounds.left); x < Math.min(bitmap.getWidth(), bounds.right); x++) {
                int pixel = bitmap.getPixel(x, y);
                if (((pixel >> 16) & 255) > 128 && ((pixel >> 8) & 255) > 128 && (pixel & 255) > 128) ink++;
            }
        }
        try (FileOutputStream output = new FileOutputStream(new File(evidence, name + ".png"))) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new AssertionError("Screenshot failed");
        }
        bitmap.recycle();
        if (ink < 30) throw new AssertionError("Visible text painted blank");
        return ink;
    }

    private void open(String stage) throws Exception {
        waitFor("cl-long-chat", SystemClock.elapsedRealtime() + 30000);
        AccessibilityNodeInfo target = node("cl-long-chat");
        while (target != null && !target.isClickable()) target = target.getParent();
        if (target == null) throw new AssertionError("Session target missing");
        long started = SystemClock.elapsedRealtime();
        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Session click failed");
        long tapped = SystemClock.elapsedRealtime() - started;
        waitFor(LATEST, started + 2000);
        long visible = SystemClock.elapsedRealtime() - started;
        int ink = screenshot(stage);
        long elapsed = SystemClock.elapsedRealtime() - started;
        report(new JSONObject().put("stage", stage).put("visibleMs", elapsed).put("latest", LATEST)
            .put("inkPixels", ink).put("tapMs", tapped).put("nodeMs", visible).put("captureMs", elapsed - visible));
        if (elapsed > 2000) throw new AssertionError("Visible reply too late: " + elapsed);
    }

    private void seedHost() throws Exception {
        Context context = getTargetContext();
        byte[] privateKey = Files.readAllBytes(new File("/data/local/tmp/long-chat-key").toPath());
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, (SecretKey) store.getKey("multiprompt_companion_secrets_v1", null));
        byte[] encrypted = cipher.doFinal(privateKey);
        byte[] packed = new byte[cipher.getIV().length + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, packed, 0, cipher.getIV().length);
        System.arraycopy(encrypted, 0, packed, cipher.getIV().length, encrypted.length);
        if (!context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE).edit()
                .putString("long-chat-key", Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) throw new AssertionError("Key seed failed");
        JSONObject host = new JSONObject().put("id", "long-chat-host").put("label", "Verify")
            .put("hostname", "10.0.2.2").put("port", 2222).put("username", "verifyssh")
            .put("keySecretId", "long-chat-key").put("hostKeys", new JSONArray().put(arguments.getString("hostKey")));
        if (!context.getSharedPreferences("hosts", Context.MODE_PRIVATE).edit()
                .putString("profiles_json", new JSONArray().put(host).toString()).commit()) throw new AssertionError("Host seed failed");
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!getTargetContext().getPackageName().equals("dev.multiprompt.companion")) throw new AssertionError("Published package required");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "long-chat");
            if (!evidence.mkdirs() && !evidence.isDirectory()) throw new AssertionError("Evidence directory missing");
            String phase = arguments.getString("phase", "initial");
            if (!phase.equals("reopen")) seedHost();
            Intent intent = getTargetContext().getPackageManager().getLaunchIntentForPackage("dev.multiprompt.companion");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getTargetContext().startActivity(intent);
            waitFor("Inbox", SystemClock.elapsedRealtime() + 30000);
            if (node("All") != null) click("All");
            open(phase);
            if (phase.equals("initial")) {
                click("Back");
                click("verify-session");
                waitFor("VERIFY_READY_528", SystemClock.elapsedRealtime() + 30000);
                click("Back");
                open("switch-back");
                long started = SystemClock.elapsedRealtime();
                int samples = 0;
                do {
                    if (node(LATEST) == null) throw new AssertionError("Reply disappeared during soak");
                    long elapsed = SystemClock.elapsedRealtime() - started;
                    if (samples % 30 == 0) screenshot("soak-" + samples);
                    report(new JSONObject().put("stage", "soak").put("elapsedMs", elapsed).put("visible", true));
                    samples++;
                    SystemClock.sleep(1000);
                } while (SystemClock.elapsedRealtime() - started < 1800000);
                if (node(LATEST) == null) throw new AssertionError("Reply disappeared after thirty minutes");
                screenshot("sustained");
                report(new JSONObject().put("stage", "sustained").put("elapsedMs", SystemClock.elapsedRealtime() - started).put("samples", samples));
            }
            result.putString("result", "LONG_CHAT_UI_OK");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
