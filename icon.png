package com.oac.nazhiyazi.op;

/**
 * AI 模型配置项。一个模型 = 一组 API地址 + 模型ID + Key + 采样参数。
 * 兼容 OpenAI / DeepSeek / Moonshot (Kimi) / OpenRouter 等任何 OpenAI 兼容协议。
 */
public class ModelConfig {
    public String id;            // 内部唯一ID（用时间戳生成）
    public String name;          // 显示名称，如 DeepSeek / Kimi
    public String apiUrl;        // 完整 chat completions URL
    public String modelId;       // 模型 ID，如 deepseek-chat
    public String apiKey;        // API Key
    public double temperature;   // 0.0 ~ 2.0
    public int maxTokens;        // 0 表示不限制
    public String systemPrompt;  // 系统提示词，可为空

    /**
     * 思考模式：
     * 0 = 默认（API 返回 reasoning_content 就显示，不额外发参数）
     * 1 = 开启思考（请求带 include_reasoning=true，有思考内容就显示）
     * 2 = 不思考（即使 API 返回思考内容也不显示）
     */
    public int thinkingMode;

    public static final int THINK_DEFAULT = 0;
    public static final int THINK_ON = 1;
    public static final int THINK_OFF = 2;

    public ModelConfig() {
        this.id = String.valueOf(System.currentTimeMillis());
        this.name = "";
        this.apiUrl = "";
        this.modelId = "";
        this.apiKey = "";
        this.temperature = 0.7;
        this.maxTokens = 2048;
        this.systemPrompt = "";
        this.thinkingMode = THINK_DEFAULT;
    }

    public boolean shouldShowReasoning() {
        return thinkingMode != THINK_OFF;
    }

    public boolean forceRequestReasoning() {
        return thinkingMode == THINK_ON;
    }
}
