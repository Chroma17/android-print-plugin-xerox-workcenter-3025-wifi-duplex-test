package com.xerox3025.printplugin;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives the "Print back sides" / "Cancel" buttons of the manual-duplex notification
 * and hands the answer to the print thread that is waiting for it.
 */
public class DuplexActionReceiver extends BroadcastReceiver {

    public static final String ACTION_CONTINUE = "com.xerox3025.printplugin.DUPLEX_CONTINUE";
    public static final String ACTION_CANCEL = "com.xerox3025.printplugin.DUPLEX_CANCEL";
    public static final String EXTRA_JOB_KEY = "job_key";

    private static final Map<Integer, BlockingQueue<Boolean>> WAITING = new ConcurrentHashMap<>();

    /** Called by the print thread before it posts the prompt notification. */
    static BlockingQueue<Boolean> register(int jobKey) {
        BlockingQueue<Boolean> q = new ArrayBlockingQueue<>(1);
        WAITING.put(jobKey, q);
        return q;
    }

    static void unregister(int jobKey) {
        WAITING.remove(jobKey);
    }

    /** true = continue with the back sides, false = give up. */
    static void signal(int jobKey, boolean proceed) {
        BlockingQueue<Boolean> q = WAITING.get(jobKey);
        if (q != null) q.offer(proceed);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int key = intent.getIntExtra(EXTRA_JOB_KEY, 0);
        boolean proceed = ACTION_CONTINUE.equals(intent.getAction());
        if (!WAITING.containsKey(key)) {
            // The print service was restarted since page 1 was printed; nothing to resume.
            PrintLog.w("DuplexReceiver", "No waiting job for key " + key);
            context.getSystemService(NotificationManager.class).cancel(key);
            return;
        }
        PrintLog.i("DuplexReceiver", proceed ? "User tapped Print back sides" : "User cancelled duplex");
        signal(key, proceed);
    }
}
