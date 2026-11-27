package com.memochat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

/**
 * 后台唤醒：即使 App 被关掉，也由系统在合适的时间把它叫起来，
 * 让她主动说一句，然后发一条通知。
 */
public class ProactiveJobService extends JobService {

    private static final int JOB_ID = 0x4D43;   // "MC"
    static final String CHANNEL = "memochat_proactive";

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            boolean again = true;
            try {
                Store store = new Store(getApplicationContext());
                Settings settings = new Settings(getApplicationContext());
                LlmClient llm = new LlmClient(settings);
                GrowEngine grow = new GrowEngine(store, settings, llm);
                JSONObject r = grow.proactiveTick();
                if (r.optBoolean("due", false)) {
                    String text = r.optString("text", "");
                    if (!text.isEmpty()) notifyUser(text);
                    JSONObject ev = new JSONObject();
                    ev.put("t", "proactive");
                    ev.put("sid", r.optString("sid", ""));
                    ev.put("text", text);
                    ev.put("activity", r.optString("activity", ""));
                    PendingEvents.push(getApplicationContext(), ev.toString());
                }
            } catch (Exception ignored) {
            } finally {
                jobFinished(params, false);
                if (again) ProactiveScheduler.schedule(getApplicationContext());
            }
        }, "proactive-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    /* ---------------- 通知 ---------------- */

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "她主动说的话",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("空闲时她主动发来的消息");
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    private void notifyUser(String text) {
        try {
            ensureChannel(this);
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(this, CHANNEL);
            } else {
                b = new Notification.Builder(this);
            }
            int icon = getApplicationInfo().icon;
            b.setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_dialog_info)
                    .setContentTitle(Persona.parse(new Settings(this).characterJson()).name)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(0x4D44, b.build());
        } catch (Exception ignored) {
        }
    }
}
