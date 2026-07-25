package com.oac.nazhiyazi.op;

/**
 * 流式响应中一个 delta 的解析结果。
 * - content: 正常回复内容
 * - reasoning: 思考内容（DeepSeek reasoning_content / OpenAI o1 reasoning）
 *
 * 用于兼容支持思考链的模型。普通模型 reasoning 为空。
 */
public class StreamDelta {
    public String content = "";
    public String reasoning = "";

    public boolean hasContent() {
        return content != null && content.length() > 0;
    }

    public boolean hasReasoning() {
        return reasoning != null && reasoning.length() > 0;
    }

    public boolean isEmpty() {
        return !hasContent() && !hasReasoning();
    }
}
