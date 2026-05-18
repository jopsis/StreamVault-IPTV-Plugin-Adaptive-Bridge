package com.streamvault.plugin.adaptivebridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

final class AdaptiveSettings {
    private static final String LEGACY_PREFS = "streamvault_adaptive_bridge_legacy";
    private static final String PREFS = "streamvault_adaptive_bridge_user_sources";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SOURCE_URLS = "source_urls";
    private static final String KEY_LAST_MESSAGE = "last_message";
    private static final String KEY_LAST_CHANNEL_COUNT = "last_channel_count";

    private final SharedPreferences prefs;

    AdaptiveSettings(Context context) {
        Context appContext = context.getApplicationContext();
        appContext.deleteSharedPreferences(LEGACY_PREFS);
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    String sourceUrls() {
        return prefs.getString(KEY_SOURCE_URLS, "");
    }

    String lastMessage() {
        return prefs.getString(KEY_LAST_MESSAGE, "");
    }

    int lastChannelCount() {
        if (prefs.contains(KEY_LAST_CHANNEL_COUNT)) {
            return prefs.getInt(KEY_LAST_CHANNEL_COUNT, 0);
        }
        return parseLeadingCount(lastMessage());
    }

    void setLastMessage(String message) {
        prefs.edit().putString(KEY_LAST_MESSAGE, message == null ? "" : message).apply();
    }

    void setLastCatalogStatus(int channelCount, String message) {
        prefs.edit()
                .putInt(KEY_LAST_CHANNEL_COUNT, Math.max(0, channelCount))
                .putString(KEY_LAST_MESSAGE, message == null ? "" : message)
                .apply();
    }

    JSONObject values(String providerUrl, int channelCount, String status) throws Exception {
        return new JSONObject()
                .put("status", status)
                .put("providerUrl", providerUrl)
                .put("channelCount", channelCount)
                .put("lastMessage", lastMessage())
                .put(KEY_SOURCE_URLS, sourceUrls());
    }

    void save(JSONObject values) {
        SharedPreferences.Editor editor = prefs.edit();
        if (values.has(KEY_SOURCE_URLS)) {
            editor.putString(KEY_SOURCE_URLS, values.optString(KEY_SOURCE_URLS, ""));
        }
        editor.apply();
    }

    private int parseLeadingCount(String message) {
        if (message == null) return 0;
        String trimmed = message.trim();
        if (trimmed.isEmpty()) return 0;
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        if (end == 0) return 0;
        try {
            return Integer.parseInt(trimmed.substring(0, end));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
