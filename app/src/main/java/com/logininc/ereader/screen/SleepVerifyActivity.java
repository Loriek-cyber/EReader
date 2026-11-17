package com.logininc.ereader.screen;

import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.logininc.ereader.R;
import com.logininc.ereader.model.Title;
import com.logininc.ereader.service.PlaybackSessionManager;
import com.logininc.ereader.service.SleepTimerController;
import com.logininc.ereader.util.ThemeHelper;

public class SleepVerifyActivity extends AppCompatActivity {

    private static final long VERIFY_TIMEOUT_MS = 60_000L;
    private static final int SWIPE_THRESHOLD_RELEASE = 85;
    private static final int SWIPE_THRESHOLD_INSTANT = 90;

    private TextView tvCountdown;
    private TextView tvSwipeHint;
    private SeekBar seekSleepSwipe;

    @Nullable
    private CountDownTimer countDownTimer;
    private boolean handled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applySavedLocale(this);
        ThemeHelper.applyTheme(this, true);
        super.onCreate(savedInstanceState);
        configureWakeScreenFlags();
        setContentView(R.layout.activity_sleep_verify);

        Title active = PlaybackSessionManager.getInstance().getActiveTitle();
        String titleLabel = active != null ? active.getTitle() : getString(R.string.app_name);

        TextView tvSubtitle = findViewById(R.id.tvSleepSubtitle);
        tvSubtitle.setText(getString(R.string.sleep_verify_subtitle, titleLabel));

        tvCountdown = findViewById(R.id.tvSleepCountdown);
        tvSwipeHint = findViewById(R.id.tvSwipeHint);
        seekSleepSwipe = findViewById(R.id.seekSleepSwipe);

        findViewById(R.id.btnSleepStop).setOnClickListener(v -> stopAndFinish());

        seekSleepSwipe.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSwipeHint.setAlpha(Math.max(0.15f, 1.0f - (progress / 100.0f)));
                if (fromUser && progress >= SWIPE_THRESHOLD_INSTANT) {
                    confirmWakeAndResume();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (handled) {
                    return;
                }
                if (seekBar.getProgress() >= SWIPE_THRESHOLD_RELEASE) {
                    confirmWakeAndResume();
                } else {
                    seekBar.setProgress(0);
                    tvSwipeHint.setAlpha(1.0f);
                }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                stopAndFinish();
            }
        });

        startCountdown();
    }

    @SuppressWarnings("deprecation")
    private void configureWakeScreenFlags() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }
    }

    private void startCountdown() {
        cancelCountdown();
        countDownTimer = new CountDownTimer(VERIFY_TIMEOUT_MS, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                int seconds = Math.max(1, (int) Math.ceil(millisUntilFinished / 1000.0));
                tvCountdown.setText(getString(R.string.sleep_verify_countdown, seconds));
            }

            @Override
            public void onFinish() {
                stopAndFinish();
            }
        };
        countDownTimer.start();
    }

    private void confirmWakeAndResume() {
        if (handled) {
            return;
        }
        handled = true;
        cancelCountdown();
        SleepTimerController.getInstance().onUserVerifiedWake(this);
        Toast.makeText(this, R.string.sleep_verify_resumed, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void stopAndFinish() {
        if (handled) {
            return;
        }
        handled = true;
        cancelCountdown();
        SleepTimerController.getInstance().onVerificationStopped(this);
        finish();
    }

    private void cancelCountdown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
    }

    @Override
    protected void onDestroy() {
        cancelCountdown();
        super.onDestroy();
    }
}
