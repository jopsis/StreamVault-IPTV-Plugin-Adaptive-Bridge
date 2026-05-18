package com.streamvault.plugin.inputstreamadaptive;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AdaptiveChannel {
    private static final String CLEARKEY_SYSTEM_ID = "E2719D58-A985-B3C9-781A-B030AF78D30E";
    private static final String PLAYREADY_SYSTEM_ID = "9A04F079-9840-4286-AB92-E65BE0885F95";

    final String id;
    final String name;
    final String groupTitle;
    final String tvgId;
    final String logoUrl;
    final String manifestUrl;
    final String manifestType;
    final String userAgent;
    final Map<String, String> headers;
    final Drm drm;

    AdaptiveChannel(
            String id,
            String name,
            String groupTitle,
            String tvgId,
            String logoUrl,
            String manifestUrl,
            String manifestType,
            String userAgent,
            Map<String, String> headers,
            Drm drm
    ) {
        this.id = id;
        this.name = name;
        this.groupTitle = groupTitle;
        this.tvgId = tvgId;
        this.logoUrl = logoUrl;
        this.manifestUrl = manifestUrl;
        this.manifestType = manifestType;
        this.userAgent = userAgent;
        this.headers = headers == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.drm = drm;
    }

    boolean hasDrm() {
        return drm != null;
    }

    String streamType() {
        String normalized = manifestType == null ? "" : manifestType.toLowerCase(Locale.ROOT);
        if (normalized.equals("mpd") || normalized.equals("dash") || manifestUrl.toLowerCase(Locale.ROOT).contains(".mpd")) {
            return "DASH";
        }
        if (normalized.equals("hls") || normalized.equals("m3u8") || manifestUrl.toLowerCase(Locale.ROOT).contains(".m3u8")) {
            return "HLS";
        }
        if (normalized.equals("ism") || normalized.equals("isml") || normalized.equals("smoothstreaming") ||
                normalized.equals("smooth_streaming") || manifestUrl.toLowerCase(Locale.ROOT).contains(".isml/manifest")) {
            return "SMOOTH_STREAMING";
        }
        return "UNKNOWN";
    }

    boolean needsSmoothStreamingClearKeyManifestProxy() {
        return "SMOOTH_STREAMING".equals(streamType()) && drm != null && "CLEARKEY".equals(drm.scheme);
    }

    boolean needsDashClearKeyManifestProxy() {
        return "DASH".equals(streamType()) && drm != null && "CLEARKEY".equals(drm.scheme) && !drm.clearKeys.isEmpty();
    }

    boolean needsManifestProxy() {
        return needsSmoothStreamingClearKeyManifestProxy() || needsDashClearKeyManifestProxy();
    }

    String playbackUrl(String localBaseUrl) {
        if (needsSmoothStreamingClearKeyManifestProxy()) {
            return localBaseUrl + "/manifest/" + id + "/Manifest";
        }
        if (needsDashClearKeyManifestProxy()) {
            return localBaseUrl + "/manifest/" + id + "/manifest.mpd";
        }
        return manifestUrl;
    }

    boolean isManifestProxyEntry(String suffix) {
        String normalized = suffix == null ? "" : suffix.trim();
        return (needsSmoothStreamingClearKeyManifestProxy() && normalized.equalsIgnoreCase("Manifest")) ||
                (needsDashClearKeyManifestProxy() && normalized.equalsIgnoreCase("manifest.mpd"));
    }

    String originUrlForManifestProxyPath(String suffix) {
        String normalizedSuffix = suffix == null ? "" : suffix.trim();
        if (normalizedSuffix.isEmpty() || normalizedSuffix.equalsIgnoreCase("Manifest")) {
            return manifestUrl;
        }
        while (normalizedSuffix.startsWith("/")) {
            normalizedSuffix = normalizedSuffix.substring(1);
        }
        return originBaseUrl() + "/" + normalizedSuffix;
    }

    String rewriteSmoothStreamingManifestForClearKey(String manifest) {
        if (!needsSmoothStreamingClearKeyManifestProxy() || manifest == null || manifest.isEmpty()) {
            return manifest == null ? "" : manifest;
        }
        return manifest.replaceAll(
                "(?i)SystemID=\"\\{?" + PLAYREADY_SYSTEM_ID + "\\}?\"",
                "SystemID=\"" + CLEARKEY_SYSTEM_ID + "\""
        );
    }

    String rewriteManifestForPlayback(String manifest) {
        if (manifest == null || manifest.isEmpty()) return manifest == null ? "" : manifest;
        if (needsSmoothStreamingClearKeyManifestProxy()) {
            return rewriteSmoothStreamingManifestForClearKey(manifest);
        }
        if (needsDashClearKeyManifestProxy()) {
            return DashClearKeyManifestRewriter.rewrite(manifest, drm.clearKeys);
        }
        return manifest;
    }

    private String originBaseUrl() {
        String lower = manifestUrl.toLowerCase(Locale.ROOT);
        int manifestIndex = lower.lastIndexOf("/manifest");
        if (manifestIndex > 0) {
            return manifestUrl.substring(0, manifestIndex);
        }
        int slash = manifestUrl.lastIndexOf('/');
        return slash > 0 ? manifestUrl.substring(0, slash) : manifestUrl;
    }

    JSONObject drmJson(String localBaseUrl) throws Exception {
        if (drm == null) return null;
        JSONObject json = new JSONObject()
                .put("scheme", drm.scheme)
                .put("headers", new JSONObject(drm.headers))
                .put("multiSession", drm.multiSession)
                .put("forceDefaultLicenseUrl", true)
                .put("playClearContentWithoutKey", true);
        if ("CLEARKEY".equals(drm.scheme) && !drm.clearKeys.isEmpty()) {
            json.put("licenseUrl", localBaseUrl + "/license/clearkey/" + id);
        } else {
            json.put("licenseUrl", drm.licenseUrl);
        }
        return json;
    }

    JSONObject clearKeyJwk() throws Exception {
        if (drm == null || drm.clearKeys.isEmpty()) {
            return new JSONObject().put("keys", new JSONArray()).put("type", "temporary");
        }
        JSONArray keys = new JSONArray();
        for (ClearKey key : drm.clearKeys) {
            keys.put(new JSONObject()
                    .put("kty", "oct")
                    .put("kid", key.kidBase64Url)
                    .put("k", key.keyBase64Url));
        }
        return new JSONObject().put("keys", keys).put("type", "temporary");
    }

    static final class Drm {
        final String scheme;
        final String licenseUrl;
        final Map<String, String> headers;
        final boolean multiSession;
        final List<ClearKey> clearKeys;

        Drm(String scheme, String licenseUrl, Map<String, String> headers, boolean multiSession, List<ClearKey> clearKeys) {
            this.scheme = scheme;
            this.licenseUrl = licenseUrl == null ? "" : licenseUrl;
            this.headers = headers == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.multiSession = multiSession;
            this.clearKeys = clearKeys == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(clearKeys));
        }
    }

    static final class ClearKey {
        final String kidHex;
        final String keyHex;
        final String kidBase64Url;
        final String keyBase64Url;

        ClearKey(String kidHex, String keyHex, String kidBase64Url, String keyBase64Url) {
            this.kidHex = kidHex;
            this.keyHex = keyHex;
            this.kidBase64Url = kidBase64Url;
            this.keyBase64Url = keyBase64Url;
        }
    }
}
