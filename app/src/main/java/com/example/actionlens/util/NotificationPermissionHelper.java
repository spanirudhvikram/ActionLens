package com.example.actionlens.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.core.app.NotificationManagerCompat;

import java.util.Set;

/**
 * Utility for checking and navigating to Android Notification Listener Access settings.
 */
public final class NotificationPermissionHelper {

    private NotificationPermissionHelper() {
        // Utility class
    }

    /**
     * Checks whether the user has explicitly authorized ActionLens
     * in Android Notification Listener Settings.
     */
    public static boolean isNotificationListenerEnabled(Context context) {
        if (context == null) {
            return false;
        }

        // 1. Primary check using NotificationManagerCompat
        Set<String> enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context);
        if (enabledPackages != null && enabledPackages.contains(context.getPackageName())) {
            return true;
        }

        // 2. Fallback check querying Settings.Secure
        String flat = Settings.Secure.getString(
                context.getContentResolver(),
                "enabled_notification_listeners"
        );
        if (!TextUtils.isEmpty(flat)) {
            String myPkg = context.getPackageName();
            String[] names = flat.split(":");
            for (String name : names) {
                ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && TextUtils.equals(myPkg, cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Launches the system settings screen where the user can enable or revoke
     * notification access for ActionLens.
     */
    public static void openNotificationListenerSettings(Context context) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
