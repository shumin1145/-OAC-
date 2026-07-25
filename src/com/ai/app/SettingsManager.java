package com.oac.nazhiyazi.op;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 设置管理：多模型配置 + 当前激活模型 + 流式输出开关。
 * 使用 SharedPreferences 持久化，模型列表以 JSON 字符串形式存储。
 * 兼容 Android 2.3 (API 9+)
 *
 * 开源版说明：
 * - 不再提供任何内置默认模型或公共 API。
 * - 用户必须自行添加模型才能使用 AI 对话功能。
 */
public class SettingsManager {

    private static final String PREF_NAME = "oac_nazhiyazi_settings";
    private static final String KEY_MODELS = "models_json";
    private static final String KEY_ACTIVE_MODEL = "active_model_id";
    private static final String KEY_STREAM = "stream_output";
    private static final String KEY_LANGUAGE = "app_language";
    private static final String KEY_FIRST_LAUNCH = "first_launch";
    private static final String KEY_PRIVACY_ACCEPTED = "privacy_accepted";

    private static SettingsManager sInstance;

    private final SharedPreferences mPrefs;

    private SettingsManager(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized SettingsManager get(Context context) {
        if (sInstance == null) {
            sInstance = new SettingsManager(context);
        }
        return sInstance;
    }

    // ============ 模型列表 ============

    /**
     * 获取用户自定义模型列表。
     */
    public List<ModelConfig> getModels() {
        List<ModelConfig> list = new ArrayList<ModelConfig>();
        String json = mPrefs.getString(KEY_MODELS, "");
        if (json == null || json.length() == 0) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ModelConfig m = new ModelConfig();
                m.id = o.optString("id", String.valueOf(System.currentTimeMillis() + i));
                m.name = o.optString("name", "");
                m.apiUrl = o.optString("api_url", "");
                m.modelId = o.optString("model_id", "");
                m.apiKey = o.optString("api_key", "");
                m.temperature = o.optDouble("temperature", 0.7);
                m.maxTokens = o.optInt("max_tokens", 2048);
                m.systemPrompt = o.optString("system_prompt", "");
                m.thinkingMode = o.optInt("thinking_mode", ModelConfig.THINK_DEFAULT);
                if (m.thinkingMode < ModelConfig.THINK_DEFAULT || m.thinkingMode > ModelConfig.THINK_OFF) {
                    m.thinkingMode = ModelConfig.THINK_DEFAULT;
                }
                list.add(m);
            }
        } catch (Exception e) {
            // ignore parse error
        }
        return list;
    }

    /**
     * 开源版：所有可用模型即用户模型，无内置模型。
     */
    public List<ModelConfig> getAllModels() {
        return getModels();
    }

    public void saveModels(List<ModelConfig> models) {
        JSONArray arr = new JSONArray();
        if (models == null) {
            mPrefs.edit().putString(KEY_MODELS, "").commit();
            return;
        }
        for (ModelConfig m : models) {
            if (m == null) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("id", m.id == null ? "" : m.id);
                o.put("name", m.name == null ? "" : m.name);
                o.put("api_url", m.apiUrl == null ? "" : m.apiUrl);
                o.put("model_id", m.modelId == null ? "" : m.modelId);
                o.put("api_key", m.apiKey == null ? "" : m.apiKey);
                // 防止 NaN/Infinity 抛 JSONException
                double temp = m.temperature;
                if (Double.isNaN(temp) || Double.isInfinite(temp)) temp = 0.7;
                o.put("temperature", temp);
                o.put("max_tokens", m.maxTokens);
                o.put("system_prompt", m.systemPrompt == null ? "" : m.systemPrompt);
                o.put("thinking_mode", m.thinkingMode);
                arr.put(o);
            } catch (Exception e) {
                // ignore 单条失败
            }
        }
        try {
            mPrefs.edit().putString(KEY_MODELS, arr.toString()).commit();
        } catch (Throwable t) {
            // ignore
        }
    }

    public void addOrUpdateModel(ModelConfig model) {
        if (model == null) return;
        List<ModelConfig> list = getModels();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id != null && list.get(i).id.equals(model.id)) {
                list.set(i, model);
                found = true;
                break;
            }
        }
        if (!found) list.add(model);
        saveModels(list);
    }

    public void deleteModel(String modelId) {
        List<ModelConfig> list = getModels();
        Iterator<ModelConfig> it = list.iterator();
        while (it.hasNext()) {
            ModelConfig m = it.next();
            if (m.id != null && m.id.equals(modelId)) {
                it.remove();
            }
        }
        saveModels(list);
        if (modelId != null && modelId.equals(getActiveModelId())) {
            // 删除的是当前激活模型，清空选择
            setActiveModelId(null);
        }
    }

    public ModelConfig getModel(String modelId) {
        if (modelId == null) return null;
        List<ModelConfig> list = getModels();
        for (ModelConfig m : list) {
            if (modelId.equals(m.id)) return m;
        }
        return null;
    }

    // ============ 当前激活模型 ============

    public String getActiveModelId() {
        String id = mPrefs.getString(KEY_ACTIVE_MODEL, null);
        if (id == null || id.length() == 0) {
            // 未选择时返回 null
            return null;
        }
        // 如果已不存在的模型，返回 null
        if (getModel(id) == null) {
            setActiveModelId(null);
            return null;
        }
        return id;
    }

    public void setActiveModelId(String modelId) {
        mPrefs.edit().putString(KEY_ACTIVE_MODEL, modelId).commit();
    }

    /**
     * 开源版：没有内置模型兜底，无可用模型时返回 null。
     */
    public ModelConfig getActiveModel() {
        String id = getActiveModelId();
        if (id == null) return null;
        return getModel(id);
    }

    // ============ 流式输出 ============

    public boolean isStreamOutput() {
        return mPrefs.getBoolean(KEY_STREAM, true);
    }

    public void setStreamOutput(boolean stream) {
        mPrefs.edit().putBoolean(KEY_STREAM, stream).commit();
    }

    // ============ 语言与首次启动 ============

    /**
     * 是否首次启动。首次启动需要显示语言选择页。
     */
    public boolean isFirstLaunch() {
        return mPrefs.getBoolean(KEY_FIRST_LAUNCH, true);
    }

    public void setFirstLaunch(boolean first) {
        mPrefs.edit().putBoolean(KEY_FIRST_LAUNCH, first).commit();
    }

    /**
     * 获取当前语言，"zh" 或 "en"。默认中文，首次启动会强制选择。
     */
    public String getLanguage() {
        String lang = mPrefs.getString(KEY_LANGUAGE, null);
        if (lang == null) {
            // 未选择过，默认中文
            return "zh";
        }
        return lang;
    }

    public void setLanguage(String lang) {
        if (lang == null) lang = "zh";
        if (!"en".equals(lang) && !"zh".equals(lang)) {
            lang = "zh";
        }
        mPrefs.edit().putString(KEY_LANGUAGE, lang).commit();
    }

    public boolean isPrivacyAccepted() {
        return mPrefs.getBoolean(KEY_PRIVACY_ACCEPTED, false);
    }

    public void setPrivacyAccepted(boolean accepted) {
        mPrefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, accepted).commit();
    }
}
