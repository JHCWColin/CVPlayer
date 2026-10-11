package com.inkbox.videoplayer;

import android.app.Activity;
import android.os.Bundle;
import android.widget.RadioGroup;

public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        RadioGroup frameRateOptions = findViewById(R.id.frame_rate_options);
        long selectedInterval = PlaybackPreferences.getFrameIntervalMs(this);
        frameRateOptions.check(radioButtonForInterval(selectedInterval));
        frameRateOptions.setOnCheckedChangeListener((group, checkedId) -> {
            long intervalMs;
            if (checkedId == R.id.frame_rate_1s) {
                intervalMs = 1000;
            } else if (checkedId == R.id.frame_rate_3s) {
                intervalMs = 3000;
            } else if (checkedId == R.id.frame_rate_5s) {
                intervalMs = 5000;
            } else {
                intervalMs = PlaybackPreferences.ORIGINAL_FRAME_RATE;
            }
            PlaybackPreferences.setFrameIntervalMs(this, intervalMs);
        });

        findViewById(R.id.settings_done).setOnClickListener(v -> finish());
    }

    private int radioButtonForInterval(long intervalMs) {
        if (intervalMs == 1000) {
            return R.id.frame_rate_1s;
        }
        if (intervalMs == 3000) {
            return R.id.frame_rate_3s;
        }
        if (intervalMs == PlaybackPreferences.ORIGINAL_FRAME_RATE) {
            return R.id.frame_rate_original;
        }
        return R.id.frame_rate_5s;
    }
}
