package dev.multiprompt.verification;

import android.app.Instrumentation;
import android.app.Activity;
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

    @Override public void onStart() {
        PowerManager power = (PowerManager) getTargetContext().getSystemService("power");
        long end = SystemClock.elapsedRealtime() + 600000;
        while (SystemClock.elapsedRealtime() < end) {
            Bundle result = new Bundle();
            boolean shown = visible(getUiAutomation().getRootInActiveWindow());
            result.putString("sample", "{\"at\":" + System.currentTimeMillis()
                + ",\"visible\":" + shown + ",\"interactive\":" + power.isInteractive() + "}");
            sendStatus(0, result);
            SystemClock.sleep(100);
        }
        finish(Activity.RESULT_OK, new Bundle());
    }
}
