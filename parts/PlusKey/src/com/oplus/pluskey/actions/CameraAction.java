package com.oplus.pluskey.actions;

import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import com.oplus.pluskey.Constants;
import com.oplus.pluskey.Settings;

/**
 * Opens the chosen camera app. Defaults to OPlus Camera when no target is set.
 */
public class CameraAction implements Action {
    private static final String OPLUS_CAMERA_PACKAGE = "com.oplus.camera";
    private static final String OPLUS_CAMERA_ACTIVITY = "com.oplus.camera.Camera";
    private static final String OPLUS_CAMERA_ACTION = "com.oplus.action.CAMERA";

    @Override
    public void run(Context ctx) {
        String pkg = Settings.getCameraAppPkg(ctx);
        Haptics.confirm(ctx);
        try {
            wakeForCamera(ctx);
            ctx.startActivity(cameraIntent(ctx, pkg));
        } catch (ActivityNotFoundException e) {
            Log.w(Constants.TAG, "Selected camera launch failed, falling back", e);
            Intent launch = pkg == null ? null
                    : ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                ctx.startActivity(addLaunchFlags(launch));
                return;
            }
            ctx.startActivity(addLaunchFlags(
                    ctx.getPackageManager().getLaunchIntentForPackage(OPLUS_CAMERA_PACKAGE)));
        }
    }

    private Intent cameraIntent(Context ctx, String pkg) {
        boolean secureLaunch = isKeyguardLocked(ctx) || !isInteractive(ctx);
        if (pkg == null || pkg.isEmpty() || OPLUS_CAMERA_PACKAGE.equals(pkg)) {
            if (secureLaunch) {
                Intent secure = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                secure.setPackage(OPLUS_CAMERA_PACKAGE);
                return addLaunchFlags(secure);
            }
            return addLaunchFlags(new Intent(OPLUS_CAMERA_ACTION)
                    .setClassName(OPLUS_CAMERA_PACKAGE, OPLUS_CAMERA_ACTIVITY));
        }

        Intent i = new Intent(secureLaunch
                ? MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
                : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        i.setPackage(pkg);
        return addLaunchFlags(i);
    }

    private boolean isKeyguardLocked(Context ctx) {
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    private boolean isInteractive(Context ctx) {
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    private void wakeForCamera(Context ctx) {
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        if (pm == null || pm.isInteractive()) {
            return;
        }
        try {
            pm.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_CAMERA_LAUNCH,
                    "PlusKey camera");
        } catch (Throwable t) {
            Log.w(Constants.TAG, "wake for camera failed", t);
        }
    }

    private Intent addLaunchFlags(Intent i) {
        if (i == null) {
            i = new Intent(OPLUS_CAMERA_ACTION);
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }
}
