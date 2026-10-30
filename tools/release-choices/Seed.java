package dev.multiprompt.verification;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.json.JSONObject;

public class Seed extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
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
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
