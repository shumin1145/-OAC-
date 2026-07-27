package com.oac.nazhiyazi.op;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;

/** 使用 Android Keystore 管理 API 密钥。 */
public final class SecureKeyStore {
    private static final String PREF_NAME = "oac_encrypted_api_keys";
    private static final String KEY_PREFIX = "api_key_";
    private static final String LEGACY_PREF_NAME = "oac_nazhiyazi_settings";
    private static final String LEGACY_MODELS_KEY = "models_json";

    private final SharedPreferences prefs;

    private SecureKeyStore(Context context) throws GeneralSecurityException, IOException {
        MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        prefs = EncryptedSharedPreferences.create(
                context.getApplicationContext(), PREF_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    public static SecureKeyStore create(Context context) {
        try {
            SecureKeyStore store = new SecureKeyStore(context);
            store.migrateLegacyKeys(context.getApplicationContext());
            return store;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法初始化 Android Keystore 安全存储", e);
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化加密配置存储", e);
        }
    }

    public void saveApiKey(String key) {
        saveApiKey("default", key);
    }

    public void saveApiKey(String modelId, String key) {
        if (modelId == null || modelId.length() == 0) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
        if (key == null) key = "";
        if (!prefs.edit().putString(KEY_PREFIX + modelId, key).commit()) {
            throw new IllegalStateException("API 密钥保存失败");
        }
    }

    public String getApiKey() {
        return getApiKey("default");
    }

    public String getApiKey(String modelId) {
        if (modelId == null || modelId.length() == 0) return "";
        return prefs.getString(KEY_PREFIX + modelId, "");
    }

    public void deleteApiKey(String modelId) {
        if (modelId != null && modelId.length() > 0) {
            prefs.edit().remove(KEY_PREFIX + modelId).commit();
        }
    }

    private void migrateLegacyKeys(Context context) {
        SharedPreferences legacy = context.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE);
        String json = legacy.getString(LEGACY_MODELS_KEY, "");
        if (json == null || json.length() == 0) return;
        try {
            JSONArray models = new JSONArray(json);
            boolean changed = false;
            for (int i = 0; i < models.length(); i++) {
                JSONObject model = models.getJSONObject(i);
                String id = model.optString("id", "");
                String key = model.optString("api_key", "");
                if (id.length() > 0 && key.length() > 0) {
                    saveApiKey(id, key);
                }
                if (model.has("api_key")) {
                    model.remove("api_key");
                    changed = true;
                }
            }
            if (changed && !legacy.edit().putString(LEGACY_MODELS_KEY, models.toString()).commit()) {
                throw new IllegalStateException("旧明文 API 密钥删除失败");
            }
        } catch (Exception e) {
            throw new IllegalStateException("API 密钥迁移失败，已停止使用旧明文配置", e);
        }
    }
}
