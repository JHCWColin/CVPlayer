package com.inkbox.videoplayer;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.util.DisplayMetrics;
import android.widget.PopupMenu;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * 全屏播放界面（墨水屏版）。
 * 音频由 MediaPlayer 原样播放（码率不变）；画面由后台抽帧显示，
 * 可选择固定间隔或按视频原始帧率刷新。
 */
public class PlayerActivity extends Activity {

    private static final String TAG = "PlayerActivity";
    private static final int[] ENCOURAGEMENT_TRACKS = {
            R.raw.encouragement_01, R.raw.encouragement_02, R.raw.encouragement_03,
            R.raw.encouragement_04, R.raw.encouragement_05, R.raw.encouragement_06,
            R.raw.encouragement_07, R.raw.encouragement_08, R.raw.encouragement_09,
            R.raw.encouragement_10, R.raw.encouragement_11, R.raw.encouragement_12,
            R.raw.encouragement_13, R.raw.encouragement_14, R.raw.encouragement_15,
            R.raw.encouragement_16, R.raw.encouragement_17, R.raw.encouragement_18,
            R.raw.encouragement_19, R.raw.encouragement_20, R.raw.encouragement_21,
            R.raw.encouragement_22
    };

    static final String EXTRA_URIS = "uris";
    static final String EXTRA_TITLES = "titles";
    static final String EXTRA_INDEX = "index";

    /** 控制栏无操作后自动隐藏的时间（毫秒）；设为 0 则不自动隐藏。 */
    private static final long CONTROLS_HIDE_DELAY_MS = 4000;
    private static final int SEEK_STEP_MS = 10000;
    /** 上一个按钮：播放超过该时长则回到本视频开头，否则切到上一个视频。 */
    private static final int RESTART_THRESHOLD_MS = 3000;
    /** 定时器略晚于画面刷新边界触发，确保 getCurrentPosition() 已越过边界。 */
    private static final long TICK_SLACK_MS = 30;
    private static final long POSITION_SAVE_INTERVAL_MS = 1000;
    private static final int MAX_FRAME_WIDTH = 1280;
    private static final int MAX_FRAME_HEIGHT = 720;
    private static final int PROGRESS_UI_INTERVAL_MS = 1000;
    private static final float[] PLAYBACK_SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ArrayList<Uri> uris;
    private ArrayList<String> titles;
    private int index;

    private MediaPlayer player;
    /** player 已 prepare 完成且未出错。 */
    private boolean prepared;
    /** 当前视频已播放到结尾（且已是列表最后一个）。 */
    private boolean completed;
    private boolean encouragementTriggeredForCurrentVideo;
    private boolean encouragementPlaying;
    private boolean finishAfterEncouragement;
    private int[] selectedEncouragementTracks;
    private int nextEncouragementTrack;
    private MediaPlayer encouragementPlayer;
    private Runnable afterEncouragement;
    /** 用户正在拖动进度条。 */
    private boolean seeking;
    /** 当前画面所属的时间段序号，-1 表示需要强制刷新。 */
    private long shownSlot = -1;
    private long frameIntervalMs;
    private boolean originalFrameRate;
    private float playbackSpeed;
    private long lastPositionSaveTimeMs;
    private int lastProgressUiPositionMs = -PROGRESS_UI_INTERVAL_MS;

    private FrameSampler sampler;
    private AudioManager audioManager;

    private ImageView frameView;
    private View overlay;
    private TextView titleView;
    private TextView positionView;
    private TextView durationView;
    private SeekBar seekBar;
    private ImageButton playButton;
    private TextView playbackSpeedView;

    private final Runnable frameTick = () -> {
        refreshFrame();
        updateProgress();
        scheduleFrameTick();
    };

    private final Runnable hideOverlay = () -> setOverlayVisible(false);

    private final AudioManager.OnAudioFocusChangeListener focusListener = change ->
            handler.post(() -> {
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    pause();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        ArrayList<String> uriStrings = getIntent().getStringArrayListExtra(EXTRA_URIS);
        ArrayList<String> titleList = getIntent().getStringArrayListExtra(EXTRA_TITLES);
        if (uriStrings == null || titleList == null || uriStrings.isEmpty()) {
            finish();
            return;
        }
        uris = new ArrayList<>(uriStrings.size());
        for (String s : uriStrings) {
            uris.add(Uri.parse(s));
        }
        titles = new ArrayList<>(titleList);
        index = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_INDEX, 0), uris.size() - 1));

        bindViews();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float scale = Math.min(1f, Math.min(
                MAX_FRAME_WIDTH / (float) Math.max(1, metrics.widthPixels),
                MAX_FRAME_HEIGHT / (float) Math.max(1, metrics.heightPixels)));
        int frameWidth = Math.max(1, Math.round(metrics.widthPixels * scale));
        int frameHeight = Math.max(1, Math.round(metrics.heightPixels * scale));
        sampler = new FrameSampler(this, frameWidth, frameHeight,
                frame -> frameView.setImageBitmap(frame));
        frameIntervalMs = PlaybackPreferences.getFrameIntervalMs(this);
        originalFrameRate = frameIntervalMs == PlaybackPreferences.ORIGINAL_FRAME_RATE;
        playbackSpeed = PlaybackPreferences.getPlaybackSpeed(this);
        if (!isSupportedPlaybackSpeed(playbackSpeed)) {
            playbackSpeed = PlaybackPreferences.DEFAULT_PLAYBACK_SPEED;
        }
        updatePlaybackSpeedButton();
        if (originalFrameRate) {
            frameIntervalMs = 1000;
        }
        playIndex(index);
    }

    private void bindViews() {
        frameView = findViewById(R.id.frame);
        overlay = findViewById(R.id.overlay);
        titleView = findViewById(R.id.title);
        positionView = findViewById(R.id.position);
        durationView = findViewById(R.id.duration);
        seekBar = findViewById(R.id.seek_bar);
        playButton = findViewById(R.id.play);
        playbackSpeedView = findViewById(R.id.playback_speed);

        // 点击画面切换控制栏显示
        findViewById(R.id.root).setOnClickListener(v ->
                setOverlayVisible(overlay.getVisibility() != View.VISIBLE));
        findViewById(R.id.back).setOnClickListener(v -> requestExit());
        findViewById(R.id.previous).setOnClickListener(v -> playPrevious());
        findViewById(R.id.rewind).setOnClickListener(v -> seekBy(-SEEK_STEP_MS));
        playButton.setOnClickListener(v -> togglePlayPause());
        findViewById(R.id.forward).setOnClickListener(v -> seekBy(SEEK_STEP_MS));
        findViewById(R.id.next).setOnClickListener(v -> playNext());
        playbackSpeedView.setOnClickListener(this::showPlaybackSpeedMenu);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    positionView.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seeking = true;
                handler.removeCallbacks(hideOverlay);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                seekTo(bar.getProgress());
            }
        });
    }

    // ---------- 播放控制 ----------

    private boolean isPlaying() {
        return prepared && player.isPlaying();
    }

    private void playIndex(int position) {
        saveCurrentPosition(true);
        index = position;
        encouragementTriggeredForCurrentVideo = false;
        lastPositionSaveTimeMs = 0;
        lastProgressUiPositionMs = -PROGRESS_UI_INTERVAL_MS;
        releasePlayer();
        completed = false;
        shownSlot = -1;

        titleView.setText(titles.get(index));
        frameView.setImageBitmap(null); // 切换时清空旧画面
        positionView.setText(formatTime(0));
        durationView.setText(formatTime(0));
        seekBar.setProgress(0);
        updatePlayButton();

        MediaPlayer mp = new MediaPlayer();
        player = mp;
        sampler.open(uris.get(index), framesPerSecond -> {
            if (mp == player && originalFrameRate) {
                frameIntervalMs = Math.max(1, Math.round(1000f / framesPerSecond));
                shownSlot = -1;
                if (prepared) {
                    refreshFrame();
                    scheduleFrameTick();
                }
            }
        });
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            mp.setDataSource(this, uris.get(index));
            // 监听器里校验 p == player，忽略已被替换或释放的旧播放器的回调
            mp.setOnPreparedListener(p -> {
                if (p == player) {
                    onPrepared();
                }
            });
            mp.setOnSeekCompleteListener(p -> {
                if (p == player) {
                    onSeekComplete();
                }
            });
            mp.setOnCompletionListener(p -> {
                if (p == player) {
                    onCompletion();
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (p == player) {
                    prepared = false;
                    handler.removeCallbacks(frameTick);
                    handler.post(() -> {
                        if (p == player) {
                            onPlaybackError();
                        }
                    });
                }
                return true;
            });
            mp.prepareAsync();
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException e) {
            prepared = false;
            handler.post(this::onPlaybackError);
        }
    }

    private void onPrepared() {
        prepared = true;
        int duration = Math.max(0, player.getDuration());
        seekBar.setMax(duration);
        durationView.setText(formatTime(duration));
        long savedPosition = PlaybackPreferences.getPositionMs(this, uris.get(index));
        if (savedPosition >= duration) {
            PlaybackPreferences.clearPosition(this, uris.get(index));
            savedPosition = 0;
        }
        if (savedPosition > 0) {
            player.seekTo((int) savedPosition);
            seekBar.setProgress((int) savedPosition);
            positionView.setText(formatTime(savedPosition));
        }
        applyPlaybackSpeed();
        play();
    }

    private void showPlaybackSpeedMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(Menu.NONE, 0, Menu.NONE, getString(R.string.playback_speed))
                .setEnabled(false);
        for (int i = 0; i < PLAYBACK_SPEEDS.length; i++) {
            float speed = PLAYBACK_SPEEDS[i];
            String label = getString(R.string.playback_speed_option, formatPlaybackSpeed(speed));
            menu.getMenu().add(Menu.NONE, i + 1, Menu.NONE, label)
                    .setCheckable(true)
                    .setChecked(speed == playbackSpeed);
        }
        menu.setOnMenuItemClickListener(item -> {
            int option = item.getItemId() - 1;
            if (option < 0 || option >= PLAYBACK_SPEEDS.length) {
                return false;
            }
            playbackSpeed = PLAYBACK_SPEEDS[option];
            PlaybackPreferences.setPlaybackSpeed(this, playbackSpeed);
            updatePlaybackSpeedButton();
            applyPlaybackSpeed();
            return true;
        });
        menu.show();
    }

    private void updatePlaybackSpeedButton() {
        if (playbackSpeedView != null) {
            String label = formatPlaybackSpeed(playbackSpeed) + "×";
            playbackSpeedView.setText(label);
            playbackSpeedView.setContentDescription(getString(R.string.playback_speed_option,
                    formatPlaybackSpeed(playbackSpeed)));
        }
    }

    private void applyPlaybackSpeed() {
        if (!prepared || player == null) {
            return;
        }
        try {
            player.setPlaybackParams(new PlaybackParams()
                    .setSpeed(playbackSpeed)
                    .setPitch(1.0f));
        } catch (IllegalArgumentException | IllegalStateException e) {
            Log.w(TAG, "Unable to apply playback speed " + playbackSpeed, e);
            Toast.makeText(this, R.string.playback_speed_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean isSupportedPlaybackSpeed(float speed) {
        for (float option : PLAYBACK_SPEEDS) {
            if (speed == option) {
                return true;
            }
        }
        return false;
    }

    private static String formatPlaybackSpeed(float speed) {
        return speed == (int) speed
                ? String.format(Locale.US, "%.0f", speed)
                : String.format(Locale.US, "%.2f", speed).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void play() {
        if (!prepared) {
            return;
        }
        audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        player.start();
        frameView.setKeepScreenOn(true);
        shownSlot = -1;
        refreshFrame();
        updateProgress();
        scheduleFrameTick();
        updatePlayButton();
        scheduleHideOverlay();
    }

    private void pause() {
        if (!isPlaying()) {
            return;
        }
        player.pause();
        handler.removeCallbacks(frameTick);
        frameView.setKeepScreenOn(false);
        audioManager.abandonAudioFocus(focusListener);
        refreshFrame(); // 暂停时显示当前精确位置的画面
        updateProgress();
        saveCurrentPosition(true);
        updatePlayButton();
        setOverlayVisible(true);
    }

    private void togglePlayPause() {
        if (!prepared) {
            return;
        }
        if (completed) {
            playIndex(index); // 播放结束后按播放键，从头重播
            return;
        }
        if (player.isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    private void onCompletion() {
        handler.removeCallbacks(frameTick);
        completed = true;
        saveCurrentPosition(true);
        frameView.setKeepScreenOn(false);
        audioManager.abandonAudioFocus(focusListener);
        updatePlayButton();
        updateProgress();
        setOverlayVisible(true);
        triggerEncouragement(() -> {
            if (index + 1 < uris.size()) {
                playIndex(index + 1);
            }
        });
    }

    private void requestExit() {
        if (isFinishing()) {
            return;
        }
        if (encouragementPlaying) {
            finishAfterEncouragement = true;
            afterEncouragement = null;
            return;
        }
        if (prepared && !completed && !encouragementTriggeredForCurrentVideo) {
            pause();
            finishAfterEncouragement = true;
            triggerEncouragement(null);
            return;
        }
        finish();
    }

    private void triggerEncouragement(Runnable afterPlayback) {
        if (encouragementTriggeredForCurrentVideo || isFinishing()) {
            if (afterPlayback != null) {
                afterPlayback.run();
            }
            return;
        }
        encouragementTriggeredForCurrentVideo = true;
        encouragementPlaying = true;
        afterEncouragement = afterPlayback;

        ArrayList<Integer> shuffledTracks = new ArrayList<>(ENCOURAGEMENT_TRACKS.length);
        for (int track : ENCOURAGEMENT_TRACKS) {
            shuffledTracks.add(track);
        }
        Collections.shuffle(shuffledTracks);
        selectedEncouragementTracks = new int[]{shuffledTracks.get(0), shuffledTracks.get(1)};
        nextEncouragementTrack = 0;
        audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        playNextEncouragementTrack();
    }

    private void playNextEncouragementTrack() {
        if (isFinishing() || isDestroyed()) {
            releaseEncouragementPlayer();
            return;
        }
        if (selectedEncouragementTracks == null
                || nextEncouragementTrack >= selectedEncouragementTracks.length) {
            finishEncouragementPlayback();
            return;
        }

        releaseEncouragementPlayer();
        try {
            encouragementPlayer = MediaPlayer.create(this,
                    selectedEncouragementTracks[nextEncouragementTrack++]);
            if (encouragementPlayer == null) {
                Log.e(TAG, "Unable to create encouragement audio player");
                playNextEncouragementTrack();
                return;
            }
            encouragementPlayer.setOnCompletionListener(mp -> playNextEncouragementTrack());
            encouragementPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Encouragement audio playback failed: " + what + ", " + extra);
                playNextEncouragementTrack();
                return true;
            });
            encouragementPlayer.start();
        } catch (IllegalArgumentException | IllegalStateException e) {
            Log.e(TAG, "Unable to play encouragement audio", e);
            playNextEncouragementTrack();
        }
    }

    private void finishEncouragementPlayback() {
        releaseEncouragementPlayer();
        audioManager.abandonAudioFocus(focusListener);
        encouragementPlaying = false;
        selectedEncouragementTracks = null;
        if (finishAfterEncouragement) {
            finish();
            return;
        }
        Runnable continuation = afterEncouragement;
        afterEncouragement = null;
        if (continuation != null && !isFinishing()) {
            continuation.run();
        }
    }

    private void releaseEncouragementPlayer() {
        if (encouragementPlayer != null) {
            encouragementPlayer.setOnCompletionListener(null);
            encouragementPlayer.setOnErrorListener(null);
            encouragementPlayer.release();
            encouragementPlayer = null;
        }
    }

    private void onSeekComplete() {
        refreshFrame();
        updateProgress();
        saveCurrentPosition(true);
        scheduleFrameTick();
    }

    private void seekTo(int positionMs) {
        if (!prepared) {
            return;
        }
        int duration = Math.max(0, player.getDuration());
        int target = Math.max(0, Math.min(positionMs, duration));
        completed = false;
        player.seekTo(target);
        positionView.setText(formatTime(target));
        seekBar.setProgress(target);
        scheduleHideOverlay();
    }

    private void seekBy(int deltaMs) {
        if (prepared) {
            seekTo(player.getCurrentPosition() + deltaMs);
        }
    }

    private void playPrevious() {
        if (!prepared) {
            return;
        }
        if (index == 0 || player.getCurrentPosition() > RESTART_THRESHOLD_MS) {
            seekTo(0);
        } else {
            playIndex(index - 1);
        }
    }

    private void playNext() {
        if (index + 1 < uris.size()) {
            playIndex(index + 1);
        }
    }

    private void onPlaybackError() {
        Toast.makeText(this, getString(R.string.cannot_play, titles.get(index)), Toast.LENGTH_SHORT).show();
        if (index + 1 < uris.size()) {
            playIndex(index + 1);
        } else {
            updatePlayButton();
            setOverlayVisible(true);
        }
    }

    private void releasePlayer() {
        handler.removeCallbacks(frameTick);
        prepared = false;
        if (player != null) {
            player.release();
            player = null;
        }
        if (frameView != null) {
            frameView.setKeepScreenOn(false);
        }
        if (audioManager != null) {
            audioManager.abandonAudioFocus(focusListener);
        }
    }

    // ---------- 画面刷新 ----------

    /** 按当前状态刷新画面：播放中按所选间隔刷新，暂停时显示精确位置的画面。 */
    private void refreshFrame() {
        if (!prepared) {
            return;
        }
        int position = player.getCurrentPosition();
        if (player.isPlaying()) {
            long slot = position / frameIntervalMs;
            if (slot != shownSlot) {
                shownSlot = slot;
                sampler.requestFrame(slot * frameIntervalMs, originalFrameRate);
            }
        } else {
            shownSlot = -1;
            sampler.requestFrame(position, true);
        }
    }

    /** 调度下一次刷新：对齐到所选帧间隔的下一个边界。 */
    private void scheduleFrameTick() {
        handler.removeCallbacks(frameTick);
        if (!isPlaying()) {
            return;
        }
        long position = player.getCurrentPosition();
        long nextSlot = (position / frameIntervalMs + 1) * frameIntervalMs;
        handler.postDelayed(frameTick, (nextSlot - position) + TICK_SLACK_MS);
    }

    /** 更新进度条与时间（只在刷新周期内更新，减少墨水屏局部刷新）。 */
    private void updateProgress() {
        if (!prepared || seeking) {
            return;
        }
        int position = player.getCurrentPosition();
        if (isPlaying() && Math.abs(position - lastProgressUiPositionMs) < PROGRESS_UI_INTERVAL_MS) {
            return;
        }
        lastProgressUiPositionMs = position;
        seekBar.setProgress(position);
        positionView.setText(formatTime(position));
        saveCurrentPosition(false);
    }

    private void saveCurrentPosition(boolean force) {
        if (player == null || !prepared || index < 0 || index >= uris.size()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastPositionSaveTimeMs < POSITION_SAVE_INTERVAL_MS) {
            return;
        }
        int position = Math.max(0, player.getCurrentPosition());
        int duration = Math.max(0, player.getDuration());
        Uri uri = uris.get(index);
        if (completed || (duration > 0 && position >= duration)) {
            PlaybackPreferences.clearPosition(this, uri);
        } else {
            PlaybackPreferences.savePositionMs(this, uri, position);
        }
        lastPositionSaveTimeMs = now;
    }

    private void updatePlayButton() {
        boolean playing = isPlaying();
        playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playButton.setContentDescription(getString(playing ? R.string.pause : R.string.play));
    }

    // ---------- 控制栏显示 ----------

    private void setOverlayVisible(boolean visible) {
        overlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        scheduleHideOverlay();
    }

    private void scheduleHideOverlay() {
        handler.removeCallbacks(hideOverlay);
        if (CONTROLS_HIDE_DELAY_MS > 0 && overlay.getVisibility() == View.VISIBLE && isPlaying() && !seeking) {
            handler.postDelayed(hideOverlay, CONTROLS_HIDE_DELAY_MS);
        }
    }

    // ---------- 系统与生命周期 ----------

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pause(); // 切到后台或息屏时暂停
        saveCurrentPosition(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        saveCurrentPosition(true);
        releaseEncouragementPlayer();
        releasePlayer();
        if (sampler != null) {
            sampler.close();
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayPause();
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(-SEEK_STEP_MS);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(SEEK_STEP_MS);
                break;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                playPrevious();
                break;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                playNext();
                break;
            default:
                return super.onKeyDown(keyCode, event);
        }
        setOverlayVisible(true);
        return true;
    }

    @Override
    public void onBackPressed() {
        requestExit();
    }

    static String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
