package com.oac.nazhiyazi.op;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 聊天消息列表适配器。兼容 Android 2.3。
 *
 * 性能优化：
 * - 使用 ViewHolder 缓存 findViewById，避免 getView 重复查找
 * - updateLastItem 默认只刷新最后一条可见项，流式时不触发全量 notifyDataSetChanged
 * - 提供 forceNotify 方法供需要全量刷新时调用
 *
 * 思考内容：
 * - assistant 消息如果有 reasoning 字段，显示可折叠/展开的思考区域
 * - 用 mExpandedIds 记录展开的消息 id，避免 convertView 复用导致状态错乱
 * - 点击 header 切换展开/收起
 */
public class ChatAdapter extends BaseAdapter {

    private static final int TYPE_USER = 0;
    private static final int TYPE_AI = 1;

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final List<ChatMessage> mItems = new ArrayList<ChatMessage>();
    /** 记录展开思考内容的消息 id（默认展开） */
    private final Set<Long> mExpandedIds = new HashSet<Long>();

    public ChatAdapter(Context context) {
        mContext = context;
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    public void setItems(List<ChatMessage> items) {
        mItems.clear();
        if (items != null) mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void addItem(ChatMessage m) {
        if (m == null) return;
        mItems.add(m);
        notifyDataSetChanged();
    }

    public void updateLastItemContent(String content) {
        updateLastItem(content, null);
    }

    /**
     * 更新最后一条消息的内容/思考过程。
     * 若传入了 listView，则直接找到最后一条可见项进行局部刷新，不触发全量 notifyDataSetChanged。
     */
    public void updateLastItem(String content, String reasoning) {
        updateLastItem(content, reasoning, null);
    }

    public void updateLastItem(String content, String reasoning, ListView listView) {
        if (mItems.isEmpty()) return;
        try {
            ChatMessage last = mItems.get(mItems.size() - 1);
            if (content != null) last.content = content;
            if (reasoning != null) last.reasoning = reasoning;

            if (listView != null) {
                // 直接刷新最后一条可见视图，避免 notifyDataSetChanged 重绘全部
                int lastPos = mItems.size() - 1;
                int firstVisible = listView.getFirstVisiblePosition();
                int lastVisible = listView.getLastVisiblePosition();
                if (lastPos >= firstVisible && lastPos <= lastVisible) {
                    View child = listView.getChildAt(lastPos - firstVisible);
                    if (child != null) {
                        bindView(child, last, lastPos);
                    }
                }
            } else {
                notifyDataSetChanged();
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    public void removeLastItem() {
        if (!mItems.isEmpty()) {
            try {
                mItems.remove(mItems.size() - 1);
                notifyDataSetChanged();
            } catch (Throwable t) {
                // ignore
            }
        }
    }

    public List<ChatMessage> getItems() {
        return mItems;
    }

    public ChatMessage getLastItem() {
        return mItems.isEmpty() ? null : mItems.get(mItems.size() - 1);
    }

    @Override
    public int getCount() {
        return mItems.size();
    }

    @Override
    public ChatMessage getItem(int position) {
        if (position < 0 || position >= mItems.size()) return null;
        return mItems.get(position);
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= mItems.size()) return -1;
        return mItems.get(position).id;
    }

    @Override
    public int getItemViewType(int position) {
        if (position < 0 || position >= mItems.size()) return TYPE_AI;
        ChatMessage m = mItems.get(position);
        return (m != null && m.isUser()) ? TYPE_USER : TYPE_AI;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final ChatMessage m = getItem(position);
        if (m == null) {
            if (convertView == null) {
                convertView = new View(mContext);
            }
            return convertView;
        }
        int type = getItemViewType(position);
        ViewHolder holder;
        if (convertView == null) {
            int layoutId = (type == TYPE_USER) ? R.layout.item_message_user : R.layout.item_message_ai;
            try {
                convertView = mInflater.inflate(layoutId, parent, false);
            } catch (Throwable t) {
                convertView = new View(mContext);
                return convertView;
            }
            if (convertView == null) {
                convertView = new View(mContext);
                return convertView;
            }
            holder = new ViewHolder();
            holder.tvRole = (TextView) convertView.findViewById(R.id.tv_role);
            holder.tvContent = (TextView) convertView.findViewById(R.id.tv_content);
            if (type == TYPE_AI) {
                holder.reasoningContainer = convertView.findViewById(R.id.reasoning_container);
                holder.tvReasoningHeader = (TextView) convertView.findViewById(R.id.tv_reasoning_header);
                holder.tvReasoningContent = (TextView) convertView.findViewById(R.id.tv_reasoning_content);
            }
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
            if (holder == null) {
                // 兜底：tag 异常时重新绑定
                holder = new ViewHolder();
                holder.tvRole = (TextView) convertView.findViewById(R.id.tv_role);
                holder.tvContent = (TextView) convertView.findViewById(R.id.tv_content);
                if (type == TYPE_AI) {
                    holder.reasoningContainer = convertView.findViewById(R.id.reasoning_container);
                    holder.tvReasoningHeader = (TextView) convertView.findViewById(R.id.tv_reasoning_header);
                    holder.tvReasoningContent = (TextView) convertView.findViewById(R.id.tv_reasoning_content);
                }
                convertView.setTag(holder);
            }
        }
        bindView(convertView, m, position);
        return convertView;
    }

    private void bindView(View convertView, final ChatMessage m, int position) {
        if (convertView == null || m == null) return;
        ViewHolder holder = (ViewHolder) convertView.getTag();
        if (holder == null) return;
        int type = getItemViewType(position);

        try {
            int roleResId;
            if (m.isUser()) {
                roleResId = R.string.role_user;
            } else if (m.isAssistant()) {
                roleResId = R.string.role_assistant;
            } else {
                roleResId = R.string.role_system;
            }
            if (holder.tvRole != null) {
                try {
                    holder.tvRole.setText(mContext.getString(roleResId));
                } catch (Throwable t) {
                    // ignore
                }
            }
            if (holder.tvContent != null) {
                holder.tvContent.setText(m.content == null ? "" : m.content);
            }

            if (type == TYPE_AI) {
                bindReasoning(holder, m);
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    private void bindReasoning(ViewHolder holder, final ChatMessage m) {
        if (holder == null) return;
        View container = holder.reasoningContainer;
        TextView tvHeader = holder.tvReasoningHeader;
        TextView tvContent = holder.tvReasoningContent;

        boolean hasReasoning = m.hasReasoning();
        if (container == null) return;

        if (!hasReasoning) {
            container.setVisibility(View.GONE);
            return;
        }

        container.setVisibility(View.VISIBLE);
        // 默认展开：已有的展开记录优先；没有记录则默认展开（id<=0 表示正在流式生成的新消息，也展开）
        boolean expanded = mExpandedIds.contains(m.id) || !mExpandedIds.contains(-m.id);
        if (tvContent != null) {
            tvContent.setText(m.reasoning);
            tvContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (tvHeader != null) {
            tvHeader.setText(expanded ? mContext.getString(R.string.reasoning_collapse)
                                      : mContext.getString(R.string.reasoning_expand));
            tvHeader.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        if (mExpandedIds.contains(m.id)) {
                            mExpandedIds.remove(m.id);
                            mExpandedIds.add(-m.id);
                        } else {
                            mExpandedIds.add(m.id);
                            mExpandedIds.remove(-m.id);
                        }
                        notifyDataSetChanged();
                    } catch (Throwable t) {
                        // ignore
                    }
                }
            });
        }
    }

    private static class ViewHolder {
        TextView tvRole;
        TextView tvContent;
        View reasoningContainer;
        TextView tvReasoningHeader;
        TextView tvReasoningContent;
    }
}
