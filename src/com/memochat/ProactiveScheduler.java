package com.memochat;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import java.util.Calendar;
import java.util.List;

/** 安排“下一次把她叫起来”的时间（用 JobScheduler，App 关掉也能被叫醒）。 */
public class ProactiveScheduler {

    private static final int JOB_ID = 0x4D43;

    public static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            js.cancel(JOB_ID);

            Settings settings = new Settings(ctx);
            if (!settings.proactiveOn() || settings.apiKey().isEmpty()) return;

            long delay;
            if (settings.proactiveCountToday() >= settings.proactiveMax()) {
                delay = msUntilTomorrowWindow(settings);
            } else {
                Store store = new Store(ctx);
                List<Store.Session> ss = store.sessions();
                long last = ss.isEmpty() ? 0 : store.lastMessageTs(ss.get(0).id);
                long idleMs = settings.proactiveIdle() * 3600_000L;
                long target = (last > 0 ? last + idleMs : System.currentTimeMillis() + idleMs);
                delay = Math.max(target - System.currentTimeMillis(), msUntilWindowOpen(settings));
                if (delay < 60_000) delay = 60_000;
            }
            enqueue(ctx, delay);
        } catch (Exception ignored) {
        }
    }

    public static void cancel(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Exception ignored) {
        }
    }

    private static void enqueue(Context ctx, long delayMs) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            ComponentName cn = new ComponentName(ctx.getPackageName(), ProactiveJobService.class.getName());
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, cn)
                    .setMinimumLatency(Math.max(60_000L, delayMs))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                b.setPersisted(true);
            }
            js.schedule(b.build());
        } catch (Exception ignored) {
        }
    }

    private static long msUntilWindowOpen(Settings s) {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        Calendar t = (Calendar) now.clone();
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        if (hour < s.proactiveStart()) {
            t.set(Calendar.HOUR_OF_DAY, s.proactiveStart());
        } else if (hour >= s.proactiveEnd()) {
            t.add(Calendar.DAY_OF_YEAR, 1);
            t.set(Calendar.HOUR_OF_DAY, s.proactiveStart());
        } else {
            return 0;
        }
        return Math.max(60_000L, t.getTimeInMillis() - now.getTimeInMillis());
    }

    private static long msUntilTomorrowWindow(Settings s) {
        Calendar now = Calendar.getInstance();
        Calendar t = (Calendar) now.clone();
        t.add(Calendar.DAY_OF_YEAR, 1);
        t.set(Calendar.HOUR_OF_DAY, s.proactiveStart());
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        return Math.max(60_000L, t.getTimeInMillis() - now.getTimeInMillis());
    }
}
