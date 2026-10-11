package com.inkbox.videoplayer;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 播放列表行：按层级缩进，文件夹可点击折叠/展开。 */
final class PlaylistAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final Set<String> collapsed;
    private final int basePadding;
    private final int indentStep;
    private final List<MediaNode> items = new ArrayList<>();

    PlaylistAdapter(Context context, Set<String> collapsed) {
        this.inflater = LayoutInflater.from(context);
        this.collapsed = collapsed;
        float density = context.getResources().getDisplayMetrics().density;
        this.basePadding = Math.round(16 * density);
        this.indentStep = Math.round(24 * density);
    }

    void setItems(List<MediaNode> nodes) {
        items.clear();
        items.addAll(nodes);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public MediaNode getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView != null
                ? convertView
                : inflater.inflate(R.layout.item_playlist, parent, false);
        MediaNode node = items.get(position);
        ImageView icon = row.findViewById(R.id.icon);
        ImageView arrow = row.findViewById(R.id.arrow);
        TextView name = row.findViewById(R.id.name);

        row.setPaddingRelative(basePadding + node.depth * indentStep,
                row.getPaddingTop(), row.getPaddingEnd(), row.getPaddingBottom());

        if (node.isFolder) {
            boolean isCollapsed = collapsed.contains(node.key);
            icon.setImageResource(R.drawable.ic_folder);
            arrow.setVisibility(View.VISIBLE);
            arrow.setImageResource(isCollapsed ? R.drawable.ic_chevron_right : R.drawable.ic_expand_more);
            name.setText(node.name);
            name.setTypeface(null, Typeface.BOLD);
        } else {
            icon.setImageResource(R.drawable.ic_movie);
            arrow.setVisibility(View.GONE);
            name.setText(node.title());
            name.setTypeface(null, Typeface.NORMAL);
        }
        return row;
    }
}
