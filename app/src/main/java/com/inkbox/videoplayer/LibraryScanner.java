package com.inkbox.videoplayer;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 递归扫描用户选择的文件夹，生成与目录结构一致的播放列表。 */
final class LibraryScanner {

    /** 常见的 Android 硬件解码容器。 */
    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4", "m4v", "mov", "3gp", "3g2", "mkv", "webm"));

    /** 文件夹排在文件前面，同类之间按自然顺序排列。 */
    private static final Comparator<DocumentFile> ORDER = (a, b) -> {
        if (a.isDirectory() != b.isDirectory()) {
            return a.isDirectory() ? -1 : 1;
        }
        return NaturalOrder.INSTANCE.compare(nameOf(a), nameOf(b));
    };

    /** 扫描结果：顶层节点，以及按树形顺序排列的全部视频（即播放队列）。 */
    static final class Library {
        final List<MediaNode> roots;
        final List<MediaNode> videos;

        Library(List<MediaNode> roots, List<MediaNode> videos) {
            this.roots = roots;
            this.videos = videos;
        }
    }

    private LibraryScanner() {
    }

    /** 扫描失败（例如授权丢失）时返回 null。 */
    static Library scan(Context context, Uri treeUri) {
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
            if (root == null || !root.isDirectory()) {
                return null;
            }
            List<MediaNode> videos = new ArrayList<>();
            List<MediaNode> roots = scanDirectory(root, "", 0, videos);
            return new Library(roots, videos);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<MediaNode> scanDirectory(DocumentFile dir, String parentKey, int depth,
                                                 List<MediaNode> videos) {
        DocumentFile[] files = dir.listFiles();
        Arrays.sort(files, ORDER);

        List<MediaNode> nodes = new ArrayList<>();
        for (DocumentFile file : files) {
            String name = nameOf(file);
            if (name.isEmpty() || name.startsWith(".")) {
                continue; // 忽略隐藏文件
            }
            String key = parentKey + "/" + name;
            if (file.isDirectory()) {
                List<MediaNode> children = scanDirectory(file, key, depth + 1, videos);
                if (children.isEmpty()) {
                    continue; // 不含视频的文件夹不显示
                }
                MediaNode folder = new MediaNode(name, key, null, true, depth);
                folder.children.addAll(children);
                nodes.add(folder);
            } else if (file.isFile() && isVideo(name)) {
                MediaNode video = new MediaNode(name, key, file.getUri(), false, depth);
                video.queueIndex = videos.size();
                videos.add(video);
                nodes.add(video);
            }
        }
        return nodes;
    }

    private static boolean isVideo(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && VIDEO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String nameOf(DocumentFile file) {
        String name = file.getName();
        return name == null ? "" : name;
    }
}
