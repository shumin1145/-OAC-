package com.oac.nazhiyazi.op;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 设置界面。兼容 Android 2.3。
 *
 * - 多模型配置（添加 / 编辑 / 删除 / 设为当前）
 * - 流式输出开关
 *
 * 健壮性策略：
 * - onCreate 全局 try-catch，避免任何初始化异常直接闪退
 * - 所有 findViewById 后做 null 检查
 * - dlg.getButton(AlertDialog.BUTTON_POSITIVE) 加 null 检查
 * - refreshModelList 中 inflate 失败的 try-catch
 * - 数字解析容错
 */
public class SettingsActivity extends Activity {

    private SettingsManager mSettings;
    private TextView mTvActiveModel;
    private CheckBox mCbStream;
    private LinearLayout mListContainer;
    private TextView mTvNoModels;
    private Button mBtnAddModelInline;
    private View mRowLanguage;
    private TextView mTvLanguageValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_settings);
        } catch (Throwable t) {
            toast(getString(R.string.msg_load_ui_failed, safeMsg(t)));
            finish();
            return;
        }

        try {
            mSettings = SettingsManager.get(this);

            mTvActiveModel = (TextView) findViewById(R.id.tv_active_model);
            mCbStream = (CheckBox) findViewById(R.id.cb_stream);
            mListContainer = (LinearLayout) findViewById(R.id.list_models_container);
            mTvNoModels = (TextView) findViewById(R.id.tv_no_models);
            mBtnAddModelInline = (Button) findViewById(R.id.btn_add_model_inline);
            mRowLanguage = findViewById(R.id.row_language);
            mTvLanguageValue = (TextView) findViewById(R.id.tv_language_value);

            if (mBtnAddModelInline != null) {
                mBtnAddModelInline.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showEditDialog(null);
                    }
                });
            }

            View btnBack = findViewById(R.id.btn_back);
            if (btnBack != null) {
                btnBack.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });
            }
            View btnAdd = findViewById(R.id.btn_add_model);
            if (btnAdd != null) {
                btnAdd.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showEditDialog(null);
                    }
                });
            }

            if (mCbStream != null) {
                try {
                    mCbStream.setChecked(mSettings.isStreamOutput());
                } catch (Throwable t) {
                    mCbStream.setChecked(true);
                }
                mCbStream.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            mSettings.setStreamOutput(mCbStream.isChecked());
                        } catch (Throwable t) {
                            // ignore
                        }
                    }
                });
            }

            if (mTvActiveModel != null) {
                mTvActiveModel.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 点击当前模型，跳转到选择对话框
                        showModelPicker();
                    }
                });
            }

            updateLanguageDisplay();
            if (mRowLanguage != null) {
                mRowLanguage.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showLanguagePicker();
                    }
                });
            }

            refreshModelList();
        } catch (Throwable t) {
            t.printStackTrace();
            toast(getString(R.string.msg_init_failed, safeMsg(t)));
        }
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        if (m == null || m.length() == 0) {
            m = t.getClass().getSimpleName();
        }
        if (m.length() > 100) m = m.substring(0, 100);
        return m;
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            // ignore
        }
    }

    private void refreshModelList() {
        if (mListContainer == null || mSettings == null) return;
        try {
            mListContainer.removeAllViews();
        } catch (Throwable t) {
            // ignore
        }
        List<ModelConfig> list = null;
        try {
            list = mSettings.getAllModels();  // 所有可用模型
        } catch (Throwable t) {
            list = null;
        }
        boolean empty = list == null || list.isEmpty();
        if (mTvNoModels != null) {
            mTvNoModels.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (mBtnAddModelInline != null) {
            mBtnAddModelInline.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (empty) {
            updateActiveDisplay();
            return;
        }

        String activeId = null;
        try {
            activeId = mSettings.getActiveModelId();
        } catch (Throwable t) {
            // ignore
        }
        if (activeId == null) {
            activeId = list.get(0).id;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (final ModelConfig m : list) {
            View item = null;
            try {
                item = inflater.inflate(R.layout.item_model, mListContainer, false);
            } catch (Throwable t) {
                // inflate 失败，跳过此项
                continue;
            }
            if (item == null) continue;

            try {
                TextView tvName = (TextView) item.findViewById(R.id.tv_model_name);
                if (tvName != null) tvName.setText(m.name == null ? "" : m.name);
                TextView tvId = (TextView) item.findViewById(R.id.tv_model_id);
                if (tvId != null) {
                    tvId.setText(m.modelId == null ? "" : m.modelId);
                }
                TextView tvUrl = (TextView) item.findViewById(R.id.tv_model_url);
                if (tvUrl != null) {
                    tvUrl.setText(m.apiUrl == null ? "" : m.apiUrl);
                }

                TextView tvActive = (TextView) item.findViewById(R.id.tv_model_active);
                if (tvActive != null) {
                    if (m.id != null && m.id.equals(activeId)) {
                        tvActive.setVisibility(View.VISIBLE);
                        tvActive.setText("[当前]");
                        tvActive.setTextColor(0xFF333333);
                    } else {
                        tvActive.setVisibility(View.GONE);
                    }
                }
            } catch (Throwable t) {
                // ignore TextView 错误
            }

            // 设为当前按钮：内置和自定义都可用
            bindModelButton(item, R.id.btn_model_activate, new Runnable() {
                @Override public void run() {
                    try {
                        mSettings.setActiveModelId(m.id);
                        refreshModelList();
                        updateActiveDisplay();
                        toast(getString(R.string.msg_switched_to, m.name == null ? "" : m.name));
                    } catch (Throwable t) {
                        toast(safeMsg(t));
                    }
                }
            });

            bindModelButton(item, R.id.btn_model_edit, new Runnable() {
                @Override public void run() {
                    showEditDialog(m);
                }
            });
            bindModelButton(item, R.id.btn_model_delete, new Runnable() {
                @Override public void run() {
                    try {
                        new AlertDialog.Builder(SettingsActivity.this)
                                .setTitle(R.string.app_name)
                                .setMessage(R.string.msg_confirm_delete)
                                .setPositiveButton(R.string.msg_yes, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        try {
                                            mSettings.deleteModel(m.id);
                                            refreshModelList();
                                            updateActiveDisplay();
                                        } catch (Throwable t) {
                                            toast(safeMsg(t));
                                        }
                                    }
                                })
                                .setNegativeButton(R.string.msg_no, null)
                                .show();
                    } catch (Throwable t) {
                        toast(safeMsg(t));
                    }
                }
            });

            try {
                mListContainer.addView(item);
            } catch (Throwable t) {
                // ignore
            }
        }

        updateActiveDisplay();
    }

    private void bindModelButton(View parent, int id, final Runnable action) {
        if (parent == null) return;
        View v = parent.findViewById(id);
        if (v == null) return;
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    action.run();
                } catch (Throwable t) {
                    toast(safeMsg(t));
                }
            }
        });
    }

    private void updateActiveDisplay() {
        if (mTvActiveModel == null) return;
        ModelConfig active = null;
        try {
            active = mSettings.getActiveModel();
        } catch (Throwable t) {
            // ignore
        }
        if (active == null) {
            mTvActiveModel.setText(R.string.set_no_active);
        } else {
            String name = active.name == null ? "" : active.name;
            String id = active.modelId == null ? "" : active.modelId;
            mTvActiveModel.setText(name + "  (" + id + ")");
        }
    }

    private void updateLanguageDisplay() {
        if (mTvLanguageValue == null || mSettings == null) return;
        try {
            String lang = mSettings.getLanguage();
            mTvLanguageValue.setText("en".equals(lang) ? R.string.lang_english : R.string.lang_chinese);
        } catch (Throwable t) {
            // ignore
        }
    }

    private void showLanguagePicker() {
        if (mSettings == null) return;
        try {
            final String[] langs = {"zh", "en"};
            String[] names = {
                    getString(R.string.lang_chinese),
                    getString(R.string.lang_english)
            };
            String current = mSettings.getLanguage();
            int checked = "en".equals(current) ? 1 : 0;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.lang_select_title)
                    .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                String lang = langs[which];
                                mSettings.setLanguage(lang);
                                applyLanguage(lang);
                                updateLanguageDisplay();
                                setResult(RESULT_OK);
                                dialog.dismiss();
                            } catch (Throwable t) {
                                toast(safeMsg(t));
                            }
                        }
                    })
                    .setNegativeButton(R.string.dlg_cancel, null)
                    .show();
        } catch (Throwable t) {
            toast(safeMsg(t));
        }
    }

    /**
     * 应用语言设置。兼容 Android 2.3。
     */
    private void applyLanguage(String lang) {
        try {
            Locale locale = "en".equals(lang) ? Locale.ENGLISH : Locale.CHINESE;
            Locale.setDefault(locale);
            Configuration config = getResources().getConfiguration();
            config.locale = locale;
            getResources().updateConfiguration(config, getResources().getDisplayMetrics());
        } catch (Throwable t) {
            // ignore
        }
    }

    private void showModelPicker() {
        if (mSettings == null) return;
        List<ModelConfig> list = null;
        try {
            list = mSettings.getAllModels();
        } catch (Throwable t) {
            // ignore
        }
        if (list == null || list.isEmpty()) {
            toast(getString(R.string.msg_add_model_first));
            return;
        }
        try {
            String[] names = new String[list.size()];
            int checked = 0;
            String activeId = mSettings.getActiveModelId();
            for (int i = 0; i < list.size(); i++) {
                names[i] = list.get(i).name == null ? "" : list.get(i).name;
                if (list.get(i).id != null && list.get(i).id.equals(activeId)) {
                    checked = i;
                }
            }
            final List<ModelConfig> finalList = list;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.set_active_model)
                    .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                mSettings.setActiveModelId(finalList.get(which).id);
                                refreshModelList();
                                dialog.dismiss();
                            } catch (Throwable t) {
                                toast(safeMsg(t));
                            }
                        }
                    })
                    .setNegativeButton(R.string.dlg_cancel, null)
                    .show();
        } catch (Throwable t) {
            toast(safeMsg(t));
        }
    }

    private void showEditDialog(final ModelConfig existing) {
        View body;
        try {
            body = LayoutInflater.from(this).inflate(R.layout.dialog_edit_model, null);
        } catch (Throwable t) {
            toast(getString(R.string.msg_load_dialog_failed, safeMsg(t)));
            return;
        }
        if (body == null) {
            toast(getString(R.string.msg_load_dialog_failed, ""));
            return;
        }

        final EditText etName = (EditText) body.findViewById(R.id.et_model_name);
        final EditText etUrl = (EditText) body.findViewById(R.id.et_api_url);
        final EditText etModelId = (EditText) body.findViewById(R.id.et_model_id);
        final EditText etKey = (EditText) body.findViewById(R.id.et_api_key);
        final EditText etTemp = (EditText) body.findViewById(R.id.et_temperature);
        final EditText etMax = (EditText) body.findViewById(R.id.et_max_tokens);
        final EditText etSys = (EditText) body.findViewById(R.id.et_system_prompt);
        final RadioGroup rgThinking = (RadioGroup) body.findViewById(R.id.rg_thinking_mode);
        final RadioButton rbThinkDefault = (RadioButton) body.findViewById(R.id.rb_think_default);
        final RadioButton rbThinkOn = (RadioButton) body.findViewById(R.id.rb_think_on);
        final RadioButton rbThinkOff = (RadioButton) body.findViewById(R.id.rb_think_off);
        final Button btnTest = (Button) body.findViewById(R.id.btn_test_connection);
        final TextView tvTestResult = (TextView) body.findViewById(R.id.tv_test_result);
        final AIRequest[] testReqHolder = new AIRequest[1];

        try {
            if (existing != null) {
                if (etName != null) etName.setText(existing.name);
                if (etUrl != null) etUrl.setText(existing.apiUrl);
                if (etModelId != null) etModelId.setText(existing.modelId);
                if (etKey != null) etKey.setText(existing.apiKey);
                if (etTemp != null) etTemp.setText(String.valueOf(existing.temperature));
                if (etMax != null) etMax.setText(String.valueOf(existing.maxTokens));
                if (etSys != null) etSys.setText(existing.systemPrompt);
                if (rgThinking != null) {
                    int mode = existing.thinkingMode;
                    if (mode == ModelConfig.THINK_ON) {
                        rgThinking.check(R.id.rb_think_on);
                    } else if (mode == ModelConfig.THINK_OFF) {
                        rgThinking.check(R.id.rb_think_off);
                    } else {
                        rgThinking.check(R.id.rb_think_default);
                    }
                }
            } else {
                if (etTemp != null) etTemp.setText("0.7");
                if (etMax != null) etMax.setText("2048");
                if (rgThinking != null) rgThinking.check(R.id.rb_think_default);
            }
        } catch (Throwable t) {
            // ignore setText 错误
        }

        final AlertDialog dlg;
        try {
            dlg = new AlertDialog.Builder(this)
                    .setTitle(existing == null ? R.string.set_add_model : R.string.set_edit_model)
                    .setView(body)
                    .setPositiveButton(R.string.dlg_ok, null)  // 后面覆盖，避免点完自动 dismiss
                    .setNegativeButton(R.string.dlg_cancel, null)
                    .create();
            dlg.show();

            // 平板/大屏上弹窗容易太窄，手动设置宽度为屏幕宽度的 90%（兼容 Android 2.3）
            try {
                Window window = dlg.getWindow();
                if (window != null) {
                    WindowManager.LayoutParams params = window.getAttributes();
                    params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
                    window.setAttributes(params);
                }
            } catch (Throwable t) {
                // ignore
            }
        } catch (Throwable t) {
            toast(getString(R.string.msg_show_dialog_failed, safeMsg(t)));
            return;
        }

        // 覆盖 OK 按钮，做校验（getButton 可能返回 null）
        Button btnOk = null;
        try {
            btnOk = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        } catch (Throwable t) {
            // ignore
        }
        if (btnOk != null) {
            btnOk.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        String name = etName != null ? etName.getText().toString().trim() : "";
                        String url = etUrl != null ? etUrl.getText().toString().trim() : "";
                        String modelId = etModelId != null ? etModelId.getText().toString().trim() : "";
                        String key = etKey != null ? etKey.getText().toString().trim() : "";
                        String tempStr = etTemp != null ? etTemp.getText().toString().trim() : "";
                        String maxStr = etMax != null ? etMax.getText().toString().trim() : "";
                        String sys = etSys != null ? etSys.getText().toString() : "";

                        if (name.length() == 0) {
                            toast(getString(R.string.msg_fill_name));
                            return;
                        }
                        if (url.length() == 0) {
                            toast(getString(R.string.msg_fill_api_url));
                            return;
                        }
                        if (modelId.length() == 0) {
                            toast(getString(R.string.msg_fill_model_id));
                            return;
                        }
                        if (key.length() == 0) {
                            toast(getString(R.string.msg_fill_api_key));
                            return;
                        }

                        double temp = 0.7;
                        if (tempStr.length() > 0) {
                            try { temp = Double.parseDouble(tempStr); }
                            catch (Exception e) {
                                toast(getString(R.string.msg_invalid_temperature));
                                return;
                            }
                        }
                        int maxTok = 2048;
                        if (maxStr.length() > 0) {
                            try { maxTok = Integer.parseInt(maxStr); }
                            catch (Exception e) {
                                toast(getString(R.string.msg_invalid_max_tokens));
                                return;
                            }
                        }

                        ModelConfig m = existing == null ? new ModelConfig() : existing;
                        m.name = name;
                        m.apiUrl = url;
                        m.modelId = modelId;
                        m.apiKey = key;
                        m.temperature = temp;
                        m.maxTokens = maxTok;
                        m.systemPrompt = sys;

                        // 读取思考模式
                        int thinkingMode = ModelConfig.THINK_DEFAULT;
                        try {
                            if (rgThinking != null) {
                                int checkedId = rgThinking.getCheckedRadioButtonId();
                                if (checkedId == R.id.rb_think_on) {
                                    thinkingMode = ModelConfig.THINK_ON;
                                } else if (checkedId == R.id.rb_think_off) {
                                    thinkingMode = ModelConfig.THINK_OFF;
                                } else {
                                    thinkingMode = ModelConfig.THINK_DEFAULT;
                                }
                            }
                        } catch (Throwable t) {
                            // ignore
                        }
                        m.thinkingMode = thinkingMode;

                        mSettings.addOrUpdateModel(m);

                        // 如果是第一个，自动设为当前
                        if (mSettings.getActiveModelId() == null) {
                            mSettings.setActiveModelId(m.id);
                        }

                        refreshModelList();
                        dlg.dismiss();
                    } catch (Throwable t) {
                        toast(getString(R.string.msg_save_failed, safeMsg(t)));
                    }
                }
            });
        }

        // 测试连接按钮
        if (btnTest != null) {
            btnTest.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    runTestConnection(etUrl, etModelId, etKey, etName, etSys, etTemp, etMax,
                            btnTest, tvTestResult, testReqHolder);
                }
            });
        }

        // 对话框关闭时取消测试请求
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (testReqHolder[0] != null) {
                    try { testReqHolder[0].cancel(); } catch (Throwable t) {}
                    testReqHolder[0] = null;
                }
            }
        });
    }

    /**
     * 执行测试连接：用当前填入的配置发起一次简单请求，显示详细结果。
     */
    private void runTestConnection(EditText etUrl, EditText etModelId, EditText etKey,
                                   EditText etName, EditText etSys, EditText etTemp, EditText etMax,
                                   final Button btnTest, final TextView tvTestResult,
                                   final AIRequest[] testReqHolder) {
        try {
            String url = etUrl != null ? etUrl.getText().toString().trim() : "";
            String modelId = etModelId != null ? etModelId.getText().toString().trim() : "";
            String key = etKey != null ? etKey.getText().toString().trim() : "";
            String name = etName != null ? etName.getText().toString().trim() : "";
            String sys = etSys != null ? etSys.getText().toString() : "";
            String tempStr = etTemp != null ? etTemp.getText().toString().trim() : "";
            String maxStr = etMax != null ? etMax.getText().toString().trim() : "";

            if (url.length() == 0 || modelId.length() == 0 || key.length() == 0) {
                if (tvTestResult != null) {
                    tvTestResult.setVisibility(View.VISIBLE);
                    tvTestResult.setText("[失败] 请先填写 API 地址、模型 ID、API Key");
                    tvTestResult.setTextColor(0xFFD32F2F);
                }
                return;
            }

            double temp = 0.7;
            try { if (tempStr.length() > 0) temp = Double.parseDouble(tempStr); }
            catch (Exception e) { temp = 0.7; }
            int maxTok = 2048;
            try { if (maxStr.length() > 0) maxTok = Integer.parseInt(maxStr); }
            catch (Exception e) { maxTok = 2048; }

            ModelConfig testModel = new ModelConfig();
            testModel.name = name.length() > 0 ? name : "测试";
            testModel.apiUrl = url;
            testModel.modelId = modelId;
            testModel.apiKey = key;
            testModel.temperature = temp;
            testModel.maxTokens = maxTok > 0 ? Math.min(maxTok, 100) : 100;  // 测试时限制 tokens
            testModel.systemPrompt = sys;

            // 取消之前的测试请求
            if (testReqHolder[0] != null) {
                try { testReqHolder[0].cancel(); } catch (Throwable t) {}
                testReqHolder[0] = null;
            }

            // 显示测试中
            if (tvTestResult != null) {
                tvTestResult.setVisibility(View.VISIBLE);
                tvTestResult.setText("测试中…\n→ POST " + url + "\n→ model: " + modelId);
                tvTestResult.setTextColor(0xFF666666);
            }
            if (btnTest != null) {
                btnTest.setEnabled(false);
                btnTest.setText(R.string.set_testing);
            }

            final AIRequest req = new AIRequest();
            testReqHolder[0] = req;
            final long startTime = System.currentTimeMillis();
            req.execute(testModel, new ArrayList<ChatMessage>(), "你好", false, new AIRequest.AICallback() {
                @Override
                public void onStart() {}

                @Override
                public void onDelta(String delta) {}

                @Override
                public void onReasoningDelta(String delta) {}

                @Override
                public void onComplete(String fullResponse, String fullReasoning) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (testReqHolder[0] == req) testReqHolder[0] = null;
                    if (btnTest != null) {
                        btnTest.setEnabled(true);
                        btnTest.setText(R.string.set_test);
                    }
                    if (tvTestResult != null) {
                        tvTestResult.setVisibility(View.VISIBLE);
                        String preview = fullResponse == null ? "" : fullResponse;
                        if (preview.length() > 300) preview = preview.substring(0, 300) + "…";
                        String reasoningInfo = "";
                        if (fullReasoning != null && fullReasoning.length() > 0) {
                            String rPreview = fullReasoning.length() > 100
                                    ? fullReasoning.substring(0, 100) + "…"
                                    : fullReasoning;
                            reasoningInfo = "\n思考: " + rPreview;
                        }
                        tvTestResult.setText("[成功] 测试成功 (" + elapsed + "ms)\n回复: "
                                + preview + reasoningInfo);
                        tvTestResult.setTextColor(0xFF388E3C);
                    }
                }

                @Override
                public void onError(String error) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (testReqHolder[0] == req) testReqHolder[0] = null;
                    if (btnTest != null) {
                        btnTest.setEnabled(true);
                        btnTest.setText(R.string.set_test);
                    }
                    if (tvTestResult != null) {
                        tvTestResult.setVisibility(View.VISIBLE);
                        tvTestResult.setText("[失败] 测试失败 (" + elapsed + "ms)\n"
                                + (error == null ? "unknown" : error));
                        tvTestResult.setTextColor(0xFFD32F2F);
                    }
                }
            });
        } catch (Throwable t) {
            if (btnTest != null) {
                btnTest.setEnabled(true);
                btnTest.setText(R.string.set_test);
            }
            if (tvTestResult != null) {
                tvTestResult.setVisibility(View.VISIBLE);
                tvTestResult.setText("[失败] 测试异常: " + safeMsg(t));
                tvTestResult.setTextColor(0xFFD32F2F);
            }
        }
    }
}
