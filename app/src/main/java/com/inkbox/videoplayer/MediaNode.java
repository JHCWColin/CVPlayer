package com.inkbox.videoplayer;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 播放列表中的一个节点：文件夹或视频文件。 */
final class MediaNode {

    final String name;
    /** 相对路径，用于记住文件夹是否被折叠。 */
    final String key;
    /** 仅视频有值。 */
    final Uri uri;
    final boolean isFolder;
    /** 层级深度，顶层为 0，用于缩进显示。 */
    final int depth;
    final List<MediaNode> children = new ArrayList<>();
    /** 仅视频：在播放队列中的位置。 */
    int queueIndex = -1;

    MediaNode(String name, String key, Uri uri, boolean isFolder, int depth) {
        this.name = name;
        this.key = key;
        this.uri = uri;
        this.isFolder = isFolder;
        this.depth = depth;
    }

    /** 视频标题：去掉扩展名。 */
    String title() {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 按树形顺序展开可见节点，被折叠的文件夹不展开子节点。 */
    static void flatten(List<MediaNode> nodes, Set<String> collapsed, List<MediaNode> out) {
        for (MediaNode node : nodes) {
            out.add(node);
            if (node.isFolder && !collapsed.contains(node.key)) {
                flatten(node.children, collapsed, out);
            }
        }
    }
}
