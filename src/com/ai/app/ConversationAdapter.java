package com.oac.nazhiyazi.op;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话列表适配器（侧边栏）。兼容 Android 2.3。
 *
 * 性能优化：
 * - 使用 ViewHolder 缓存 findViewById
 * - 删除按钮 listener 使用 tag 绑定，避免每次 getView 都创建匿名类
 * - 减少 setBackgroundColor / setTextColor 的重复调用：只有在状态变化时才设置
 */
public class ConversationAdapter extends BaseAdapter {

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final List<Conversation> mItems = new ArrayList<Conversation>();
    private long mActiveId = -1;
    private OnConversationActionListener mListener;

    public interface OnConversationActionListener {
        void onDelete(Conversation conv);
    }

    public ConversationAdapter(Context context) {
        mContext = context;
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    public void setListener(OnConversationActionListener listener) {
        mListener = listener;
    }

    public void setItems(List<Conversation> items) {
        mItems.clear();
        if (items != null) mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void setActiveId(long id) {
        mActiveId = id;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return mItems.size();
    }

    @Override
    public Conversation getItem(int position) {
        if (position < 0 || position >= mItems.size()) return null;
        return mItems.get(position);
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= mItems.size()) return -1;
        return mItems.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final Conversation conv = getItem(position);
        if (conv == null) {
            if (convertView == null) {
                convertView = new View(mContext);
            }
            return convertView;
        }
        ViewHolder holder;
        if (convertView == null) {
            try {
                convertView = mInflater.inflate(R.layout.item_conversation, parent, false);
            } catch (Throwable t) {
                convertView = new View(mContext);
                return convertView;
            }
            if (convertView == null) {
                convertView = new View(mContext);
                return convertView;
            }
            holder = new ViewHolder();
            holder.tvTitle = (TextView) convertView.findViewById(R.id.tv_conv_title);
            holder.tvPreview = (TextView) convertView.findViewById(R.id.tv_conv_preview);
            holder.btnDelete = (Button) convertView.findViewById(R.id.btn_conv_delete);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
            if (holder == null) {
                holder = new ViewHolder();
                holder.tvTitle = (TextView) convertView.findViewById(R.id.tv_conv_title);
                holder.tvPreview = (TextView) convertView.findViewById(R.id.tv_conv_preview);
                holder.btnDelete = (Button) convertView.findViewById(R.id.btn_conv_delete);
                convertView.setTag(holder);
            }
        }

        try {
            if (holder.tvTitle != null) {
                holder.tvTitle.setText(conv.title == null ? "" : conv.title);
            }
            if (holder.tvPreview != null) {
                holder.tvPreview.setText(conv.preview == null ? "" : conv.preview);
            }

            // 选中状态：只在需要变化时才设置，减少重绘
            boolean active = conv.id == mActiveId;
            Integer lastState = (Integer) convertView.getTag(R.id.tag_conv_active);
            if (lastState == null || lastState.intValue() != (active ? 1 : 0)) {
                convertView.setTag(R.id.tag_conv_active, active ? 1 : 0);
                try {
                    if (active) {
                        convertView.setBackgroundColor(0xFF1A1A1A);
                        if (holder.tvTitle != null) holder.tvTitle.setTextColor(0xFFFFFFFF);
                    } else {
                        convertView.setBackgroundColor(0xFF2C2C2C);
                        if (holder.tvTitle != null) holder.tvTitle.setTextColor(0xFFE0E0E0);
                    }
                } catch (Throwable t) {
                    // ignore
                }
            }

            // 删除按钮：用 tag 绑定当前 conversation，listener 只设一次
            if (holder.btnDelete != null) {
                holder.btnDelete.setTag(conv);
                if (holder.btnDelete.getTag(R.id.tag_listener_set) == null) {
                    holder.btnDelete.setTag(R.id.tag_listener_set, Boolean.TRUE);
                    holder.btnDelete.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try {
                                Conversation c = (Conversation) v.getTag();
                                if (c != null && mListener != null) mListener.onDelete(c);
                            } catch (Throwable t) {
                                // ignore
                            }
                        }
                    });
                }
            }
        } catch (Throwable t) {
            // ignore
        }

        return convertView;
    }

    private static class ViewHolder {
        TextView tvTitle;
        TextView tvPreview;
        Button btnDelete;
    }
}
