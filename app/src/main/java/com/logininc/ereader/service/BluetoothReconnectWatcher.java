package com.logininc.ereader.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.logininc.ereader.persistence.SettingsDao;

/**
 * Watches audio output devices so playback can optionally resume when a Bluetooth headset reconnects
 * after an unexpected disconnection (becoming noisy) pause.
 */
public class BluetoothReconnectWatcher {

    private static final long ROUTE_SETTLE_DELAY_MS = 350L;

    @Nullable
    private final AudioManager audioManager;
    private final SettingsDao settingsDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable onReconnectResume;
    private final Runnable delayedResume;

    private boolean registered = false;
    private boolean pendingNoisyResume = false;
    private boolean manuallyCleared = false;

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            if (!pendingNoisyResume || addedDevices == null || !hasBluetoothSink(addedDevices)) {
                return;
            }
            if (!settingsDao.isBluetoothAutoResume()) {
                return;
            }
            pendingNoisyResume = false;
            mainHandler.removeCallbacks(delayedResume);
            mainHandler.postDelayed(delayedResume, ROUTE_SETTLE_DELAY_MS);
        }
    };

    public BluetoothReconnectWatcher(@NonNull Context context, @NonNull Runnable onReconnectResume) {
        Context appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.settingsDao = new SettingsDao(appContext);
        this.onReconnectResume = onReconnectResume;
        this.delayedResume = () -> {
            if (!manuallyCleared && settingsDao.isBluetoothAutoResume()) {
                onReconnectResume.run();
            }
        };
    }

    public void register() {
        if (registered || audioManager == null) {
            return;
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler);
        registered = true;
    }

    public void unregister() {
        mainHandler.removeCallbacks(delayedResume);
        if (registered && audioManager != null) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback);
            registered = false;
        }
    }

    public void markPausedByNoisyDisconnect() {
        pendingNoisyResume = true;
        manuallyCleared = false;
    }

    public void clearPendingResume() {
        pendingNoisyResume = false;
        manuallyCleared = true;
        mainHandler.removeCallbacks(delayedResume);
    }

    private static boolean hasBluetoothSink(@NonNull AudioDeviceInfo[] devices) {
        for (AudioDeviceInfo device : devices) {
            if (device != null && device.isSink() && isBluetoothOutputType(device.getType())) {
                return true;
            }
        }
        return false;
    }

    @SuppressLint("InlinedApi")
    public static boolean isBluetoothOutputType(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_HEARING_AID
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER;
    }
}
