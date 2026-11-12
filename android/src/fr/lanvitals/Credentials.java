package fr.lanvitals;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.ProviderException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Credentials {
    private static final String ALIAS = "lan-vitals-password";
    private static final String DATA = "password_data";
    private static final String IV = "password_iv";
    private final SharedPreferences preferences;

    Credentials(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    String load() {
        String data = preferences.getString(DATA, null);
        String iv = preferences.getString(IV, null);
        if (data == null || iv == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException | ProviderException error) {
            clear();
            return null;
        }
    }

    boolean save(String password, String fingerprint) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] data = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            preferences.edit().putString(DATA, Base64.encodeToString(data, Base64.NO_WRAP))
                    .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("tls_pin", fingerprint).apply();
            return true;
        } catch (GeneralSecurityException | IOException | ProviderException error) {
            clear();
            preferences.edit().putString("tls_pin", fingerprint).apply();
            return false;
        }
    }

    void clear() {
        preferences.edit().remove(DATA).remove(IV).apply();
    }

    private SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey(ALIAS, null);
        if (key != null || !create) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
