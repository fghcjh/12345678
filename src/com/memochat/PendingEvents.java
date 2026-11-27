package com.memochat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** 后台线程生成的事件（比如她主动说的话）先存在这里，WebView 起来后再补发。 */
public class PendingEvents {

    private static final String PREF = "memochat_pending";

    public static void push(Context ctx, String json) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String all = sp.getString("queue", "");
            String next = all == null || all.isEmpty() ? json : all + "\n" + json;
            if (next.length() > 20000) next = next.substring(next.length() - 20000);
            sp.edit().putString("queue", next).apply();
        } catch (Exception ignored) {
        }
    }

    public static List<String> takeAll(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String all = sp.getString("queue", "");
            sp.edit().remove("queue").apply();
            if (all == null || all.isEmpty()) return out;
            for (String line : all.split("\n")) {
                if (!line.trim().isEmpty()) out.add(line.trim());
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
