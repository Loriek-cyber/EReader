package com.logininc.ereader.service;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.persistence.SettingsDao;
import com.logininc.ereader.screen.SleepVerifyActivity;

import java.util.Calendar;

/**
 * Monitors active reading/listening sessions during the configured nocturnal window
 * (or when manually armed from the player), pauses the session when the configured minutes expire,
 * and opens the full-screen swipe-to-continue verification activity.
 */
public class SleepTimerController {

    private static volatile SleepTimerController instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable
    private Context appContext;
    private boolean manualArmed = false;
    private long manualOverrideIntervalMs = -1L;
    private boolean timerScheduled = false;

    private final Runnable timerExpiredRunnable = new Runnable() {
        @Override
        public void run() {
            timerScheduled = false;
            manualArmed = false;
            manualOverrideIntervalMs = -1L;
            if (appContext == null) {
                return;
            }
            PlaybackSessionManager session = PlaybackSessionManager.getInstance();
            if (session.getActiveTitle() == null || !session.isPlaying()) {
                return;
            }
            session.pause(appContext);
            Intent intent = new Intent(appContext, SleepVerifyActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            appContext.startActivity(intent);
        }
    };

    private SleepTimerController() {
    }

    public static SleepTimerController getInstance() {
        if (instance == null) {
            synchronized (SleepTimerController.class) {
                if (instance == null) {
                    instance = new SleepTimerController();
                }
            }
        }
        return instance;
    }

    /**
     * Pure testable check for whether {@code currentMinutes} (0..1439) falls inside
     * the configured window {@code [startMinutes, endMinutes)}.
     * Handles overnight ranges like 22:30 (1350) -> 07:00 (420).
     */
    public static boolean isWithinNightWindow(int currentMinutes, int startMinutes, int endMinutes) {
        if (startMinutes == endMinutes) {
            return true;
        }
        if (startMinutes < endMinutes) {
            return currentMinutes >= startMinutes && currentMinutes < endMinutes;
        }
        return currentMinutes >= startMinutes || currentMinutes < endMinutes;
    }

    public static boolean shouldScheduleTimer(
            boolean nightEnabled,
            int currentMinutes,
            int startMinutes,
            int endMinutes,
            boolean manualArmed
    ) {
        return manualArmed || (nightEnabled && isWithinNightWindow(currentMinutes, startMinutes, endMinutes));
    }

    public void onSessionActive(@NonNull Context context) {
        appContext = context.getApplicationContext();
        SettingsDao settings = new SettingsDao(appContext);
        Calendar cal = Calendar.getInstance();
        int currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int startMinutes = settings.getSleepStartHour() * 60 + settings.getSleepStartMin();
        int endMinutes = settings.getSleepEndHour() * 60 + settings.getSleepEndMin();

        if (shouldScheduleTimer(settings.isSleepTimerEnabled(), currentMinutes, startMinutes, endMinutes, manualArmed)) {
            long intervalMs = manualOverrideIntervalMs > 0
                    ? manualOverrideIntervalMs
                    : settings.getSleepIntervalMinutes() * 60_000L;
            schedule(intervalMs);
        }
    }

    public void armManualTimer(@NonNull Context context, int minutes) {
        appContext = context.getApplicationContext();
        manualArmed = true;
        manualOverrideIntervalMs = Math.max(1, minutes) * 60_000L;
        schedule(manualOverrideIntervalMs);
    }

    public void cancelManualTimer() {
        manualArmed = false;
        manualOverrideIntervalMs = -1L;
        cancel();
    }

    public void onSessionPaused() {
        cancel();
    }

    public void onSessionStopped() {
        manualArmed = false;
        manualOverrideIntervalMs = -1L;
        cancel();
    }

    public void onUserVerifiedWake(@NonNull Context context) {
        appContext = context.getApplicationContext();
        PlaybackSessionManager.getInstance().resume(appContext);
    }

    public void onVerificationStopped(@NonNull Context context) {
        appContext = context.getApplicationContext();
        PlaybackSessionManager.getInstance().pause(appContext);
    }

    private void schedule(long delayMs) {
        handler.removeCallbacks(timerExpiredRunnable);
        timerScheduled = true;
        handler.postDelayed(timerExpiredRunnable, delayMs);
    }

    private void cancel() {
        timerScheduled = false;
        handler.removeCallbacks(timerExpiredRunnable);
    }

    public boolean isTimerScheduled() {
        return timerScheduled;
    }
}
