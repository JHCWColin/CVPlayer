package com.inkbox.videoplayer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 播放列表界面：展示所选文件夹的树形结构，点击视频进入全屏播放。 */
public class MainActivity extends Activity {

    private static final int REQUEST_PICK_FOLDER = 1;
    private static final String PREFS = "library";
    private static final String KEY_TREE_URI = "tree_uri";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 被折叠的文件夹相对路径，默认全部展开。 */
    private final Set<String> collapsed = new HashSet<>();
    private final List<MediaNode> roots = new ArrayList<>();
    /** 播放队列：按树形顺序排列的全部视频。 */
    private final List<MediaNode> videos = new ArrayList<>();

    private PlaylistAdapter adapter;
    private TextView statusView;
    private Uri treeUri;
    /** 每次扫描递增，用于丢弃过期的扫描结果。 */
    private int scanSerial;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        ListView list = findViewById(R.id.list);
        adapter = new PlaylistAdapter(this, collapsed);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onRowClicked(adapter.getItem(position)));

        findViewById(R.id.btn_import).setOnClickListener(v -> pickFolder());
        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_refresh).setOnClickListener(v -> {
            if (treeUri != null) {
                scan(treeUri);
            }
        });

        treeUri = loadTreeUri();
        if (treeUri != null) {
            scan(treeUri);
        } else {
            statusView.setText(R.string.empty_hint);
            statusView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_FOLDER || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            // 持久化授权，重启应用后仍能读取该文件夹
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // 部分文件管理器不提供持久授权，本次运行内仍可读取
        }
        if (treeUri != null && !treeUri.equals(uri)) {
            try {
                getContentResolver().releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // 旧授权已不存在，忽略
            }
        }
        treeUri = uri;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TREE_URI, uri.toString()).apply();
        scan(uri);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_FOLDER);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_folder_picker, Toast.LENGTH_LONG).show();
        }
    }

    /** 读取上次导入的文件夹；若系统已撤销授权则返回 null。 */
    private Uri loadTreeUri() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE_URI, null);
        if (saved == null) {
            return null;
        }
        Uri uri = Uri.parse(saved);
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(uri) && permission.isReadPermission()) {
                return uri;
            }
        }
        return null;
    }

    private void scan(Uri uri) {
        final int serial = ++scanSerial;
        statusView.setText(R.string.scanning);
        statusView.setVisibility(View.VISIBLE);
        final Context appContext = getApplicationContext();
        executor.execute(() -> {
            LibraryScanner.Library library = LibraryScanner.scan(appContext, uri);
            mainHandler.post(() -> {
                if (serial != scanSerial || isFinishing()) {
                    return;
                }
                if (library == null) {
                    statusView.setText(R.string.scan_failed);
                    statusView.setVisibility(View.VISIBLE);
                } else {
                    onLibraryScanned(library);
                }
            });
        });
    }

    private void onLibraryScanned(LibraryScanner.Library library) {
        roots.clear();
        roots.addAll(library.roots);
        videos.clear();
        videos.addAll(library.videos);
        refreshRows();

        if (videos.isEmpty()) {
            statusView.setText(R.string.no_videos_found);
            statusView.setVisibility(View.VISIBLE);
        } else {
            statusView.setVisibility(View.GONE);
            Toast.makeText(this, getString(R.string.video_count, videos.size()), Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshRows() {
        List<MediaNode> visible = new ArrayList<>();
        MediaNode.flatten(roots, collapsed, visible);
        adapter.setItems(visible);
    }

    private void onRowClicked(MediaNode node) {
        if (node.isFolder) {
            // 点击文件夹：折叠或展开
            if (!collapsed.remove(node.key)) {
                collapsed.add(node.key);
            }
            refreshRows();
            return;
        }

        ArrayList<String> uris = new ArrayList<>(videos.size());
        ArrayList<String> titles = new ArrayList<>(videos.size());
        for (MediaNode video : videos) {
            uris.add(video.uri.toString());
            titles.add(video.title());
        }
        Intent intent = new Intent(this, PlayerActivity.class)
                .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, uris)
                .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, titles)
                .putExtra(PlayerActivity.EXTRA_INDEX, node.queueIndex);
        startActivity(intent);
        overridePendingTransition(0, 0); // 墨水屏不需要切换动画
    }
}
