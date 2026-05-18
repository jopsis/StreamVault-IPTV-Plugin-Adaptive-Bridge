package com.streamvault.plugin.adaptivebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import org.json.JSONObject;

public class StreamVaultAdaptiveBridgePluginService extends Service {
    public static final String ACTION_KEEP_ALIVE = "com.streamvault.plugin.adaptivebridge.KEEP_ALIVE";
    private static final String NOTIFICATION_CHANNEL_ID = "adaptive_bridge_proxy";
    private static final int NOTIFICATION_ID = 39078;

    private HandlerThread handlerThread;
    private Messenger messenger;

    @Override
    public void onCreate() {
        super.onCreate();
        handlerThread = new HandlerThread("streamvault-adaptive-bridge-plugin");
        handlerThread.start();
        messenger = new Messenger(new Handler(handlerThread.getLooper(), this::handleMessage));
        keepAliveIfEnabled();
    }

    @Override
    public IBinder onBind(Intent intent) {
        keepAliveIfEnabled();
        return messenger.getBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AdaptiveBridge bridge = AdaptiveBridge.get(this);
        if (!bridge.isEnabled()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            bridge.start();
            promoteToForeground();
        } catch (Throwable ignored) {
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        super.onDestroy();
    }

    private boolean handleMessage(Message request) {
        Bundle response = newResponse(request);
        AdaptiveBridge bridge = AdaptiveBridge.get(this);
        try {
            switch (request.what) {
                case PluginContract.MSG_GET_MANIFEST:
                    response.putBoolean(PluginContract.KEY_SUCCESS, true);
                    response.putString(PluginContract.KEY_MANIFEST_JSON, bridge.manifestJson().toString());
                    break;
                case PluginContract.MSG_SET_ENABLED:
                    handleSetEnabled(bridge, request.getData(), response);
                    break;
                case PluginContract.MSG_GET_STATUS:
                    handleStatus(bridge, response);
                    break;
                case PluginContract.MSG_GET_PROVIDER_URL:
                    handleProviderUrl(bridge, response);
                    break;
                case PluginContract.MSG_PREPARE_PLAYBACK:
                    handlePreparePlayback(bridge, request.getData(), response);
                    break;
                case PluginContract.MSG_REWRITE_CAST_URL:
                    response.putBoolean(PluginContract.KEY_SUCCESS, true);
                    response.putBoolean(PluginContract.KEY_HANDLED, false);
                    break;
                case PluginContract.MSG_GET_CONFIGURATION_SCHEMA:
                    response.putBoolean(PluginContract.KEY_SUCCESS, true);
                    response.putString(PluginContract.KEY_CONFIGURATION_SCHEMA_JSON, bridge.configurationSchema().toString());
                    break;
                case PluginContract.MSG_GET_CONFIGURATION_VALUES:
                    response.putBoolean(PluginContract.KEY_SUCCESS, true);
                    response.putString(PluginContract.KEY_CONFIGURATION_VALUES_JSON, bridge.configurationValues().toString());
                    break;
                case PluginContract.MSG_SET_CONFIGURATION_VALUES:
                    bridge.saveConfiguration(request.getData().getString(PluginContract.KEY_CONFIGURATION_VALUES_JSON, "{}"));
                    response.putBoolean(PluginContract.KEY_SUCCESS, true);
                    response.putString(PluginContract.KEY_MESSAGE, getString(R.string.message_settings_saved));
                    break;
                case PluginContract.MSG_RUN_CONFIGURATION_ACTION:
                    String message = bridge.runAction(request.getData().getString(PluginContract.KEY_CONFIGURATION_ACTION_ID, ""));
                    response.putBoolean(PluginContract.KEY_SUCCESS, true);
                    response.putString(PluginContract.KEY_MESSAGE, message);
                    break;
                default:
                    response.putBoolean(PluginContract.KEY_SUCCESS, false);
                    response.putString(PluginContract.KEY_MESSAGE, getString(R.string.message_unsupported_plugin_request));
                    break;
            }
        } catch (Throwable error) {
            response.putBoolean(PluginContract.KEY_SUCCESS, false);
            response.putString(PluginContract.KEY_MESSAGE, error.getMessage() == null ? error.toString() : error.getMessage());
        }
        sendResponse(request, response);
        return true;
    }

    private void handleSetEnabled(AdaptiveBridge bridge, Bundle request, Bundle response) throws Exception {
        boolean enabled = request.getBoolean(PluginContract.KEY_ENABLED, false);
        bridge.setEnabled(enabled);
        if (enabled) {
            startKeepAliveService();
            promoteToForeground();
        } else {
            stopForeground(true);
            stopService(new Intent(this, StreamVaultAdaptiveBridgePluginService.class));
        }
        response.putBoolean(PluginContract.KEY_SUCCESS, true);
        response.putString(
                PluginContract.KEY_MESSAGE,
                enabled ? getString(R.string.message_enabled) : getString(R.string.message_disabled)
        );
    }

    private void handleStatus(AdaptiveBridge bridge, Bundle response) throws Exception {
        if (bridge.isEnabled()) {
            bridge.start();
            startKeepAliveService();
            promoteToForeground();
        }
        response.putBoolean(PluginContract.KEY_SUCCESS, true);
        response.putString(
                PluginContract.KEY_STATUS_LABEL,
                bridge.isEnabled() ? getString(R.string.status_ready) : getString(R.string.status_disabled)
        );
        response.putString(PluginContract.KEY_MESSAGE, bridge.cachedMessage());
    }

    private void handleProviderUrl(AdaptiveBridge bridge, Bundle response) throws Exception {
        if (!bridge.isEnabled()) {
            response.putBoolean(PluginContract.KEY_SUCCESS, false);
            response.putString(PluginContract.KEY_PROVIDER_NAME, getString(R.string.provider_name));
            response.putString(PluginContract.KEY_URL, "");
            response.putString(PluginContract.KEY_MESSAGE, getString(R.string.message_disabled));
            return;
        }

        bridge.start();
        startKeepAliveService();
        promoteToForeground();
        AdaptiveCatalog catalog = bridge.refreshCatalog(false);
        response.putBoolean(PluginContract.KEY_SUCCESS, !catalog.channels.isEmpty());
        response.putString(PluginContract.KEY_PROVIDER_NAME, getString(R.string.provider_name));
        response.putString(PluginContract.KEY_URL, bridge.providerUrl());
        response.putString(
                PluginContract.KEY_MESSAGE,
                catalog.channels.isEmpty() ? getString(R.string.message_provider_empty) : getString(R.string.message_provider_ready)
        );
    }

    private void handlePreparePlayback(AdaptiveBridge bridge, Bundle request, Bundle response) throws Exception {
        String inputUrl = request.getString(PluginContract.KEY_INPUT_URL, "");
        if (!bridge.ownsUrl(inputUrl)) {
            response.putBoolean(PluginContract.KEY_SUCCESS, true);
            response.putBoolean(PluginContract.KEY_HANDLED, false);
            return;
        }

        AdaptiveChannel channel = bridge.prepareChannel(inputUrl);
        if (bridge.isEnabled()) {
            startKeepAliveService();
            promoteToForeground();
        }
        response.putBoolean(PluginContract.KEY_HANDLED, true);
        if (channel == null) {
            response.putBoolean(PluginContract.KEY_SUCCESS, false);
            response.putString(PluginContract.KEY_MESSAGE, getString(R.string.message_playback_not_found));
            return;
        }

        response.putBoolean(PluginContract.KEY_SUCCESS, true);
        response.putString(PluginContract.KEY_OUTPUT_URL, channel.playbackUrl(bridge.baseUrl()));
        response.putString(PluginContract.KEY_STREAM_TYPE, channel.streamType());
        response.putString(PluginContract.KEY_HEADERS_JSON, AdaptiveBridge.mapToJson(bridge.playbackHeaders(channel)).toString());
        response.putString(PluginContract.KEY_USER_AGENT, bridge.playbackUserAgent(channel));
        JSONObject drm = channel.drmJson(bridge.baseUrl());
        if (drm != null) {
            response.putString(PluginContract.KEY_DRM_JSON, drm.toString());
        }
        response.putString(PluginContract.KEY_MESSAGE, getString(R.string.message_playback_ready));
    }

    private Bundle newResponse(Message request) {
        Bundle response = new Bundle();
        Bundle data = request.getData();
        response.putInt(PluginContract.KEY_API_VERSION, PluginContract.API_VERSION);
        if (data != null) {
            response.putString(PluginContract.KEY_REQUEST_ID, data.getString(PluginContract.KEY_REQUEST_ID, ""));
        }
        return response;
    }

    private void sendResponse(Message request, Bundle response) {
        if (request.replyTo == null) return;
        Message reply = Message.obtain(null, request.what);
        reply.setData(response);
        try {
            request.replyTo.send(reply);
        } catch (RemoteException ignored) {
        }
    }

    private void keepAliveIfEnabled() {
        AdaptiveBridge bridge = AdaptiveBridge.get(this);
        if (!bridge.isEnabled()) return;
        try {
            bridge.start();
            startKeepAliveService();
            promoteToForeground();
        } catch (Throwable ignored) {
        }
    }

    private void startKeepAliveService() {
        Intent intent = new Intent(this, StreamVaultAdaptiveBridgePluginService.class)
                .setAction(ACTION_KEEP_ALIVE);
        try {
            startService(intent);
        } catch (Throwable ignored) {
        }
    }

    private void promoteToForeground() {
        createNotificationChannel();
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification() {
        Intent configureIntent = new Intent(this, AdaptiveBridgeConfigActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, configureIntent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_adaptive_bridge_plugin)
                .setContentTitle("StreamVault Adaptive Bridge")
                .setContentText("Local StreamVault proxy is running.")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "StreamVault Adaptive Bridge",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps the StreamVault adaptive proxy available during playback.");
        manager.createNotificationChannel(channel);
    }
}
