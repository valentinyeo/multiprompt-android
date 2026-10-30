package dev.multiprompt.verification;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.json.JSONObject;
import org.json.JSONArray;

public class Seed extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            if (!context.getPackageName().equals("dev.multiprompt.companion")
                && !context.getPackageName().equals("dev.multiprompt.companion.m3")) {
                throw new IllegalStateException("Published package required");
            }
            String mode = arguments.getString("mode", "account");
            if (!mode.equals("account") && !mode.equals("favorites-account")) {
                if (!(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))) {
                    throw new IllegalStateException("Emulator required");
                }
                if (mode.equals("favorites-seed")) seedFavorites(context, result);
                else if (mode.equals("favorites-snapshot")) snapshotFavorites(context, result);
                else throw new IllegalStateException("Unknown fixture mode");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (mode.equals("favorites-account")) {
                if (!(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
                    || !context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE).getAll().isEmpty()
                    || !context.getSharedPreferences("account_layout", Context.MODE_PRIVATE).getAll().isEmpty()
                    || !context.getSharedPreferences("hosts", Context.MODE_PRIVATE).getAll().isEmpty()) {
                    throw new IllegalStateException("Empty emulator fixture required");
                }
            }
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("multiprompt_companion_secrets_v1",
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
            SecretKey key = generator.generateKey();
            JSONObject session = new JSONObject()
                .put("account_id", "isolated-release-fixture")
                .put("device_id", "github-emulator")
                .put("access_token", "offline-fixture")
                .put("refresh_token", "offline-fixture")
                .put("expires_at", System.currentTimeMillis() + 3600000);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] ciphertext = cipher.doFinal(session.toString().getBytes("UTF-8"));
            byte[] packed = new byte[cipher.getIV().length + ciphertext.length];
            System.arraycopy(cipher.getIV(), 0, packed, 0, cipher.getIV().length);
            System.arraycopy(ciphertext, 0, packed, cipher.getIV().length, ciphertext.length);
            if (!context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE).edit()
                .putString("account_device_v1", Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
                throw new IllegalStateException("Fixture session was not persisted");
            }
            result.putString("result", "ISOLATED_RELEASE_SESSION_SEEDED");
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            if (arguments != null && arguments.getString("mode", "account").startsWith("favorites-")) {
                result.putString("error", error instanceof IllegalStateException ? error.getMessage() : error.getClass().getSimpleName());
            } else {
                result.putString("error", error.toString());
            }
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void seedFavorites(Context context, Bundle result) throws Exception {
        SharedPreferences secrets = context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE);
        if (!secrets.contains("account_device_v1")) throw new IllegalStateException("Fixture account required");
        JSONArray hosts = new JSONArray(context.getSharedPreferences("hosts", Context.MODE_PRIVATE)
            .getString("profiles_json", "[]"));
        if (hosts.length() != 1) throw new IllegalStateException("Exactly one fixture host required");
        JSONObject host = hosts.getJSONObject(0);
        if (!host.getString("label").equals("Verify") || !host.getString("hostname").equals("10.0.2.2")
            || host.getInt("port") != 2222 || !host.getString("username").equals("verifyssh")
            || host.getString("id").isEmpty() || host.getString("keySecretId").isEmpty()
            || !host.getString("hostKeyType").equals("ssh-ed25519")
            || !host.getString("hostKeyFingerprint").startsWith("SHA256:")) {
            throw new IllegalStateException("Unexpected fixture host");
        }
        SharedPreferences preferences = context.getSharedPreferences("account_layout", Context.MODE_PRIVATE);
        if (!preferences.getAll().isEmpty()) throw new IllegalStateException("Layout store must be empty");
        JSONArray tabs = new JSONArray();
        for (String name : new String[] {"verify-session", "verify-keep"}) {
            tabs.put(new JSONObject().put("kind", "tmux").put("name", name).put("tmux_session", name)
                .put("tmux_host", 1).put("workspace", "Fixture").put("manual", false).put("favorite", true));
        }
        JSONObject layout = new JSONObject().put("schema", 1).put("revision", 0)
            .put("workspaces", new JSONArray().put(new JSONObject().put("name", "Fixture")))
            .put("tabs", tabs).put("settings", new JSONObject().put("favorites", true).put("all", true));
        JSONObject pending = new JSONObject().put("base", layout).put("edited", layout);
        if (!preferences.edit().putString("host::1", host.getString("id"))
            .putString("layout::isolated-release-fixture", pending.toString()).commit()) {
            throw new IllegalStateException("Fixture layout was not persisted");
        }
        result.putString("result", "ISOLATED_FAVORITES_SEEDED");
    }

    private void snapshotFavorites(Context context, Bundle result) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences("account_layout", Context.MODE_PRIVATE);
        String raw = preferences.getString("layout::isolated-release-fixture", null);
        if (raw == null) throw new IllegalStateException("Fixture layout missing");
        JSONArray tabs = new JSONObject(raw).getJSONObject("edited").getJSONArray("tabs");
        if (tabs.length() != 2) throw new IllegalStateException("Expected two fixture tabs");
        JSONObject states = new JSONObject();
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject tab = tabs.getJSONObject(i);
            String name = tab.getString("tmux_session");
            if (!(name.equals("verify-session") || name.equals("verify-keep")) || states.has(name)
                || tab.getInt("tmux_host") != 1 || !tab.getString("kind").equals("tmux")
                || !(tab.get("favorite") instanceof Boolean)) {
                throw new IllegalStateException("Invalid fixture tab");
            }
            states.put(name, tab.getBoolean("favorite"));
        }
        if (!states.has("verify-session") || !states.has("verify-keep")) throw new IllegalStateException("Fixture tabs missing");
        result.putString("favorites", states.toString());
        result.putString("result", "ISOLATED_FAVORITES_SNAPSHOT");
    }

}
