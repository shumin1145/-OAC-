package com.oac.nazhiyazi.op;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 数据库：保存对话列表和消息记录。
 * 兼容 Android 2.3 (API 9+)
 *
 * v2: 增加 messages.reasoning 列保存思考过程
 */
public class DBHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "oac_nazhiyazi.db";
    private static final int DB_VERSION = 2;

    private static final String T_CONVERSATIONS = "conversations";
    private static final String T_MESSAGES = "messages";

    public DBHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_CONVERSATIONS + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL, " +
                "preview TEXT)");
        db.execSQL("CREATE TABLE " + T_MESSAGES + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "conversation_id INTEGER NOT NULL, " +
                "role TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "reasoning TEXT, " +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_msg_conv ON " + T_MESSAGES + "(conversation_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 保留数据，只增加缺失的列
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + T_MESSAGES + " ADD COLUMN reasoning TEXT");
            } catch (Exception e) {
                // 列已存在则忽略
            }
        }
    }

    /** 安全获取列索引：列不存在返回 -1 */
    private int col(Cursor c, String name) {
        return c.getColumnIndex(name);
    }

    /** 安全读取字符串：列不存在或值为 null 返回 "" */
    private String safeStr(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        if (i < 0 || c.isNull(i)) return "";
        return c.getString(i);
    }

    // ============ 对话操作 ============

    public long createConversation(String title) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        cv.put("created_at", now);
        cv.put("updated_at", now);
        cv.put("preview", "");
        return db.insert(T_CONVERSATIONS, null, cv);
    }

    public List<Conversation> getAllConversations() {
        List<Conversation> list = new ArrayList<Conversation>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_CONVERSATIONS, null, null, null, null, null, "updated_at DESC");
        try {
            while (c.moveToNext()) {
                Conversation conv = new Conversation();
                conv.id = c.getLong(col(c, "id"));
                conv.title = safeStr(c, "title");
                conv.createdAt = c.getLong(col(c, "created_at"));
                conv.updatedAt = c.getLong(col(c, "updated_at"));
                conv.preview = safeStr(c, "preview");
                list.add(conv);
            }
        } finally {
            c.close();
        }
        return list;
    }

    public Conversation getConversation(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_CONVERSATIONS, null, "id=?", new String[]{String.valueOf(id)},
                null, null, null);
        try {
            if (c.moveToFirst()) {
                Conversation conv = new Conversation();
                conv.id = c.getLong(col(c, "id"));
                conv.title = safeStr(c, "title");
                conv.createdAt = c.getLong(col(c, "created_at"));
                conv.updatedAt = c.getLong(col(c, "updated_at"));
                conv.preview = safeStr(c, "preview");
                return conv;
            }
        } finally {
            c.close();
        }
        return null;
    }

    public void renameConversation(long id, String newTitle) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", newTitle);
        cv.put("updated_at", System.currentTimeMillis());
        db.update(T_CONVERSATIONS, cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteConversation(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(T_MESSAGES, "conversation_id=?", new String[]{String.valueOf(id)});
        db.delete(T_CONVERSATIONS, "id=?", new String[]{String.valueOf(id)});
    }

    public void clearAllConversations() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(T_MESSAGES, null, null);
        db.delete(T_CONVERSATIONS, null, null);
    }

    public void touchConversation(long id) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("updated_at", System.currentTimeMillis());
        db.update(T_CONVERSATIONS, cv, "id=?", new String[]{String.valueOf(id)});
    }

    // ============ 消息操作 ============

    public long addMessage(long conversationId, String role, String content) {
        return addMessage(conversationId, role, content, "");
    }

    public long addMessage(long conversationId, String role, String content, String reasoning) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("conversation_id", conversationId);
        cv.put("role", role);
        cv.put("content", content == null ? "" : content);
        cv.put("reasoning", reasoning == null ? "" : reasoning);
        cv.put("created_at", now);
        long msgId = db.insert(T_MESSAGES, null, cv);

        // 更新对话预览和时间
        ContentValues convCv = new ContentValues();
        convCv.put("updated_at", now);
        String preview = content;
        if (preview != null && preview.length() > 60) {
            preview = preview.substring(0, 60) + "…";
        }
        convCv.put("preview", preview == null ? "" : preview.replace("\n", " "));
        db.update(T_CONVERSATIONS, convCv, "id=?", new String[]{String.valueOf(conversationId)});

        return msgId;
    }

    public List<ChatMessage> getMessages(long conversationId) {
        List<ChatMessage> list = new ArrayList<ChatMessage>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_MESSAGES, null, "conversation_id=?",
                new String[]{String.valueOf(conversationId)},
                null, null, "created_at ASC, id ASC");
        try {
            while (c.moveToNext()) {
                ChatMessage m = new ChatMessage();
                m.id = c.getLong(col(c, "id"));
                m.conversationId = conversationId;
                m.role = safeStr(c, "role");
                m.content = safeStr(c, "content");
                m.reasoning = safeStr(c, "reasoning");
                m.createdAt = c.getLong(col(c, "created_at"));
                list.add(m);
            }
        } finally {
            c.close();
        }
        return list;
    }

    public void updateMessageContent(long messageId, String newContent) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("content", newContent == null ? "" : newContent);
        db.update(T_MESSAGES, cv, "id=?", new String[]{String.valueOf(messageId)});
    }

    public void updateMessage(long messageId, String newContent, String newReasoning) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("content", newContent == null ? "" : newContent);
        cv.put("reasoning", newReasoning == null ? "" : newReasoning);
        db.update(T_MESSAGES, cv, "id=?", new String[]{String.valueOf(messageId)});
    }

    public void deleteMessagesAfter(long conversationId, long messageId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(T_MESSAGES, "conversation_id=? AND id>=?",
                new String[]{String.valueOf(conversationId), String.valueOf(messageId)});
    }
}
