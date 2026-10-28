package dev.multiprompt.verification;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import java.io.File;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.Rect;

public class Observe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    private boolean visible(AccessibilityNodeInfo node) {
        if (node == null) return false;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if ("Working".contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())
                && node.isVisibleToUser() && !bounds.isEmpty()) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (visible(child)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo text(AccessibilityNodeInfo node, String value) {
        if (node == null) return null;
        if (value.contentEquals(node.getText() == null ? "" : node.getText())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = text(node.getChild(i), value);
            if (found != null) return found;
        }
        return null;
    }

    @Override public void onStart() {
        Intent intent = getTargetContext().getPackageManager().getLaunchIntentForPackage("dev.multiprompt.companion");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getTargetContext().startActivity(intent);
        File stop = new File(getTargetContext().getExternalFilesDir(null), "working-stop");
        PowerManager power = (PowerManager) getTargetContext().getSystemService("power");
        long end = SystemClock.elapsedRealtime() + 600000;
        while (SystemClock.elapsedRealtime() < end && !stop.exists()) {
            Bundle result = new Bundle();
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (text(root, "Inbox") != null) {
                AccessibilityNodeInfo session = text(root, "verify-session");
                while (session != null && !session.isClickable()) session = session.getParent();
                if (session != null) session.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            boolean ready = text(root, "WORKING_DOTS_READY") != null;
            boolean shown = visible(root);
            result.putString("sample", "{\"at\":" + System.currentTimeMillis()
                + ",\"ready\":" + ready + ",\"visible\":" + shown + ",\"interactive\":" + power.isInteractive() + "}");
            sendStatus(0, result);
            SystemClock.sleep(100);
        }
        finish(Activity.RESULT_OK, new Bundle());
    }
}
