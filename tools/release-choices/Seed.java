package dev.multiprompt.verification;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.util.Arrays;
import java.security.KeyStore;
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
                if (mode.equals("settings-seed") || mode.equals("settings-snapshot")) {
                    requireSettingsFixture(context);
                    if (mode.equals("settings-seed")) seedSettings(context, result);
                    else snapshotSettings(context, result);
                } else if (mode.equals("host-seed")) seedHost(context, result);
                else if (mode.equals("favorites-seed")) seedFavorites(context, result);
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
            if (arguments != null && arguments.getString("mode", "account").startsWith("settings-")) {
                result.putString("error", "Settings fixture rejected");
            } else if (arguments != null && arguments.getString("mode", "account").startsWith("favorites-")) {
                result.putString("error", error instanceof IllegalStateException ? error.getMessage() : error.getClass().getSimpleName());
            } else {
                result.putString("error", error.toString());
            }
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private static final String[] SETTINGS = {"frequent", "frequent_first", "favorites", "all"};
    private static final String LAYOUT_KEY = "layout::isolated-release-fixture";

    private void requireSettingsFixture(Context context) throws Exception {
        if (!"10.0.2.2:8788".equals(Settings.Global.getString(context.getContentResolver(), "http_proxy"))
            || !context.getSharedPreferences("hosts", Context.MODE_PRIVATE).getAll().isEmpty()) {
            throw new IllegalStateException();
        }
        String encoded = context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE)
            .getString("account_device_v1", null);
        byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
        if (packed.length <= 12) throw new IllegalStateException();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey("multiprompt_companion_secrets_v1", null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(packed, 0, 12)));
        JSONObject session = new JSONObject(new String(cipher.doFinal(Arrays.copyOfRange(packed, 12, packed.length)), "UTF-8"));
        Object expires = session.get("expires_at");
        if (!"isolated-release-fixture".equals(session.get("account_id"))
            || !"github-emulator".equals(session.get("device_id"))
            || !"offline-fixture".equals(session.get("access_token"))
            || !"offline-fixture".equals(session.get("refresh_token"))
            || !(expires instanceof Number) || ((Number) expires).longValue() <= System.currentTimeMillis()) {
            throw new IllegalStateException();
        }
    }

    private void seedSettings(Context context, Bundle result) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("account_layout", Context.MODE_PRIVATE);
        if (!prefs.getAll().isEmpty()) throw new IllegalStateException();
        JSONObject flags = new JSONObject();
        for (String key : SETTINGS) flags.put(key, true);
        JSONObject layout = new JSONObject().put("schema", 1).put("revision", 0)
            .put("workspaces", new JSONArray()).put("tabs", new JSONArray()).put("settings", flags);
        if (!prefs.edit().putString(LAYOUT_KEY, new JSONObject().put("base", layout).put("edited", layout).toString()).commit()) {
            throw new IllegalStateException();
        }
        result.putString("result", "ISOLATED_SETTINGS_SEEDED");
    }

    private void snapshotSettings(Context context, Bundle result) throws Exception {
        SharedPreferences layouts = context.getSharedPreferences("account_layout", Context.MODE_PRIVATE);
        if (layouts.getAll().size() > (layouts.contains(LAYOUT_KEY) ? 1 : 0)) throw new IllegalStateException();
        String raw = layouts.getString(LAYOUT_KEY, null);
        JSONObject flags = null;
        if (raw != null) {
            JSONObject pending = new JSONObject(raw);
            if (pending.length() != 2) throw new IllegalStateException();
            for (String part : new String[] {"base", "edited"}) {
                JSONObject layout = pending.getJSONObject(part);
                if (layout.length() != 5 || layout.getInt("schema") != 1 || layout.getLong("revision") != 0
                    || layout.getJSONArray("workspaces").length() != 0 || layout.getJSONArray("tabs").length() != 0) {
                    throw new IllegalStateException();
                }
                JSONObject values = layout.getJSONObject("settings");
                if (values.length() != SETTINGS.length) throw new IllegalStateException();
                for (String key : SETTINGS) if (!(values.get(key) instanceof Boolean)) throw new IllegalStateException();
                if (part.equals("edited")) flags = values;
            }
        }
        SharedPreferences local = context.getSharedPreferences("session_read_state", Context.MODE_PRIVATE);
        if (local.contains("sunlight_mode")) throw new IllegalStateException();
        JSONObject values = new JSONObject()
            .put("newest_sessions_at_bottom", local.getBoolean("newest_sessions_at_bottom", true))
            .put("all_split_on_right", local.getBoolean("all_split_on_right", true))
            .put("app_theme", local.getString("app_theme", "SYSTEM"))
            .put("reader_default_font_scale", local.getFloat("reader_default_font_scale", 1f));
        String theme = values.getString("app_theme");
        if (!theme.equals("SYSTEM") && !theme.equals("DARK") && !theme.equals("SUNLIGHT")) throw new IllegalStateException();
        JSONObject present = new JSONObject();
        for (String key : new String[] {"newest_sessions_at_bottom", "all_split_on_right", "app_theme", "reader_default_font_scale"}) {
            present.put(key, local.contains(key));
        }
        result.putString("state", new JSONObject().put("layout_present", raw != null)
            .put("settings", flags == null ? JSONObject.NULL : flags).put("local", values).put("present", present).toString());
        result.putString("result", "ISOLATED_SETTINGS_SNAPSHOT");
    }

    private void seedHost(Context context, Bundle result) throws Exception {
        SharedPreferences secrets = context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE);
        if (!secrets.contains("account_device_v1")) throw new IllegalStateException("Fixture account required");
        SharedPreferences hosts = context.getSharedPreferences("hosts", Context.MODE_PRIVATE);
        if (!hosts.getAll().isEmpty()) throw new IllegalStateException("Empty host fixture required");
        String label = arguments.getString("host_label");
        String hostname = arguments.getString("ssh_host");
        String username = arguments.getString("ssh_user");
        int port = Integer.parseInt(arguments.getString("ssh_port"));
        if (label == null || hostname == null || username == null || arguments.getString("ssh_private_key") == null) throw new IllegalStateException("Host fixture missing");
        String id = "release-fixture-host";
        String secretId = "host_key_" + id;
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey("multiprompt_companion_secrets_v1", null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(Base64.decode(arguments.getString("ssh_private_key"), Base64.DEFAULT));
        byte[] packed = new byte[cipher.getIV().length + ciphertext.length];
        System.arraycopy(cipher.getIV(), 0, packed, 0, cipher.getIV().length);
        System.arraycopy(ciphertext, 0, packed, cipher.getIV().length, ciphertext.length);
        if (!secrets.edit().putString(secretId, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) throw new IllegalStateException("Host key not saved");
        JSONObject profile = new JSONObject().put("id", id).put("label", label).put("hostname", hostname)
            .put("port", port).put("username", username).put("keySecretId", secretId);
        if (!hosts.edit().putString("profiles_json", new JSONArray().put(profile).toString()).commit()) throw new IllegalStateException("Host not saved");
        result.putString("result", "ISOLATED_HOST_SEEDED");
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
