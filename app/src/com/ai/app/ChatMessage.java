package com.oac.nazhiyazi.op;

/**
 * 一条聊天消息。role: user / assistant / system
 *
 * - content: 显示的主体内容
 * - reasoning: 助手回复的思考过程（DeepSeek reasoning_content），仅 assistant 角色可能有
 */
public class ChatMessage {
    public long id;            // 数据库自增ID
    public long conversationId;
    public String role;        // user / assistant / system
    public String content;
    public String reasoning;   // 思考过程（可空）
    public long createdAt;     // 创建时间戳

    public ChatMessage() {
    }

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
        this.createdAt = System.currentTimeMillis();
    }

    public boolean isUser() {
        return "user".equals(role);
    }

    public boolean isAssistant() {
        return "assistant".equals(role);
    }

    public boolean isSystem() {
        return "system".equals(role);
    }

    public boolean hasReasoning() {
        return reasoning != null && reasoning.length() > 0;
    }
}
