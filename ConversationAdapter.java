package com.oac.nazhiyazi.op;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.List;

/**
 * 异步 AI 请求任务。兼容 Android 2.3。
 *
 * 使用 Thread + Handler 实现（不依赖 AsyncTask，避免在某些老机型上的兼容问题）。
 * 支持流式 SSE 响应，每收到一段 delta 即回调 onDelta / onReasoningDelta。
 * 支持取消：调用 cancel() 后会尽快结束读取并断开连接。
 *
 * 错误信息尽量详细：包含 HTTP code、响应体、异常类型与消息。
 */
public class AIRequest {

    public interface AICallback {
        /** 主线程：请求开始（已建立连接） */
        void onStart();
        /** 主线程：流式增量内容到达（content 部分） */
        void onDelta(String delta);
        /** 主线程：流式增量思考内容到达（reasoning_content 部分） */
        void onReasoningDelta(String delta);
        /** 主线程：请求完成，参数为完整回复（content）与完整思考（reasoning） */
        void onComplete(String fullResponse, String fullReasoning);
        /** 主线程：出错 */
        void onError(String error);
    }

    private static final int MSG_START = 1;
    private static final int MSG_DELTA = 2;
    private static final int MSG_REASONING_DELTA = 3;
    private static final int MSG_COMPLETE = 4;
    private static final int MSG_ERROR = 5;

    /** 同时携带 content 和 reasoning 的完成消息 */
    private static class CompleteInfo {
        String content;
        String reasoning;
        CompleteInfo(String c, String r) { content = c; reasoning = r; }
    }

    private final Handler mHandler;
    private volatile Thread mThread;
    private volatile HttpURLConnection mConn;
    private volatile boolean mCancelled = false;

    // 流式 delta 缓冲区：后台线程追加，主线程 flush runnable 读取并清空
    private final StringBuilder mContentBuffer = new StringBuilder();
    private final StringBuilder mReasoningBuffer = new StringBuilder();
    private final Runnable mFlushRunnable;

    public AIRequest() {
        mFlushRunnable = new Runnable() {
            @Override
            public void run() {
                String content = null;
                String reasoning = null;
                synchronized (mContentBuffer) {
                    if (mContentBuffer.length() > 0) {
                        content = mContentBuffer.toString();
                        mContentBuffer.setLength(0);
                    }
                }
                synchronized (mReasoningBuffer) {
                    if (mReasoningBuffer.length() > 0) {
                        reasoning = mReasoningBuffer.toString();
                        mReasoningBuffer.setLength(0);
                    }
                }
                AICallback cb = mCallback;
                if (cb == null) return;
                if (content != null) {
                    cb.onDelta(content);
                }
                if (reasoning != null) {
                    cb.onReasoningDelta(reasoning);
                }
            }
        };
        mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                AICallback cb = mCallback;
                if (cb == null) return;
                switch (msg.what) {
                    case MSG_START:
                        cb.onStart();
                        break;
                    case MSG_DELTA:
                        cb.onDelta((String) msg.obj);
                        break;
                    case MSG_REASONING_DELTA:
                        cb.onReasoningDelta((String) msg.obj);
                        break;
                    case MSG_COMPLETE:
                        CompleteInfo ci = (CompleteInfo) msg.obj;
                        cb.onComplete(ci.content, ci.reasoning);
                        mCallback = null;
                        break;
                    case MSG_ERROR:
                        cb.onError((String) msg.obj);
                        mCallback = null;
                        break;
                }
            }
        };
    }

    private volatile AICallback mCallback;

    /**
     * 发起一次请求。
     *
     * @param model       模型配置
     * @param history     历史消息（不含当前用户消息）
     * @param userMessage 当前用户消息
     * @param stream      是否流式
     * @param callback    回调（所有方法在主线程调用）
     */
    public synchronized void execute(final ModelConfig model,
                                     final List<ChatMessage> history,
                                     final String userMessage,
                                     final boolean stream,
                                     final AICallback callback) {
        mCallback = callback;
        mCancelled = false;
        mHandler.removeCallbacks(mFlushRunnable);
        synchronized (mContentBuffer) { mContentBuffer.setLength(0); }
        synchronized (mReasoningBuffer) { mReasoningBuffer.setLength(0); }
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = AIClient.buildRequestBody(model, history, userMessage, stream);
                    HttpURLConnection conn = AIClient.connect(model, body);
                    mConn = conn;

                    int code = conn.getResponseCode();
                    if (mCancelled) {
                        conn.disconnect();
                        return;
                    }
                    if (code < 200 || code >= 300) {
                        // 错误响应：保留完整信息
                        java.io.InputStream es = conn.getErrorStream();
                        String errBody = "";
                        if (es != null) {
                            BufferedReader r = new BufferedReader(new InputStreamReader(es, "UTF-8"));
                            StringBuilder sb = new StringBuilder();
                            String l;
                            while ((l = r.readLine()) != null) sb.append(l).append("\n");
                            r.close();
                            errBody = sb.toString();
                        }
                        String msg = buildErrorMessage(code, errBody, null);
                        final String errMsg = msg;
                        mHandler.obtainMessage(MSG_ERROR, errMsg).sendToTarget();
                        conn.disconnect();
                        return;
                    }

                    mHandler.sendEmptyMessage(MSG_START);

                    if (stream) {
                        readStream(conn);
                    } else {
                        readFull(conn);
                    }
                } catch (final Exception e) {
                    if (mCancelled) return;
                    String msg = buildErrorMessage(-1, null, e);
                    mHandler.obtainMessage(MSG_ERROR, msg).sendToTarget();
                }
            }
        }, "AIRequest");
        mThread.setDaemon(true);
        mThread.start();
    }

    private void readStream(HttpURLConnection conn) throws Exception {
        InputStream is = conn.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullReasoning = new StringBuilder();
        String line;
        while (!mCancelled && (line = reader.readLine()) != null) {
            StreamDelta d = AIClient.parseStreamLine(line);
            if (d == null) {
                // [DONE]
                break;
            }
            if (d.hasContent()) {
                fullContent.append(d.content);
                synchronized (mContentBuffer) {
                    mContentBuffer.append(d.content);
                }
                scheduleFlush();
            }
            if (d.hasReasoning()) {
                fullReasoning.append(d.reasoning);
                synchronized (mReasoningBuffer) {
                    mReasoningBuffer.append(d.reasoning);
                }
                scheduleFlush();
            }
        }
        reader.close();
        conn.disconnect();
        if (mCancelled) {
            mHandler.removeCallbacks(mFlushRunnable);
            return;
        }
        // 确保剩余缓冲立即 flush
        mHandler.removeCallbacks(mFlushRunnable);
        mFlushRunnable.run();
        mHandler.obtainMessage(MSG_COMPLETE,
                new CompleteInfo(fullContent.toString(), fullReasoning.toString())).sendToTarget();
    }

    /** 安排 20ms 后 flush，兼顾延迟与主线程消息数量 */
    private void scheduleFlush() {
        mHandler.removeCallbacks(mFlushRunnable);
        mHandler.postDelayed(mFlushRunnable, 20);
    }

    private void readFull(HttpURLConnection conn) throws Exception {
        StreamDelta d = AIClient.readFullResponseDelta(conn);
        conn.disconnect();
        if (mCancelled) return;
        if (d.hasContent()) {
            mHandler.obtainMessage(MSG_DELTA, d.content).sendToTarget();
        }
        if (d.hasReasoning()) {
            mHandler.obtainMessage(MSG_REASONING_DELTA, d.reasoning).sendToTarget();
        }
        mHandler.obtainMessage(MSG_COMPLETE,
                new CompleteInfo(d.content, d.reasoning)).sendToTarget();
    }

    /**
     * 构建详细的错误信息。
     *
     * @param code HTTP 状态码（-1 表示非 HTTP 错误，如网络异常）
     * @param errBody 错误响应体
     * @param e 异常对象（可为 null）
     */
    private static String buildErrorMessage(int code, String errBody, Throwable e) {
        StringBuilder sb = new StringBuilder();
        if (code > 0) {
            sb.append("HTTP ").append(code);
        } else if (e != null) {
            sb.append(e.getClass().getSimpleName());
        } else {
            sb.append("Unknown error");
        }

        // 解析错误响应体
        String parsedMsg = null;
        if (errBody != null && errBody.length() > 0) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(errBody);
                org.json.JSONObject err = json.optJSONObject("error");
                if (err != null) {
                    if (err.isNull("message")) {
                        // skip
                    } else {
                        String m = err.optString("message", "");
                        if (m != null && m.length() > 0 && !"null".equals(m)) {
                            parsedMsg = m;
                        }
                    }
                } else {
                    if (!json.isNull("message")) {
                        String m = json.optString("message", "");
                        if (m != null && m.length() > 0 && !"null".equals(m)) {
                            parsedMsg = m;
                        }
                    }
                }
            } catch (Exception ex) {
                // 不是 JSON，保留原始响应体
            }
        }

        if (parsedMsg != null && parsedMsg.length() > 0) {
            sb.append(": ").append(parsedMsg);
        } else if (errBody != null && errBody.length() > 0) {
            String trunc = errBody.length() > 300 ? errBody.substring(0, 300) + "…" : errBody;
            sb.append("\nResponse: ").append(trunc);
        }

        if (e != null && e.getMessage() != null && e.getMessage().length() > 0) {
            sb.append("\nException: ").append(e.getMessage());
        }

        return sb.toString();
    }

    /**
     * 取消请求。会断开连接，不再回调。
     */
    public void cancel() {
        mCancelled = true;
        if (mConn != null) {
            try { mConn.disconnect(); } catch (Exception e) {}
            mConn = null;
        }
        if (mThread != null) {
            mThread.interrupt();
        }
        // 清空缓冲，避免取消后残留 delta 继续回调
        mHandler.removeCallbacks(mFlushRunnable);
        synchronized (mContentBuffer) { mContentBuffer.setLength(0); }
        synchronized (mReasoningBuffer) { mReasoningBuffer.setLength(0); }
    }

    public boolean isCancelled() {
        return mCancelled;
    }
}
