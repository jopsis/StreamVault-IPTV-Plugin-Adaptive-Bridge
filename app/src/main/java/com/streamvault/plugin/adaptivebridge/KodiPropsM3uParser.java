package com.streamvault.plugin.adaptivebridge;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class KodiPropsM3uParser {
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
    List<AdaptiveChannel> parse(String sourceName, String m3uText) throws Exception {
        return parseWithMetadata(sourceName, m3uText).channels;
    }

    ParsedM3u parseWithMetadata(String sourceName, String m3uText) throws Exception {
        List<AdaptiveChannel> channels = new ArrayList<>();
        List<String> epgUrls = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new StringReader(m3uText == null ? "" : m3uText));
        PendingEntry pending = null;
        PendingEntry nextDirectives = new PendingEntry();
        String globalUserAgent = "";
        String line;
        while ((line = reader.readLine()) != null) {
            line = sanitize(line);
            if (line.isEmpty()) continue;
            if (line.regionMatches(true, 0, "#EXTM3U", 0, "#EXTM3U".length())) {
                Map<String, String> attrs = parseAttributes(line);
                globalUserAgent = attrs.getOrDefault("user-agent", "");
                addDistinct(epgUrls, extractHeaderEpgUrls(attrs));
            } else if (line.regionMatches(true, 0, "#EXTINF", 0, "#EXTINF".length())) {
                pending = parseExtInf(line);
                applyDirectiveDefaults(pending, nextDirectives);
                nextDirectives = new PendingEntry();
            } else if (line.regionMatches(true, 0, "#KODIPROP:", 0, "#KODIPROP:".length())) {
                applyKodiProp(pending != null ? pending : nextDirectives, line.substring("#KODIPROP:".length()));
            } else if (line.regionMatches(true, 0, "#EXTVLCOPT:", 0, "#EXTVLCOPT:".length())) {
                applyVlcOpt(pending != null ? pending : nextDirectives, line.substring("#EXTVLCOPT:".length()));
            } else if (line.regionMatches(true, 0, "#EXTGRP", 0, "#EXTGRP".length())) {
                String group = line.substring("#EXTGRP".length()).trim();
                if (group.startsWith(":") || group.startsWith("=")) group = group.substring(1).trim();
                (pending != null ? pending : nextDirectives).groupTitle = stripQuotes(group);
            } else if (line.startsWith("#")) {
                // Unknown playlist directive for the pending entry. Keep the entry alive.
            } else if (pending != null) {
                AdaptiveChannel channel = buildChannel(sourceName, pending, line, globalUserAgent);
                if (channel != null) channels.add(channel);
                pending = null;
            }
        }
        return new ParsedM3u(channels, epgUrls);
    }

    private void applyDirectiveDefaults(PendingEntry target, PendingEntry defaults) {
        target.props.putAll(defaults.props);
        target.headers.putAll(defaults.headers);
        if (target.userAgent.isEmpty()) target.userAgent = defaults.userAgent;
        if (target.groupTitle.isEmpty()) target.groupTitle = defaults.groupTitle;
    }

    private AdaptiveChannel buildChannel(String sourceName, PendingEntry pending, String rawUrl, String globalUserAgent) throws Exception {
        UrlAndHeaders urlAndHeaders = splitPipeHeaders(rawUrl);
        String manifestUrl = urlAndHeaders.url.trim();
        if (!isHttpUrl(manifestUrl)) return null;

        Map<String, String> headers = new LinkedHashMap<>();
        headers.putAll(parseHeaderProperty(pending.props.get("inputstream.adaptive.common_headers")));
        headers.putAll(parseHeaderProperty(pending.props.get("inputstream.adaptive.manifest_headers")));
        headers.putAll(parseHeaderProperty(pending.props.get("inputstream.adaptive.stream_headers")));
        headers.putAll(pending.headers);
        headers.putAll(urlAndHeaders.headers);
        String userAgent = firstNonBlank(
                pending.userAgent,
                headers.get("User-Agent"),
                headers.get("user-agent"),
                globalUserAgent
        );
        if (!userAgent.isEmpty()) {
            headers.put("User-Agent", userAgent);
        }
        String manifestType = firstNonBlank(
                pending.props.get("inputstream.adaptive.manifest_type"),
                pending.props.get("manifest_type"),
                inferManifestType(manifestUrl)
        );
        AdaptiveChannel.Drm drm = parseDrm(pending);
        String stableId = stableId(sourceName + "|" + pending.name + "|" + pending.tvgId + "|" + manifestUrl);
        return new AdaptiveChannel(
                stableId,
                pending.name,
                firstNonBlank(pending.groupTitle, "Adaptive"),
                pending.tvgId,
                pending.logoUrl,
                manifestUrl,
                manifestType,
                userAgent,
                headers,
                drm
        );
    }

    private AdaptiveChannel.Drm parseDrm(PendingEntry pending) throws Exception {
        AdaptiveChannel.Drm drmFromJson = parseDrmJson(pending.props.get("inputstream.adaptive.drm"));
        if (drmFromJson != null) return drmFromJson;

        String licenseType = firstNonBlank(
                pending.props.get("inputstream.adaptive.license_type"),
                pending.props.get("license_type")
        ).toLowerCase(Locale.ROOT);
        if (licenseType.isEmpty()) return null;

        String scheme = drmScheme(licenseType);
        if (scheme.isEmpty()) return null;

        String rawLicenseKey = firstNonBlank(
                pending.props.get("inputstream.adaptive.license_key"),
                pending.props.get("license_key")
        );
        Map<String, String> licenseHeaders = new LinkedHashMap<>();
        licenseHeaders.putAll(parseHeaderProperty(pending.props.get("inputstream.adaptive.license_headers")));

        if ("CLEARKEY".equals(scheme)) {
            if (looksLikeHttp(rawLicenseKey)) {
                UrlAndHeaders license = splitPipeHeaders(rawLicenseKey);
                licenseHeaders.putAll(license.headers);
                return new AdaptiveChannel.Drm(scheme, license.url, licenseHeaders, false, new ArrayList<>());
            }
            return new AdaptiveChannel.Drm(scheme, "", licenseHeaders, false, parseClearKeys(rawLicenseKey));
        }

        UrlAndHeaders license = splitPipeHeaders(rawLicenseKey);
        licenseHeaders.putAll(license.headers);
        String licenseUrl = license.url;
        return licenseUrl.isEmpty() ? null : new AdaptiveChannel.Drm(scheme, licenseUrl, licenseHeaders, true, new ArrayList<>());
    }

    private AdaptiveChannel.Drm parseDrmJson(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) return null;
        String text = raw.trim();
        if (!text.startsWith("{")) return null;
        JSONObject root = new JSONObject(text);
        if (root.has("org.w3.clearkey")) {
            Object value = root.get("org.w3.clearkey");
            String keyText = value instanceof JSONObject ? ((JSONObject) value).toString() : String.valueOf(value);
            List<AdaptiveChannel.ClearKey> keys = parseClearKeysFromJson(keyText);
            return new AdaptiveChannel.Drm("CLEARKEY", "", new LinkedHashMap<>(), false, keys);
        }
        if (root.has("com.widevine.alpha")) {
            DrmJsonParts parts = parseDrmJsonParts(root.get("com.widevine.alpha"));
            return parts.licenseUrl.isEmpty() ? null : new AdaptiveChannel.Drm("WIDEVINE", parts.licenseUrl, parts.headers, true, new ArrayList<>());
        }
        if (root.has("com.microsoft.playready")) {
            DrmJsonParts parts = parseDrmJsonParts(root.get("com.microsoft.playready"));
            return parts.licenseUrl.isEmpty() ? null : new AdaptiveChannel.Drm("PLAYREADY", parts.licenseUrl, parts.headers, true, new ArrayList<>());
        }
        return null;
    }

    private DrmJsonParts parseDrmJsonParts(Object value) throws Exception {
        if (!(value instanceof JSONObject)) {
            return new DrmJsonParts(String.valueOf(value), new LinkedHashMap<>());
        }
        JSONObject object = (JSONObject) value;
        String licenseUrl = "";
        Map<String, String> headers = new LinkedHashMap<>();
        if (object.has("license")) {
            Object license = object.get("license");
            if (license instanceof JSONObject) {
                JSONObject licenseJson = (JSONObject) license;
                licenseUrl = firstNonBlank(
                        licenseJson.optString("server_url", ""),
                        licenseJson.optString("serverUrl", ""),
                        licenseJson.optString("url", "")
                );
                if (licenseJson.has("headers")) {
                    JSONObject headersJson = licenseJson.optJSONObject("headers");
                    if (headersJson != null) headers.putAll(jsonToMap(headersJson));
                }
            } else {
                licenseUrl = String.valueOf(license);
            }
        }
        licenseUrl = firstNonBlank(licenseUrl, object.optString("license_url", ""), object.optString("licenseUrl", ""));
        return new DrmJsonParts(licenseUrl, headers);
    }

    private List<AdaptiveChannel.ClearKey> parseClearKeysFromJson(String raw) throws Exception {
        String text = raw.trim();
        List<AdaptiveChannel.ClearKey> result = new ArrayList<>();
        JSONObject json = new JSONObject(text);
        JSONArray keys = json.optJSONArray("keys");
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                JSONObject key = keys.optJSONObject(i);
                if (key == null) continue;
                String kid = firstNonBlank(key.optString("kid", ""), key.optString("keyid", ""));
                String value = firstNonBlank(key.optString("k", ""), key.optString("key", ""));
                if (!kid.isEmpty() && !value.isEmpty()) {
                    result.add(clearKey(kid, value));
                }
            }
            return result;
        }

        JSONArray names = json.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String kid = names.optString(i, "");
                String value = json.optString(kid, "");
                if (kid.isEmpty() || value.isEmpty()) continue;
                try {
                    result.add(clearKey(kid, value));
                } catch (Exception ignored) {
                    // Ignore non-key metadata in ClearKey JSON objects.
                }
            }
        }
        return result;
    }

    private List<AdaptiveChannel.ClearKey> parseClearKeys(String raw) throws Exception {
        List<AdaptiveChannel.ClearKey> keys = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return keys;
        String text = raw.trim();
        if (text.startsWith("{")) return parseClearKeysFromJson(text);
        text = text.replace('\n', ',').replace(';', ',');
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String[] pieces = trimmed.split(":", 2);
            if (pieces.length != 2) continue;
            keys.add(clearKey(pieces[0].trim(), pieces[1].trim()));
        }
        return keys;
    }

    private AdaptiveChannel.ClearKey clearKey(String kid, String key) throws Exception {
        byte[] kidBytes = decodeHexOrBase64Url(kid);
        byte[] keyBytes = decodeHexOrBase64Url(key);
        return new AdaptiveChannel.ClearKey(
                toHex(kidBytes),
                toHex(keyBytes),
                Base64.encodeToString(kidBytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP),
                Base64.encodeToString(keyBytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP)
        );
    }

    private byte[] decodeHexOrBase64Url(String value) {
        String normalized = value.trim().replace("-", "").replace("urn:uuid:", "");
        if (normalized.matches("(?i)[0-9a-f]{32}")) {
            byte[] bytes = new byte[16];
            for (int i = 0; i < 16; i++) {
                bytes[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
            }
            return bytes;
        }
        return Base64.decode(value.trim(), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private String toHex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            result[i * 2] = HEX_CHARS[v >>> 4];
            result[i * 2 + 1] = HEX_CHARS[v & 0x0f];
        }
        return new String(result);
    }

    private String drmScheme(String licenseType) {
        if (licenseType.contains("clearkey") || licenseType.contains("org.w3.clearkey")) return "CLEARKEY";
        if (licenseType.contains("widevine") || licenseType.contains("com.widevine.alpha")) return "WIDEVINE";
        if (licenseType.contains("playready") || licenseType.contains("com.microsoft.playready")) return "PLAYREADY";
        return "";
    }

    private PendingEntry parseExtInf(String line) {
        int colon = line.indexOf(':');
        int comma = findFirstUnquotedComma(line);
        PendingEntry entry = new PendingEntry();
        if (comma >= 0 && comma + 1 < line.length()) {
            entry.name = line.substring(comma + 1).trim();
        }
        String metadata = colon >= 0 && comma > colon ? line.substring(colon + 1, comma) : "";
        Map<String, String> attrs = parseAttributes(metadata);
        entry.tvgId = attrs.getOrDefault("tvg-id", "");
        entry.logoUrl = attrs.getOrDefault("tvg-logo", "");
        entry.groupTitle = attrs.getOrDefault("group-title", "");
        if (entry.name.isEmpty()) {
            entry.name = firstNonBlank(attrs.get("tvg-name"), attrs.get("tvg-id"), "Adaptive Channel");
        }
        return entry;
    }

    private void applyKodiProp(PendingEntry entry, String raw) {
        int eq = raw.indexOf('=');
        if (eq <= 0) return;
        String key = raw.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        String value = raw.substring(eq + 1).trim();
        entry.props.put(key, stripQuotes(value));
    }

    private void applyVlcOpt(PendingEntry entry, String raw) {
        int eq = raw.indexOf('=');
        if (eq <= 0) return;
        String key = raw.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        String value = stripQuotes(raw.substring(eq + 1).trim());
        if ("http-user-agent".equals(key) || "user-agent".equals(key)) {
            entry.userAgent = value;
        } else if ("http-referer".equals(key) || "http-referrer".equals(key) || "referer".equals(key) || "referrer".equals(key)) {
            entry.headers.put("Referer", value);
        } else if ("http-origin".equals(key) || "origin".equals(key)) {
            entry.headers.put("Origin", value);
        } else if ("http-cookie".equals(key) || "cookie".equals(key)) {
            entry.headers.put("Cookie", value);
        } else if (key.startsWith("http-")) {
            String headerName = canonicalHeaderName(key.substring("http-".length()));
            if (isSafeHeaderName(headerName) && !value.isEmpty()) {
                entry.headers.put(headerName, value);
            }
        }
    }

    private List<String> extractHeaderEpgUrls(Map<String, String> attrs) {
        List<String> epgUrls = new ArrayList<>();
        addHeaderEpgUrls(epgUrls, attrs.get("x-tvg-url"));
        addHeaderEpgUrls(epgUrls, attrs.get("url-tvg"));
        addHeaderEpgUrls(epgUrls, attrs.get("tvg-url"));
        addHeaderEpgUrls(epgUrls, attrs.get("url-xml"));
        return epgUrls;
    }

    private void addHeaderEpgUrls(List<String> epgUrls, String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        for (String part : raw.split(",")) {
            String url = stripQuotes(part).trim();
            if (isHttpUrl(url) && !epgUrls.contains(url)) {
                epgUrls.add(url);
            }
        }
    }

    private void addDistinct(List<String> target, List<String> values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty() && !target.contains(value)) {
                target.add(value);
            }
        }
    }

    private UrlAndHeaders splitPipeHeaders(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        int pipe = value.indexOf('|');
        if (pipe < 0) return new UrlAndHeaders(value, new LinkedHashMap<>());
        String url = value.substring(0, pipe).trim();
        String headers = value.substring(pipe + 1);
        int secondPipe = headers.indexOf('|');
        if (secondPipe >= 0) headers = headers.substring(0, secondPipe);
        return new UrlAndHeaders(url, parseHeaderProperty(headers));
    }

    private Map<String, String> parseHeaderProperty(String raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) return headers;
        String normalized = raw.trim().replace("&amp;", "&");
        for (String part : normalized.split("&")) {
            String item = part.trim();
            if (item.isEmpty()) continue;
            int eq = item.indexOf('=');
            int colon = item.indexOf(':');
            int sep = eq >= 0 ? eq : colon;
            if (sep <= 0) continue;
            String name = decode(item.substring(0, sep).trim());
            String value = decode(item.substring(sep + 1).trim());
            if (isSafeHeaderName(name) && !value.isEmpty()) {
                headers.put(canonicalHeaderName(name), value);
            }
        }
        return headers;
    }

    private Map<String, String> parseAttributes(String text) {
        Map<String, String> attrs = new LinkedHashMap<>();
        String value = text == null ? "" : text;
        int index = 0;
        while (index < value.length()) {
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
            int keyStart = index;
            while (index < value.length() && value.charAt(index) != '=' && !Character.isWhitespace(value.charAt(index))) index++;
            if (index <= keyStart) break;
            String key = value.substring(keyStart, index).toLowerCase(Locale.ROOT);
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
            if (index >= value.length() || value.charAt(index) != '=') continue;
            index++;
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
            String parsed;
            if (index < value.length() && value.charAt(index) == '"') {
                index++;
                StringBuilder sb = new StringBuilder();
                while (index < value.length() && value.charAt(index) != '"') {
                    sb.append(value.charAt(index++));
                }
                if (index < value.length() && value.charAt(index) == '"') index++;
                parsed = sb.toString();
            } else {
                int start = index;
                while (index < value.length() && !Character.isWhitespace(value.charAt(index))) index++;
                parsed = value.substring(start, index);
            }
            attrs.put(key, parsed);
        }
        return attrs;
    }

    private int findFirstUnquotedComma(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            if (c == ',' && !quoted) return i;
        }
        return -1;
    }

    private Map<String, String> jsonToMap(JSONObject json) {
        Map<String, String> map = new LinkedHashMap<>();
        JSONArray names = json.names();
        if (names == null) return map;
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i, "");
            String value = json.optString(name, "");
            if (isSafeHeaderName(name) && !value.isEmpty()) map.put(canonicalHeaderName(name), value);
        }
        return map;
    }

    private String stableId(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        char[] result = new char[20];
        for (int i = 0; i < 10; i++) {
            int v = digest[i] & 0xff;
            result[i * 2] = HEX_CHARS[v >>> 4];
            result[i * 2 + 1] = HEX_CHARS[v & 0x0f];
        }
        return new String(result);
    }

    private String inferManifestType(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".mpd")) return "mpd";
        if (lower.contains(".m3u8")) return "hls";
        if (lower.contains(".isml") || lower.contains(".ism/manifest") || lower.contains(".isml/manifest")) return "ism";
        if (lower.contains(".ism") || lower.contains("manifest")) return "ism";
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private boolean looksLikeHttp(String value) {
        String lower = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private boolean isHttpUrl(String value) {
        return looksLikeHttp(value);
    }

    private String sanitize(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("\uFEFF")) value = value.substring(1);
        return value;
    }

    private String stripQuotes(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private boolean isSafeHeaderName(String value) {
        return value != null && value.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+");
    }

    private String canonicalHeaderName(String value) {
        if ("user-agent".equalsIgnoreCase(value)) return "User-Agent";
        if ("referer".equalsIgnoreCase(value)) return "Referer";
        if ("origin".equalsIgnoreCase(value)) return "Origin";
        return value;
    }

    private static final class PendingEntry {
        String name = "";
        String groupTitle = "";
        String tvgId = "";
        String logoUrl = "";
        String userAgent = "";
        final Map<String, String> props = new LinkedHashMap<>();
        final Map<String, String> headers = new LinkedHashMap<>();
    }

    static final class ParsedM3u {
        final List<AdaptiveChannel> channels;
        final List<String> epgUrls;

        ParsedM3u(List<AdaptiveChannel> channels, List<String> epgUrls) {
            this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
            this.epgUrls = Collections.unmodifiableList(new ArrayList<>(epgUrls));
        }
    }

    private static final class UrlAndHeaders {
        final String url;
        final Map<String, String> headers;

        UrlAndHeaders(String url, Map<String, String> headers) {
            this.url = url == null ? "" : url;
            this.headers = headers;
        }
    }

    private static final class DrmJsonParts {
        final String licenseUrl;
        final Map<String, String> headers;

        DrmJsonParts(String licenseUrl, Map<String, String> headers) {
            this.licenseUrl = licenseUrl == null ? "" : licenseUrl;
            this.headers = headers;
        }
    }
}
