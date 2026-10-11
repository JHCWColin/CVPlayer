package com.inkbox.videoplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

final class PlaybackPreferences {

    static final long ORIGINAL_FRAME_RATE = 0;
    static final long DEFAULT_FRAME_INTERVAL_MS = 5000;
    static final float DEFAULT_PLAYBACK_SPEED = 1.0f;

    private static final String PREFS = "playback";
    private static final String KEY_FRAME_INTERVAL = "frame_interval_ms";
    private static final String KEY_PLAYBACK_SPEED = "playback_speed";
    private static final String KEY_POSITION_PREFIX = "position:";

    private PlaybackPreferences() {
    }

    static long getFrameIntervalMs(Context context) {
        return preferences(context).getLong(KEY_FRAME_INTERVAL, DEFAULT_FRAME_INTERVAL_MS);
    }

    static void setFrameIntervalMs(Context context, long intervalMs) {
        preferences(context).edit().putLong(KEY_FRAME_INTERVAL, intervalMs).apply();
    }

    static float getPlaybackSpeed(Context context) {
        return preferences(context).getFloat(KEY_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED);
    }

    static void setPlaybackSpeed(Context context, float speed) {
        preferences(context).edit().putFloat(KEY_PLAYBACK_SPEED, speed).apply();
    }

    static long getPositionMs(Context context, Uri uri) {
        return preferences(context).getLong(positionKey(uri), 0);
    }

    static void savePositionMs(Context context, Uri uri, long positionMs) {
        preferences(context).edit().putLong(positionKey(uri), Math.max(0, positionMs)).apply();
    }

    static void clearPosition(Context context, Uri uri) {
        preferences(context).edit().remove(positionKey(uri)).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String positionKey(Uri uri) {
        return KEY_POSITION_PREFIX + uri;
    }
}
