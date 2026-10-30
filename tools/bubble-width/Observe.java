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
    private static final String LONG = "A wider message leaves more room for each line. Long replies should be easier to read without changing the text size. Both sides share the same maximum width.";

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
            seedHost();
            Intent intent = getTargetContext().getPackageManager().getLaunchIntentForPackage("dev.multiprompt.companion");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getTargetContext().startActivity(intent);
            waitFor("Inbox", SystemClock.elapsedRealtime() + 30000);
            if (node("All") != null) click("All");
            click("cl-width-proof");
            waitFor("Thanks", SystemClock.elapsedRealtime() + 30000);
            SystemClock.sleep(1000);
            File evidence = new File(getTargetContext().getExternalFilesDir(null), "bubble-width");
            if (!evidence.mkdirs() && !evidence.isDirectory()) throw new AssertionError("Evidence directory missing");
            Bitmap bitmap = getUiAutomation().takeScreenshot();
            if (bitmap == null || bitmap.getWidth() != 1080) throw new AssertionError("1080px screenshot required");
            try (FileOutputStream output = new FileOutputStream(new File(evidence, "chat.png"))) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new AssertionError("Screenshot failed");
            }
            JSONObject rows = new JSONObject();
            for (String text : new String[]{"Agent: " + LONG, "User: " + LONG, "Ready", "Thanks"}) {
                AccessibilityNodeInfo item = node(text);
                if (item == null) throw new AssertionError("Message missing: " + text);
                Rect rect = new Rect();
                item.getBoundsInScreen(rect);
                rows.put(text, new JSONObject().put("left", rect.left).put("right", rect.right)
                    .put("top", rect.top).put("bottom", rect.bottom));
            }
            JSONObject measurements = new JSONObject().put("screenWidth", bitmap.getWidth())
                .put("density", getTargetContext().getResources().getDisplayMetrics().density).put("text", rows);
            try (FileOutputStream output = new FileOutputStream(new File(evidence, "nodes.json"))) {
                output.write(measurements.toString(2).getBytes("UTF-8"));
            }
            result.putString("result", "WIDTH_SCREEN_CAPTURED");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
