package com.oac.nazhiyazi.op;

/**
 * 一段对话。包含 id、标题、创建时间、最后活跃时间。
 */
public class Conversation {
    public long id;
    public String title;
    public long createdAt;
    public long updatedAt;
    public String preview;   // 最后一条消息预览

    public Conversation() {
    }

    public Conversation(String title) {
        this.title = title;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
        this.preview = "";
    }
}
