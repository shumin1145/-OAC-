package com.oac.nazhiyazi.op;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主对话界面。兼容 Android 2.3 (API 9+)
 *
 * - 顶部栏 + 消息列表 + 输入框
 * - 侧边栏抽屉：对话列表 / 当前模型 / 新建 / 清空 / 设置
 * - 流式响应实时更新
 *
 * 健壮性策略：
 * - onCreate 全局 try-catch，避免任何初始化异常直接闪退
 * - 所有 findViewById 后做 null 检查
 * - 所有 setSelection(count-1) 先检查 count > 0
 * - 所有数据库操作 try-catch
 */
public class MainActivity extends Activity {

    private static final int REQ_SETTINGS = 1;

    // 视图
    private TextView mTvTitle;
    private TextView mTvModelName;
    private ListView mListMessages;
    private TextView mTvEmpty;
    private EditText mEtInput;
    private Button mBtnSend;

    // 侧边栏
    private View mDrawer;
    private View mMask;
    private TextView mTvDrawerModel;
    private ListView mListConversations;
    private TextView mTvDrawerEmpty;
    private boolean mDrawerOpen = false;

    // 用户是否手动滑动了列表；手动滑动时暂停自动跟滚，避免屏幕抖动
    private boolean mUserScrolled = false;
    private float mTouchDownY = -1f;
    private static final float SCROLL_TRIGGER_PX = 20f;
    private int mLastScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
    private final Handler mScrollHandler = new Handler();
    private static final long AUTO_RESUME_DELAY_MS = 300;
    private final Runnable mResumeAutoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            // 仅 AI 正在输出时才自动回到底部并恢复跟随
            if (!hasActiveOutput()) return;
            if (mLastScrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                // 惯性滑动还没停，再等一轮
                mScrollHandler.postDelayed(this, AUTO_RESUME_DELAY_MS);
                return;
            }
            mUserScrolled = false;
            setAutoScrollEnabled(true);
            // 重新测量并强制滚到真实底部
            if (mChatAdapter != null) {
                mChatAdapter.notifyDataSetChanged();
            }
            forceScrollToBottom();
        }
    };

    // 数据
    private DBHelper mDb;
    private SettingsManager mSettings;
    private ChatAdapter mChatAdapter;
    private ConversationAdapter mConvAdapter;
    private long mCurrentConversationId = -1;

    // 每个对话独立的 pending 请求：conversationId -> PendingRequest
    private final Map<Long, PendingRequest> mPendingRequests = new HashMap<Long, PendingRequest>();

    /**
     * 记录一个对话正在进行的 AI 请求及其占位消息 id。
     * 这样切换对话后，各自的发送按钮状态互不干扰。
     */
    private static class PendingRequest {
        AIRequest request;
        long aiMessageId;
        boolean placeholderShown;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            mSettings = SettingsManager.get(this);
        } catch (Throwable t) {
            // SettingsManager 初始化失败，无法继续
            toast(getString(R.string.msg_load_settings_failed, safeMsg(t)));
            finish();
            return;
        }

        // 首次启动：弹出不可跳过的语言选择对话框
        if (mSettings.isFirstLaunch()) {
            showFirstLaunchLanguageDialog();
            // 先不加载主界面，等用户选择语言后再初始化
            return;
        }

        // 非首次：应用保存的语言设置
        applyLanguage(mSettings.getLanguage());

        // 未同意隐私协议时先显示隐私提示
        if (!mSettings.isPrivacyAccepted()) {
            showPrivacyDialog();
            return;
        }

        try {
            setContentView(R.layout.activity_main);
        } catch (Throwable t) {
            // 布局加载失败，无法恢复
            toast(getString(R.string.msg_load_ui_failed, safeMsg(t)));
            finish();
            return;
        }

        initMain();
    }

    /**
     * 应用指定语言。兼容 Android 2.3（不使用 createConfigurationContext 等新 API）。
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

    /**
     * 首次启动语言选择对话框。兼容 Android 2.3，不可取消、不可跳过。
     */
    private void showFirstLaunchLanguageDialog() {
        try {
            final String[] langs = {"zh", "en"};
            String[] names = {
                    getString(R.string.lang_chinese),
                    getString(R.string.lang_english)
            };
            AlertDialog dlg = new AlertDialog.Builder(this)
                    .setTitle(R.string.lang_select_title)
                    .setCancelable(false)
                    .setSingleChoiceItems(names, -1, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                String lang = langs[which];
                                mSettings.setLanguage(lang);
                                mSettings.setFirstLaunch(false);
                                applyLanguage(lang);
                                dialog.dismiss();

                                // 语言选完后显示隐私提示
                                showPrivacyDialog();
                            } catch (Throwable t) {
                                toast(getString(R.string.msg_language_set_failed, safeMsg(t)));
                            }
                        }
                    })
                    .create();
            // 屏蔽返回键，必须二选一
            dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
                @Override
                public boolean onKey(DialogInterface dialog, int keyCode, android.view.KeyEvent event) {
                    return keyCode == android.view.KeyEvent.KEYCODE_BACK;
                }
            });
            dlg.show();
        } catch (Throwable t) {
            toast(getString(R.string.msg_language_set_failed, safeMsg(t)));
            // 容错：即使语言弹窗失败也显示隐私提示
            applyLanguage("zh");
            showPrivacyDialog();
        }
    }

    /**
     * 隐私提示弹窗。同意后进入主界面，拒绝则退出应用。
     */
    private void showPrivacyDialog() {
        try {
            AlertDialog dlg = new AlertDialog.Builder(this)
                    .setTitle(R.string.privacy_title)
                    .setMessage(R.string.privacy_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.privacy_agree, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            mSettings.setPrivacyAccepted(true);
                            dialog.dismiss();
                            try {
                                setContentView(R.layout.activity_main);
                                initMain();
                            } catch (Throwable t) {
                                toast(getString(R.string.msg_load_ui_failed, safeMsg(t)));
                                finish();
                            }
                        }
                    })
                    .setNegativeButton(R.string.privacy_reject, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            finish();
                            // 彻底退出，避免留在后台
                            try {
                                System.exit(0);
                            } catch (Throwable ignored) {}
                        }
                    })
                    .create();
            dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
                @Override
                public boolean onKey(DialogInterface dialog, int keyCode, android.view.KeyEvent event) {
                    return keyCode == android.view.KeyEvent.KEYCODE_BACK;
                }
            });
            dlg.show();
        } catch (Throwable t) {
            toast(getString(R.string.msg_load_ui_failed, safeMsg(t)));
            // 容错：隐私弹窗失败也尝试进入主界面
            try {
                setContentView(R.layout.activity_main);
                initMain();
            } catch (Throwable t2) {
                toast(getString(R.string.msg_load_ui_failed, safeMsg(t2)));
                finish();
            }
        }
    }

    /**
     * 初始化主界面（视图、数据、抽屉等）。
     */
    private void initMain() {
        try {
            mDb = new DBHelper(this);

            // 显式引用 SettingsActivity，避免某些 shrink/老设备上找不到类
            try {
                Class.forName("com.oac.nazhiyazi.op.SettingsActivity");
            } catch (Throwable t) {
                // ignore
            }

            initViews();
            initDrawer();
            updateModelDisplay();

            // 默认创建/打开一个对话
            List<Conversation> convs = mDb.getAllConversations();
            if (convs.isEmpty()) {
                mCurrentConversationId = mDb.createConversation(getString(R.string.msg_default_title));
            } else {
                mCurrentConversationId = convs.get(0).id;
            }
            loadCurrentConversation();
            refreshConversationList();
        } catch (Throwable t) {
            // 任何初始化异常都尽量不让 app 闪退
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

    /** 判断列表是否已经停在底部。 */
    private boolean isAtBottom() {
        if (mListMessages == null || mChatAdapter == null || mChatAdapter.getCount() == 0) {
            return true;
        }
        try {
            int lastPos = mChatAdapter.getCount() - 1;
            int lastVisible = mListMessages.getLastVisiblePosition();
            if (lastVisible < lastPos) return false;
            int firstVisible = mListMessages.getFirstVisiblePosition();
            View child = mListMessages.getChildAt(lastVisible - firstVisible);
            if (child != null) {
                return child.getBottom() <= mListMessages.getHeight() - mListMessages.getPaddingBottom();
            }
        } catch (Throwable t) {
            // ignore
        }
        return true;
    }

    /** 只有用户没手动上滑时才自动滚到底部，避免输出时屏幕抖动。 */
    private void scrollToBottomIfAllowed() {
        if (!mUserScrolled && mListMessages != null && mChatAdapter != null && mChatAdapter.getCount() > 0) {
            mListMessages.setSelection(mChatAdapter.getCount() - 1);
        }
    }

    /** 当前对话是否还有 AI 请求在输出中。 */
    private boolean hasActiveOutput() {
        if (mCurrentConversationId < 0 || mPendingRequests == null) return false;
        return mPendingRequests.get(mCurrentConversationId) != null;
    }

    /** 开启/关闭自动跟滚。ALWAYS_SCROLL 会在数据变化后自动滚到真实底部。 */
    private void setAutoScrollEnabled(boolean enabled) {
        if (mListMessages == null) return;
        try {
            mListMessages.setTranscriptMode(enabled ? ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL : ListView.TRANSCRIPT_MODE_DISABLED);
        } catch (Throwable ignored) {}
    }

    /** 等下一次 layout 完成后强制滚到最后一条，避免 item 高度未测量时滚不到位。 */
    private void forceScrollToBottom() {
        if (mListMessages == null || mChatAdapter == null || mChatAdapter.getCount() == 0) return;
        final ListView list = mListMessages;
        final int pos = mChatAdapter.getCount() - 1;
        list.post(new Runnable() {
            @Override
            public void run() {
                if (list != null) {
                    list.setSelection(pos);
                }
            }
        });
    }

    private void initViews() {
        mTvTitle = (TextView) findViewById(R.id.tv_title);
        mTvModelName = (TextView) findViewById(R.id.tv_model_name);
        mListMessages = (ListView) findViewById(R.id.list_messages);
        mTvEmpty = (TextView) findViewById(R.id.tv_empty);
        mEtInput = (EditText) findViewById(R.id.et_input);
        mBtnSend = (Button) findViewById(R.id.btn_send);

        mChatAdapter = new ChatAdapter(this);
        if (mListMessages != null) {
            mListMessages.setAdapter(mChatAdapter);
            mListMessages.setStackFromBottom(true);
            mListMessages.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
            mListMessages.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    mLastScrollState = scrollState;
                    if (scrollState == SCROLL_STATE_IDLE && mUserScrolled && hasActiveOutput()) {
                        // 滑动状态回到静止且 AI 仍在输出，0.3 秒后自动回到底部跟随
                        mScrollHandler.removeCallbacks(mResumeAutoScrollRunnable);
                        mScrollHandler.postDelayed(mResumeAutoScrollRunnable, AUTO_RESUME_DELAY_MS);
                    }
                }
                @Override
                public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {}
            });
            mListMessages.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getAction();
                    if (action == MotionEvent.ACTION_DOWN) {
                        mTouchDownY = event.getY();
                        // 手指放到列表上时先暂停跟滚，避免按住期间画面抖动
                        setAutoScrollEnabled(false);
                        mScrollHandler.removeCallbacks(mResumeAutoScrollRunnable);
                    } else if (action == MotionEvent.ACTION_MOVE) {
                        if (mTouchDownY >= 0 && Math.abs(event.getY() - mTouchDownY) > SCROLL_TRIGGER_PX) {
                            // 真正产生滑动距离才标记为手动滚动
                            mUserScrolled = true;
                            mScrollHandler.removeCallbacks(mResumeAutoScrollRunnable);
                            mTouchDownY = -1f;
                        }
                    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        mTouchDownY = -1f;
                        if (mUserScrolled && hasActiveOutput()) {
                            // 真正滑动过且 AI 仍在输出，0.3 秒后回到底部并继续跟随
                            mScrollHandler.removeCallbacks(mResumeAutoScrollRunnable);
                            mScrollHandler.postDelayed(mResumeAutoScrollRunnable, AUTO_RESUME_DELAY_MS);
                        } else if (!mUserScrolled && hasActiveOutput()) {
                            // 只是点击没有滑动，直接恢复跟滚
                            setAutoScrollEnabled(true);
                        }
                    }
                    return false;
                }
            });
        }

        // 发送按钮
        if (mBtnSend != null) {
            mBtnSend.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onSendClick();
                }
            });
        }

        // 输入框变化：空内容禁用发送按钮
        if (mEtInput != null) {
            mEtInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    updateSendButton();
                }
            });
        }
        updateSendButton();

        // 长按消息复制
        if (mListMessages != null) {
            mListMessages.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                    try {
                        ChatMessage m = mChatAdapter.getItem(position);
                        if (m != null && m.content != null && m.content.length() > 0) {
                            copyToClipboard(m.content);
                            Toast.makeText(MainActivity.this, R.string.msg_copied, Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable t) {
                        // ignore
                    }
                    return true;
                }
            });
        }

        // 顶部栏按钮
        View btnOpenDrawer = findViewById(R.id.btn_open_drawer);
        if (btnOpenDrawer != null) {
            btnOpenDrawer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openDrawer();
                }
            });
        }
        View btnNewChat = findViewById(R.id.btn_new_chat);
        if (btnNewChat != null) {
            btnNewChat.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    createNewConversation();
                }
            });
        }
    }

    private void updateSendButton() {
        if (mBtnSend == null || mEtInput == null) return;
        try {
            String text = mEtInput.getText().toString().trim();
            boolean waiting = mPendingRequests.containsKey(mCurrentConversationId);
            boolean enabled = text.length() > 0 && !waiting;
            mBtnSend.setEnabled(enabled);
        } catch (Throwable t) {
            // ignore
        }
    }

    private void initDrawer() {
        mDrawer = findViewById(R.id.drawer);
        mMask = findViewById(R.id.mask);
        mTvDrawerModel = (TextView) findViewById(R.id.tv_drawer_model);
        mListConversations = (ListView) findViewById(R.id.list_conversations);
        mTvDrawerEmpty = (TextView) findViewById(R.id.tv_drawer_empty);

        if (mDrawer == null) {
            // drawer 加载失败，无法使用侧边栏功能
            toast(getString(R.string.msg_drawer_load_failed));
            return;
        }

        mConvAdapter = new ConversationAdapter(this);
        mConvAdapter.setListener(new ConversationAdapter.OnConversationActionListener() {
            @Override
            public void onDelete(final Conversation conv) {
                try {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(R.string.app_name)
                            .setMessage(R.string.msg_confirm_delete)
                            .setPositiveButton(R.string.msg_yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    try {
                                        mDb.deleteConversation(conv.id);
                                        if (conv.id == mCurrentConversationId) {
                                            List<Conversation> rest = mDb.getAllConversations();
                                            if (rest.isEmpty()) {
                                                mCurrentConversationId = mDb.createConversation(getString(R.string.msg_default_title));
                                            } else {
                                                mCurrentConversationId = rest.get(0).id;
                                            }
                                            mUserScrolled = false;
                                            loadCurrentConversation();
                                        }
                                        refreshConversationList();
                                    } catch (Throwable t) {
                                        toast(getString(R.string.msg_delete_failed, safeMsg(t)));
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
        if (mListConversations != null) {
            mListConversations.setAdapter(mConvAdapter);

            mListConversations.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    try {
                        Conversation conv = mConvAdapter.getItem(position);
                        if (conv != null) {
                            mUserScrolled = false;
                            mCurrentConversationId = conv.id;
                            loadCurrentConversation();
                            closeDrawer();
                        }
                    } catch (Throwable t) {
                        toast(safeMsg(t));
                    }
                }
            });
        }

        // 侧边栏按钮
        bindDrawerButton(R.id.btn_drawer_close, new Runnable() {
            @Override public void run() { closeDrawer(); }
        });
        bindDrawerButton(R.id.btn_drawer_new, new Runnable() {
            @Override public void run() {
                createNewConversation();
                closeDrawer();
            }
        });
        bindDrawerButton(R.id.btn_drawer_clear, new Runnable() {
            @Override public void run() {
                try {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(R.string.app_name)
                            .setMessage(R.string.msg_confirm_clear)
                            .setPositiveButton(R.string.msg_yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    try {
                                        mDb.clearAllConversations();
                                        mCurrentConversationId = mDb.createConversation(getString(R.string.msg_default_title));
                                        mUserScrolled = false;
                                        loadCurrentConversation();
                                        refreshConversationList();
                                    } catch (Throwable t) {
                                        toast(getString(R.string.msg_clear_failed, safeMsg(t)));
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
        bindDrawerButton(R.id.btn_drawer_settings, new Runnable() {
            @Override public void run() {
                closeDrawer();
                Intent it = new Intent(MainActivity.this, SettingsActivity.class);
                try {
                    startActivityForResult(it, REQ_SETTINGS);
                } catch (Throwable t) {
                    toast(safeMsg(t));
                }
            }
        });
        bindDrawerButton(R.id.btn_drawer_active_model, new Runnable() {
            @Override public void run() {
                closeDrawer();
                Intent it = new Intent(MainActivity.this, SettingsActivity.class);
                try {
                    startActivityForResult(it, REQ_SETTINGS);
                } catch (Throwable t) {
                    toast(safeMsg(t));
                }
            }
        });

        // 遮罩点击关闭
        if (mMask != null) {
            mMask.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeDrawer();
                }
            });
        }
    }

    private void bindDrawerButton(int id, final Runnable action) {
        View v = findViewById(id);
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

    private void openDrawer() {
        if (mDrawer == null) return;
        if (mDrawerOpen) return;
        mDrawerOpen = true;
        // 先刷新数据再开启动画，避免动画过程中 notifyDataSetChanged 导致卡顿
        refreshConversationList();
        updateModelDisplay();
        try {
            mDrawer.setVisibility(View.VISIBLE);
            if (mMask != null) mMask.setVisibility(View.VISIBLE);
            // 滑入动画
            TranslateAnimation anim = new TranslateAnimation(
                    Animation.RELATIVE_TO_SELF, -1.0f,
                    Animation.RELATIVE_TO_SELF, 0.0f,
                    Animation.RELATIVE_TO_SELF, 0.0f,
                    Animation.RELATIVE_TO_SELF, 0.0f);
            anim.setDuration(200);
            mDrawer.startAnimation(anim);
        } catch (Throwable t) {
            // ignore animation errors
        }
    }

    private void closeDrawer() {
        if (mDrawer == null) return;
        if (!mDrawerOpen) return;
        mDrawerOpen = false;
        try {
            TranslateAnimation anim = new TranslateAnimation(
                    Animation.RELATIVE_TO_SELF, 0.0f,
                    Animation.RELATIVE_TO_SELF, -1.0f,
                    Animation.RELATIVE_TO_SELF, 0.0f,
                    Animation.RELATIVE_TO_SELF, 0.0f);
            anim.setDuration(200);
            anim.setAnimationListener(new Animation.AnimationListener() {
                @Override public void onAnimationStart(Animation animation) {}
                @Override
                public void onAnimationEnd(Animation animation) {
                    try {
                        mDrawer.setVisibility(View.GONE);
                        if (mMask != null) mMask.setVisibility(View.GONE);
                    } catch (Throwable t) {
                        // ignore
                    }
                }
                @Override public void onAnimationRepeat(Animation animation) {}
            });
            mDrawer.startAnimation(anim);
        } catch (Throwable t) {
            try {
                mDrawer.setVisibility(View.GONE);
                if (mMask != null) mMask.setVisibility(View.GONE);
            } catch (Throwable t2) {
                // ignore
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mDrawerOpen) {
            closeDrawer();
            return;
        }
        PendingRequest pr = mPendingRequests.get(mCurrentConversationId);
        if (pr != null && pr.request != null) {
            pr.request.cancel();
            mPendingRequests.remove(mCurrentConversationId);
            updateSendButton();
            return;
        }
        super.onBackPressed();
    }

    private void updateModelDisplay() {
        ModelConfig m = null;
        try {
            m = mSettings.getActiveModel();
        } catch (Throwable t) {
            // ignore
        }
        if (m == null) {
            if (mTvModelName != null) mTvModelName.setText("");
            if (mTvDrawerModel != null) mTvDrawerModel.setText(R.string.set_no_active);
        } else {
            String name = m.name == null ? "" : m.name;
            if (mTvModelName != null) mTvModelName.setText(name);
            if (mTvDrawerModel != null) mTvDrawerModel.setText(name);
        }
    }

    private void refreshConversationList() {
        if (mConvAdapter == null) return;
        try {
            List<Conversation> list = mDb.getAllConversations();
            mConvAdapter.setItems(list);
            mConvAdapter.setActiveId(mCurrentConversationId);
            if (mTvDrawerEmpty != null) {
                mTvDrawerEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable t) {
            toast(safeMsg(t));
        }
    }

    private void createNewConversation() {
        try {
            mUserScrolled = false;
            mCurrentConversationId = mDb.createConversation(getString(R.string.msg_default_title));
            loadCurrentConversation();
            refreshConversationList();
        } catch (Throwable t) {
            toast(getString(R.string.msg_create_failed, safeMsg(t)));
        }
    }

    private void loadCurrentConversation() {
        if (mChatAdapter == null) return;
        try {
            List<ChatMessage> msgs = mDb.getMessages(mCurrentConversationId);
            mChatAdapter.setItems(msgs);
            Conversation conv = mDb.getConversation(mCurrentConversationId);
            if (mTvTitle != null) {
                if (conv != null && conv.title != null) {
                    mTvTitle.setText(conv.title);
                } else {
                    mTvTitle.setText(R.string.msg_default_title);
                }
            }
            if (mTvEmpty != null) {
                mTvEmpty.setVisibility((msgs == null || msgs.isEmpty()) ? View.VISIBLE : View.GONE);
            }
            if (mListMessages != null && mChatAdapter.getCount() > 0) {
                mListMessages.setSelection(mChatAdapter.getCount() - 1);
            }
            if (mConvAdapter != null) {
                mConvAdapter.setActiveId(mCurrentConversationId);
            }
            updateSendButton();
        } catch (Throwable t) {
            toast(safeMsg(t));
        }
    }

    private void onSendClick() {
        if (mEtInput == null || mChatAdapter == null || mDb == null) return;
        String text;
        try {
            text = mEtInput.getText().toString().trim();
        } catch (Throwable t) {
            toast(safeMsg(t));
            return;
        }
        if (text.length() == 0) {
            Toast.makeText(this, R.string.msg_empty_input, Toast.LENGTH_SHORT).show();
            return;
        }

        ModelConfig activeModel = null;
        try {
            activeModel = mSettings.getActiveModel();
        } catch (Throwable t) {
            activeModel = null;
        }
        final ModelConfig model = activeModel;
        if (model == null || model.apiUrl == null || model.apiUrl.length() == 0
                || model.modelId == null || model.modelId.length() == 0) {
            Toast.makeText(this, R.string.msg_no_model, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            // 用户主动发送新消息时恢复自动跟滚
            mUserScrolled = false;
            setAutoScrollEnabled(true);

            // 保存用户消息，并把数据库 id 赋给对象，后续更新不会错位
            long userMsgId = mDb.addMessage(mCurrentConversationId, "user", text);
            ChatMessage userMsg = new ChatMessage("user", text);
            userMsg.id = userMsgId;
            userMsg.conversationId = mCurrentConversationId;
            mChatAdapter.addItem(userMsg);
            if (mTvEmpty != null) mTvEmpty.setVisibility(View.GONE);
            // TRANSCRIPT_MODE_ALWAYS_SCROLL 会自动滚到底

            // 如果是新对话，自动用消息预览作为标题
            Conversation conv = mDb.getConversation(mCurrentConversationId);
            if (conv != null && getString(R.string.msg_default_title).equals(conv.title)) {
                String title = text;
                if (title.length() > 20) title = title.substring(0, 20) + "…";
                mDb.renameConversation(mCurrentConversationId, title);
                if (mTvTitle != null) mTvTitle.setText(title);
            }

            mEtInput.setText("");

            // 添加 AI 占位，先显示 "..."，同样保存数据库 id
            final long convId = mCurrentConversationId;
            ChatMessage aiMsg = new ChatMessage("assistant", "...");
            long aiMsgId = mDb.addMessage(convId, "assistant", "...", "");
            aiMsg.id = aiMsgId;
            aiMsg.conversationId = convId;
            mChatAdapter.addItem(aiMsg);
            // TRANSCRIPT_MODE_ALWAYS_SCROLL 会自动滚到底

            final PendingRequest pr = new PendingRequest();
            pr.aiMessageId = aiMsgId;
            pr.placeholderShown = true;
            mPendingRequests.put(convId, pr);
            updateSendButton();

            // 构造历史（不含最新两条：刚加的 user + ai 占位）
            List<ChatMessage> history = new ArrayList<ChatMessage>();
            List<ChatMessage> all = mChatAdapter.getItems();
            for (int i = 0; i < all.size() - 2; i++) {
                history.add(all.get(i));
            }

            final boolean stream = mSettings.isStreamOutput();
            final AIRequest req = new AIRequest();
            pr.request = req;
            final StringBuilder full = new StringBuilder();
            final StringBuilder fullReasoning = new StringBuilder();
            final boolean showReasoning = model.shouldShowReasoning();

            // 流式输出：内存缓冲区 + 30ms 定时追帧打字机效果
            final Handler streamHandler = new Handler();
            final boolean[] dbPending = {false};
            final int FRAME_MS = 30;
            final int CHARS_PER_FRAME = 6;

            final StringBuilder shownContent = new StringBuilder();
            final StringBuilder shownReasoning = new StringBuilder();
            final boolean[] typingRunning = {false};

            final Runnable typewriterRunnable = new Runnable() {
                @Override
                public void run() {
                    boolean needMore = false;
                    try {
                        // 从缓冲区匀速取出内容追加到已显示文本
                        int contentRemaining = full.length() - shownContent.length();
                        if (contentRemaining > 0) {
                            int take = Math.min(contentRemaining, CHARS_PER_FRAME);
                            shownContent.append(full, shownContent.length(), shownContent.length() + take);
                            needMore = contentRemaining > take;
                        }

                        if (showReasoning) {
                            int reasoningRemaining = fullReasoning.length() - shownReasoning.length();
                            if (reasoningRemaining > 0) {
                                int take = Math.min(reasoningRemaining, CHARS_PER_FRAME);
                                shownReasoning.append(fullReasoning, shownReasoning.length(), shownReasoning.length() + take);
                                needMore = needMore || reasoningRemaining > take;
                            }
                        }

                        // 刷新最后一条消息
                        if (mCurrentConversationId == convId) {
                            if (mUserScrolled) {
                                // 用户手动滑动中，只更新模型和可见项，不重绘全部
                                mChatAdapter.updateLastItem(shownContent.toString(),
                                        showReasoning ? shownReasoning.toString() : null, mListMessages);
                            } else {
                                // 自动跟滚时，直接更新模型并通知 ListView；ALWAYS_SCROLL 会滚到真实底部
                                ChatMessage last = mChatAdapter.getLastItem();
                                if (last != null) {
                                    last.content = shownContent.toString();
                                    if (showReasoning) {
                                        last.reasoning = shownReasoning.toString();
                                    }
                                }
                                mChatAdapter.notifyDataSetChanged();
                                // TranscriptMode 会自动滚动，不需要手动 setSelection
                            }
                        }
                    } catch (Throwable t) {
                        // ignore
                    }

                    if (needMore) {
                        streamHandler.postDelayed(this, FRAME_MS);
                    } else {
                        typingRunning[0] = false;
                    }
                }
            };

            final Runnable dbRefreshRunnable = new Runnable() {
                @Override
                public void run() {
                    dbPending[0] = false;
                    try {
                        mDb.updateMessage(pr.aiMessageId, full.toString(),
                                showReasoning ? fullReasoning.toString() : "");
                    } catch (Throwable t) {
                        // ignore
                    }
                }
            };

            req.execute(model, history, text, stream, new AIRequest.AICallback() {
                @Override
                public void onStart() {
                    // 进入流式状态
                }

                @Override
                public void onDelta(String delta) {
                    if (delta == null || delta.length() == 0) return;
                    full.append(delta);
                    try {
                        // 启动打字机定时器
                        if (!typingRunning[0]) {
                            typingRunning[0] = true;
                            streamHandler.removeCallbacks(typewriterRunnable);
                            streamHandler.postDelayed(typewriterRunnable, FRAME_MS);
                        }
                        // 数据库写入批处理
                        if (!dbPending[0]) {
                            dbPending[0] = true;
                            streamHandler.removeCallbacks(dbRefreshRunnable);
                            streamHandler.postDelayed(dbRefreshRunnable, 200);
                        }
                    } catch (Throwable t) {
                        // ignore
                    }
                }

                @Override
                public void onReasoningDelta(String delta) {
                    if (!showReasoning || delta == null || delta.length() == 0) return;
                    fullReasoning.append(delta);
                    try {
                        if (!typingRunning[0]) {
                            typingRunning[0] = true;
                            streamHandler.removeCallbacks(typewriterRunnable);
                            streamHandler.postDelayed(typewriterRunnable, FRAME_MS);
                        }
                        if (!dbPending[0]) {
                            dbPending[0] = true;
                            streamHandler.removeCallbacks(dbRefreshRunnable);
                            streamHandler.postDelayed(dbRefreshRunnable, 200);
                        }
                    } catch (Throwable t) {
                        // ignore
                    }
                }

                @Override
                public void onComplete(String fullResponse, String fullReasoningArg) {
                    // 取消待处理的批处理，立即执行最终刷新
                    try {
                        streamHandler.removeCallbacks(typewriterRunnable);
                        streamHandler.removeCallbacks(dbRefreshRunnable);
                    } catch (Throwable t) {
                        // ignore
                    }
                    String resp = fullResponse;
                    if (resp == null || resp.length() == 0) resp = full.toString();
                    String reasoning = fullReasoningArg;
                    if (reasoning == null || reasoning.length() == 0) reasoning = fullReasoning.toString();
                    if (!showReasoning) {
                        reasoning = "";
                    }
                    try {
                        mDb.updateMessage(pr.aiMessageId, resp, reasoning);
                        if (mCurrentConversationId == convId) {
                            // 完成时一次性显示全部剩余内容
                            mChatAdapter.updateLastItem(resp, reasoning, mListMessages);
                            mChatAdapter.notifyDataSetChanged();
                            // TranscriptMode 会自动滚到真实底部
                        }
                    } catch (Throwable t) {
                        // ignore
                    }
                    // 非流式模式下，如果还有思考内容，模拟流式展开效果
                    if (!stream && showReasoning && reasoning.length() > 0 && mCurrentConversationId == convId) {
                        animateReasoningStream(reasoning);
                    }
                    mPendingRequests.remove(convId);
                    if (mCurrentConversationId == convId) {
                        updateSendButton();
                    }
                    refreshConversationList();
                }

                @Override
                public void onError(String error) {
                    // 停止打字机，避免错误消息被覆盖或延迟
                    try {
                        streamHandler.removeCallbacks(typewriterRunnable);
                        streamHandler.removeCallbacks(dbRefreshRunnable);
                    } catch (Throwable t) {
                        // ignore
                    }
                    String errMsg;
                    try {
                        errMsg = getString(R.string.msg_error, error == null ? "unknown" : error);
                    } catch (Throwable t) {
                        errMsg = "请求失败：" + (error == null ? "unknown" : error);
                    }
                    try {
                        mDb.updateMessage(pr.aiMessageId, errMsg, "");
                        if (mCurrentConversationId == convId) {
                            if (pr.placeholderShown) {
                                mChatAdapter.updateLastItem(errMsg, null, mListMessages);
                            } else {
                                mChatAdapter.addItem(new ChatMessage("assistant", errMsg));
                            }
                            mChatAdapter.notifyDataSetChanged();
                            // TranscriptMode 会自动滚到真实底部
                        }
                    } catch (Throwable t) {
                        // ignore
                    }
                    mPendingRequests.remove(convId);
                    if (mCurrentConversationId == convId) {
                        updateSendButton();
                    }
                    refreshConversationList();
                }
            });
        } catch (Throwable t) {
            toast(getString(R.string.msg_send_failed, safeMsg(t)));
            mPendingRequests.remove(mCurrentConversationId);
            updateSendButton();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS) {
            // 设置中可能修改了语言，重新应用并刷新界面
            try {
                String newLang = mSettings.getLanguage();
                applyLanguage(newLang);
                if (resultCode == RESULT_OK) {
                    // 语言变更后重启 Activity 以确保所有资源重新加载
                    try {
                        Intent intent = getIntent();
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(intent);
                    } catch (Throwable t) {
                        toast(safeMsg(t));
                    }
                    finish();
                    return;
                }
            } catch (Throwable t) {
                // ignore
            }
            updateModelDisplay();
            refreshConversationList();
        }
    }

    @Override
    protected void onDestroy() {
        for (PendingRequest pr : mPendingRequests.values()) {
            if (pr != null && pr.request != null) {
                try {
                    pr.request.cancel();
                } catch (Throwable t) {
                    // ignore
                }
            }
        }
        mPendingRequests.clear();
        super.onDestroy();
    }

    /**
     * 非流式模式下，把完整思考内容切成小段逐字显示，营造流式输出效果。
     * 思考内容默认展开。
     */
    private void animateReasoningStream(final String reasoning) {
        if (reasoning == null || reasoning.length() == 0) return;
        final Handler handler = new Handler(Looper.getMainLooper());
        final ChatMessage last = mChatAdapter.getLastItem();
        if (last == null) return;
        // 先清空，再逐段追加
        last.reasoning = "";
        final int len = reasoning.length();
        final Runnable runner = new Runnable() {
            int pos = 0;
            @Override
            public void run() {
                try {
                    if (pos >= len) {
                        last.reasoning = reasoning;
                        mDb.updateMessage(last.id, last.content, reasoning);
                        mChatAdapter.notifyDataSetChanged();
                        // TranscriptMode 会自动滚到真实底部
                        return;
                    }
                    // 每步 2~4 个字符，既像打字又不是太慢
                    int step = Math.min(4, len - pos);
                    pos += step;
                    last.reasoning = reasoning.substring(0, pos);
                    mChatAdapter.notifyDataSetChanged();
                    // TranscriptMode 会自动滚到真实底部
                    handler.postDelayed(this, 16);
                } catch (Throwable t) {
                    // 异常时直接显示完整内容
                    last.reasoning = reasoning;
                    try {
                        mDb.updateMessage(last.id, last.content, reasoning);
                        mChatAdapter.notifyDataSetChanged();
                    } catch (Throwable t2) {}
                }
            }
        };
        handler.post(runner);
    }

    /**
     * 兼容 Android 2.3 的剪贴板复制：API 11+ 用 content.ClipboardManager，低版本用 text.ClipboardManager。
     */
    @SuppressWarnings("deprecation")
    private void copyToClipboard(String text) {
        if (Build.VERSION.SDK_INT >= 11) {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("text", text));
                return;
            } catch (Throwable t) {
                // fallthrough
            }
        }
        try {
            android.text.ClipboardManager cm =
                    (android.text.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setText(text);
        } catch (Throwable t) {
            // ignore
        }
    }
}
