package com.memochat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后重新排一次“主动开口”的任务（JobScheduler 的持久任务也会自己恢复，这里做兜底）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            ProactiveJobService.ensureChannel(context);
            ProactiveScheduler.schedule(context.getApplicationContext());
        } catch (Exception ignored) {
        }
    }
}
