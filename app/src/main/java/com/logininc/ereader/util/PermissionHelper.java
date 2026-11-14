package com.logininc.ereader.util;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralizes runtime storage/media/notification permission checks and SAF persistable URI grants.
 */
public final class PermissionHelper {

    public static final int REQUEST_CODE_MEDIA_PERMISSIONS = 1001;

    private PermissionHelper() {
    }

    @NonNull
    public static String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return permissions.toArray(new String[0]);
    }

    public static boolean hasStorageOrAudioPermission(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestMissingPermissions(@NonNull Activity activity) {
        List<String> missing = new ArrayList<>();
        for (String perm : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(
                    activity,
                    missing.toArray(new String[0]),
                    REQUEST_CODE_MEDIA_PERMISSIONS
            );
        }
    }

    public static void takePersistableReadPermission(@NonNull Context context, @NonNull Uri uri) {
        try {
            ContentResolver resolver = context.getContentResolver();
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            resolver.takePersistableUriPermission(uri, flags);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Some document providers only grant temporary read permission.
        }
    }
}
