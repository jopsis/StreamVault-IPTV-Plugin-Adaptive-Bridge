package com.streamvault.plugin.adaptivebridge;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.parsers.DocumentBuilderFactory;

final class AdaptiveBridge implements AdaptiveLocalServer.Handler {
    private static final AtomicReference<AdaptiveBridge> INSTANCE = new AtomicReference<>();
    private static final String VERSION_NAME = "1.1.20-beta.2";
    private static final int VERSION_CODE = 24;
    private static final String DEFAULT_USER_AGENT = "StreamVault-AdaptiveBridge/" + VERSION_NAME;
    private static final long MANIFEST_FRESH_CACHE_MS = 3_000L;
    private static final long MANIFEST_STALE_FALLBACK_MS = 30_000L;

    private static final DocumentBuilderFactory DASH_FACTORY;

    static {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
        }
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (Exception ignored) {
        }
        try {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
        }
        DASH_FACTORY = factory;
    }

    private final Context context;
    private final AdaptiveSettings settings;
    private final KodiPropsM3uParser parser = new KodiPropsM3uParser();
    private final AdaptiveLocalServer server = new AdaptiveLocalServer(this);
    private final Map<String, Boolean> dashClearKeyNoHeaderMode = new ConcurrentHashMap<>();
    private final Map<String, ManifestResourceBase> manifestResourceBases = new ConcurrentHashMap<>();
    private final Map<String, CachedManifest> manifestCache = new ConcurrentHashMap<>();
    private final AtomicReference<AdaptiveCatalog> catalogRef = new AtomicReference<>(new AdaptiveCatalog(new ArrayList<>(), ""));
    private volatile long cacheTimeMs;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final ExecutorService sourcesFetchExecutor = Executors.newCachedThreadPool();

    private AdaptiveBridge(Context context) {
        this.context = context.getApplicationContext();
        this.settings = new AdaptiveSettings(context);
    }

    static AdaptiveBridge get(Context context) {
        AdaptiveBridge bridge = INSTANCE.get();
        if (bridge != null) return bridge;
        AdaptiveBridge created = new AdaptiveBridge(context);
        if (INSTANCE.compareAndSet(null, created)) return created;
        return INSTANCE.get();
    }

    synchronized void start() throws Exception {
        server.start();
    }

    synchronized void stop() {
        server.stop();
        settings.setEnabled(false);
    }

    void setEnabled(boolean enabled) throws Exception {
        settings.setEnabled(enabled);
        if (enabled) {
            start();
            refreshCatalog(true);
        } else {
            stop();
        }
    }

    boolean isEnabled() {
        return settings.enabled();
    }

    String baseUrl() throws Exception {
        start();
        return server.baseUrl();
    }

    String providerUrl() throws Exception {
        return baseUrl() + "/playlist.m3u";
    }

    String epgUrl() throws Exception {
        return baseUrl() + "/epg.xml";
    }

    boolean ownsUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return (lower.startsWith("http://127.0.0.1:") || lower.startsWith("http://localhost:")) &&
                lower.contains("/play/");
    }

    String channelIdFromUrl(String url) {
        if (url == null) return "";
        int marker = url.indexOf("/play/");
        if (marker < 0) return "";
        String id = url.substring(marker + "/play/".length());
        int query = id.indexOf('?');
        if (query >= 0) id = id.substring(0, query);
        return id;
    }

    AdaptiveCatalog refreshCatalog(boolean force) throws Exception {
        long now = System.currentTimeMillis();
        AdaptiveCatalog current = catalogRef.get();
        if (!force && now - cacheTimeMs < 60_000L && current != null) {
            return current;
        }
        refreshLock.lock();
        try {
            now = System.currentTimeMillis();
            current = catalogRef.get();
            if (!force && now - cacheTimeMs < 60_000L && current != null) {
                return current;
            }
            return doRefreshCatalog(now);
        } finally {
            refreshLock.unlock();
        }
    }

    private AdaptiveCatalog doRefreshCatalog(long now) throws Exception {
        List<SourceLine> sources = parseSourceLines(settings.sourceUrls());
        List<Callable<SourceResult>> tasks = new ArrayList<>();
        for (SourceLine source : sources) {
            tasks.add(() -> {
                try {
                    String text = readSourceText(source.url);
                    KodiPropsM3uParser.ParsedM3u parsed = parser.parseWithMetadata(source.name, text);
                    String msg = source.name + ": " + parsed.channels.size() + " channel(s)";
                    if (!parsed.epgUrls.isEmpty()) {
                        msg += ", " + parsed.epgUrls.size() + " EPG URL(s)";
                    }
                    return new SourceResult(parsed.channels, parsed.epgUrls, msg);
                } catch (Exception error) {
                    return new SourceResult(new ArrayList<>(), new ArrayList<>(),
                            source.name + ": " + error.getMessage());
                }
            });
        }

        List<AdaptiveChannel> channels = new ArrayList<>();
        List<String> epgUrls = new ArrayList<>();
        List<String> messages = new ArrayList<>();

        if (!tasks.isEmpty()) {
            List<Future<SourceResult>> futures = sourcesFetchExecutor.invokeAll(tasks, 60, TimeUnit.SECONDS);
            for (Future<SourceResult> future : futures) {
                try {
                    SourceResult result = future.get();
                    channels.addAll(result.channels);
                    addDistinct(epgUrls, result.epgUrls);
                    messages.add(result.message);
                } catch (Exception ignored) {
                    messages.add("source: fetch cancelled");
                }
            }
        }

        String summary = channels.size() + " adaptive channel(s)";
        if (!messages.isEmpty()) summary += " / " + String.join(" / ", messages);
        AdaptiveCatalog catalog = new AdaptiveCatalog(channels, epgUrls, summary);
        catalogRef.set(catalog);
        cacheTimeMs = now;
        settings.setLastCatalogStatus(channels.size(), summary);
        return catalog;
    }

    int cachedChannelCount() {
        AdaptiveCatalog catalog = catalogRef.get();
        if (cacheTimeMs > 0L && catalog != null) {
            return catalog.channels.size();
        }
        return settings.lastChannelCount();
    }

    String cachedMessage() {
        AdaptiveCatalog catalog = catalogRef.get();
        if (cacheTimeMs > 0L && catalog != null && !catalog.message.trim().isEmpty()) {
            return catalog.message;
        }
        String lastMessage = settings.lastMessage();
        if (lastMessage != null && !lastMessage.trim().isEmpty()) {
            return lastMessage;
        }
        return isEnabled() ? context.getString(R.string.message_enabled) : context.getString(R.string.message_disabled);
    }

    AdaptiveChannel prepareChannel(String inputUrl) throws Exception {
        start();
        String id = channelIdFromUrl(inputUrl);
        if (id.isEmpty()) return null;
        AdaptiveCatalog catalog = refreshCatalog(false);
        AdaptiveChannel channel = catalog.channel(id);
        if (channel == null) {
            catalog = refreshCatalog(true);
            channel = catalog.channel(id);
        }
        if (channel != null) {
            resolveDashClearKeyHeaderMode(channel);
        }
        return channel;
    }

    JSONObject manifestJson() throws Exception {
        return new JSONObject()
                .put("schemaVersion", 1)
                .put("id", "com.streamvault.plugins.adaptivebridge")
                .put("name", "StreamVault Adaptive Bridge")
                .put("versionName", VERSION_NAME)
                .put("versionCode", VERSION_CODE)
                .put("description", context.getString(R.string.plugin_description))
                .put("providerName", context.getString(R.string.provider_name))
                .put("configurationMode", "activity")
                .put("configurationActivityAction", "com.streamvault.plugin.adaptivebridge.CONFIGURE")
                .put("capabilities", new JSONArray()
                        .put("provider.m3u")
                        .put("playback.prepare")
                        .put("configuration.activity"));
    }

    JSONObject configurationSchema() throws Exception {
        JSONArray sections = new JSONArray()
                .put(new JSONObject()
                        .put("id", "status")
                        .put("title", "Status")
                        .put("description", "Runtime information.")
                        .put("fields", new JSONArray()
                                .put(infoField("status", "Status"))
                                .put(infoField("providerUrl", "Provider URL"))
                                .put(infoField("channelCount", "Channels"))
                                .put(infoField("lastMessage", "Last message"))))
                .put(new JSONObject()
                        .put("id", "sources")
                        .put("title", "Sources")
                        .put("description", "M3U sources with Kodi inputstream.adaptive KODIPROP metadata.")
                        .put("fields", new JSONArray()
                                .put(new JSONObject()
                                        .put("key", "source_urls")
                                        .put("type", "textarea")
                                        .put("label", "M3U source URLs or files")
                                        .put("description", "One source per line. Use URL, file:// URI, content:// URI, absolute file path, or Name|source."))));
        JSONArray actions = new JSONArray()
                .put(new JSONObject()
                        .put("id", "refresh")
                        .put("label", "Refresh sources")
                        .put("description", "Download and parse configured M3U lists.")
                        .put("refreshAfterRun", true));
        return new JSONObject()
                .put("schemaVersion", 1)
                .put("title", "StreamVault Adaptive Bridge")
                .put("description", "Imports Kodi-style inputstream.adaptive M3U lists for StreamVault playback.")
                .put("sections", sections)
                .put("actions", actions);
    }

    JSONObject configurationValues() throws Exception {
        String status = isEnabled() ? context.getString(R.string.status_ready) : context.getString(R.string.status_disabled);
        return settings.values(providerUrl(), cachedChannelCount(), status);
    }

    void saveConfiguration(String rawValues) throws Exception {
        JSONObject values = new JSONObject(rawValues == null || rawValues.trim().isEmpty() ? "{}" : rawValues);
        settings.save(values);
        refreshCatalog(true);
    }

    String runAction(String actionId) throws Exception {
        if ("refresh".equals(actionId)) {
            AdaptiveCatalog catalog = refreshCatalog(true);
            return context.getString(R.string.message_refresh_ok) + " " + catalog.channels.size() + " channel(s).";
        }
        throw new IllegalArgumentException(context.getString(R.string.message_unknown_action));
    }

    @Override
    public String playlist() throws Exception {
        AdaptiveCatalog catalog = refreshCatalog(false);
        StringBuilder m3u = new StringBuilder("#EXTM3U");
        m3u.append(" x-tvg-url=\"").append(escapeAttr(epgUrl())).append('"');
        m3u.append('\n');
        for (AdaptiveChannel channel : catalog.channels) {
            if (channel.hasDrm()) {
                m3u.append("#KODIPROP:inputstream=inputstream.adaptive\n");
                if (!channel.manifestType.isEmpty()) {
                    m3u.append("#KODIPROP:inputstream.adaptive.manifest_type=").append(escapeLine(channel.manifestType)).append('\n');
                }
            }
            m3u.append("#EXTINF:-1");
            if (!channel.tvgId.isEmpty()) m3u.append(" tvg-id=\"").append(escapeAttr(channel.tvgId)).append('"');
            if (!channel.logoUrl.isEmpty()) m3u.append(" tvg-logo=\"").append(escapeAttr(channel.logoUrl)).append('"');
            if (!channel.groupTitle.isEmpty()) m3u.append(" group-title=\"").append(escapeAttr(channel.groupTitle)).append('"');
            m3u.append(',').append(escapeLine(channel.name)).append('\n');
            m3u.append(baseUrl()).append("/play/").append(channel.id).append('\n');
        }
        return m3u.toString();
    }

    @Override
    public String epgLocation() throws Exception {
        AdaptiveCatalog catalog = refreshCatalog(false);
        return catalog.epgUrls.isEmpty() ? "" : catalog.epgUrls.get(0);
    }

    @Override
    public AdaptiveChannel channel(String id) throws Exception {
        return refreshCatalog(false).channel(id);
    }

    @Override
    public String manifest(String id) throws Exception {
        AdaptiveChannel channel = channel(id);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found");
        }
        resolveDashClearKeyHeaderMode(channel);
        DownloadResult manifest = cachedOrDownloadManifest(channel);
        rememberManifestResourceBase(channel, manifest.finalUrl);
        if (channel.needsDashClearKeyManifestProxy() &&
                !usesNoHeaderDashMode(channel) &&
                dashResourcesPreferNoHeaderMode(channel, manifest.text, manifest.finalUrl)) {
            dashClearKeyNoHeaderMode.put(channel.id, true);
        }
        return channel.rewriteManifestForPlayback(manifest.text);
    }

    @Override
    public String manifestResourceLocation(String id, String suffix) throws Exception {
        AdaptiveChannel channel = channel(id);
        if (channel == null) return "";
        ManifestResourceBase resolvedBase = manifestResourceBases.get(id);
        if (resolvedBase != null) {
            return resolvedBase.resourceLocation(suffix);
        }
        return channel.originUrlForManifestProxyPath(suffix);
    }

    @Override
    public AdaptiveLocalServer.ProxiedResource manifestResource(String id, String suffix, Map<String, String> requestHeaders) throws Exception {
        AdaptiveChannel channel = channel(id);
        if (channel == null || (!usesNoHeaderDashMode(channel) && !channel.hasStreamRequestHeaders())) return null;
        String location = manifestResourceLocation(id, suffix);
        if (location == null || location.trim().isEmpty()) return null;
        return openManifestResource(channel, location, requestHeaders);
    }

    @Override
    public JSONObject statusJson() throws Exception {
        return new JSONObject()
                .put("enabled", isEnabled())
                .put("channels", cachedChannelCount())
                .put("message", cachedMessage());
    }

    private JSONObject infoField(String key, String label) throws Exception {
        return new JSONObject()
                .put("key", key)
                .put("type", "info")
                .put("label", label)
                .put("readOnly", true);
    }

    private List<SourceLine> parseSourceLines(String raw) {
        List<SourceLine> sources = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return sources;
        String[] lines = raw.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String name = "Source " + (i + 1);
            String url = line;
            int pipe = line.indexOf('|');
            if (pipe > 0) {
                name = line.substring(0, pipe).trim();
                url = line.substring(pipe + 1).trim();
            }
            if (isSupportedSource(url)) {
                sources.add(new SourceLine(name.isEmpty() ? "Source " + (i + 1) : name, url));
            }
        }
        return sources;
    }

    private void addDistinct(List<String> target, List<String> values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty() && !target.contains(value)) {
                target.add(value);
            }
        }
    }

    private boolean isSupportedSource(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("file://") ||
                lower.startsWith("content://") ||
                (value != null && value.startsWith("/"));
    }

    private String readSourceText(String source) throws Exception {
        String lower = source == null ? "" : source.toLowerCase(Locale.ROOT);
        if (lower.startsWith("content://")) {
            try (InputStream input = context.getContentResolver().openInputStream(Uri.parse(source))) {
                if (input == null) throw new IllegalStateException("Unable to open content URI");
                return readAllText(input);
            }
        }
        if (lower.startsWith("file://")) {
            try (InputStream input = new URL(source).openStream()) {
                return readAllText(input);
            }
        }
        if (source != null && source.startsWith("/")) {
            try (InputStream input = new FileInputStream(new File(source))) {
                return readAllText(input);
            }
        }
        return downloadText(source);
    }

    Map<String, String> playbackHeaders(AdaptiveChannel channel) {
        if (usesNoHeaderDashMode(channel)) return java.util.Collections.emptyMap();
        return channel.streamHeaders;
    }

    String playbackUserAgent(AdaptiveChannel channel) {
        if (usesNoHeaderDashMode(channel)) return "";
        return channel.streamUserAgent;
    }

    private Map<String, String> manifestHeaders(AdaptiveChannel channel) {
        if (usesNoHeaderDashMode(channel)) return java.util.Collections.emptyMap();
        return channel.manifestHeaders;
    }

    private String manifestUserAgent(AdaptiveChannel channel) {
        if (usesNoHeaderDashMode(channel)) return "";
        return channel.manifestUserAgent;
    }

    private boolean usesNoHeaderDashMode(AdaptiveChannel channel) {
        return channel != null && Boolean.TRUE.equals(dashClearKeyNoHeaderMode.get(channel.id));
    }

    private DownloadResult downloadManifest(AdaptiveChannel channel) throws Exception {
        if (!channel.needsDashClearKeyManifestProxy()) {
            return downloadTextResult(channel.manifestUrl, manifestHeaders(channel), manifestUserAgent(channel));
        }
        boolean preferredNoHeader = usesNoHeaderDashMode(channel);
        boolean[] modes = preferredNoHeader ? new boolean[]{true, false} : new boolean[]{false, true};
        Exception firstError = null;
        for (boolean noHeaderMode : modes) {
            try {
                DownloadResult result = downloadTextResult(
                        channel.manifestUrl,
                        noHeaderMode ? java.util.Collections.emptyMap() : channel.manifestHeaders,
                        noHeaderMode ? "" : channel.manifestUserAgent
                );
                dashClearKeyNoHeaderMode.put(channel.id, noHeaderMode);
                return result;
            } catch (Exception error) {
                if (firstError == null) firstError = error;
            }
        }
        throw firstError == null ? new IllegalStateException("Manifest download failed") : firstError;
    }

    private DownloadResult cachedOrDownloadManifest(AdaptiveChannel channel) throws Exception {
        long now = System.currentTimeMillis();
        CachedManifest cached = manifestCache.get(channel.id);
        if (cached != null && cached.isFresh(now)) {
            return cached.result;
        }
        try {
            DownloadResult result = downloadManifest(channel);
            manifestCache.put(channel.id, new CachedManifest(result, now));
            return result;
        } catch (Exception error) {
            if (cached != null && cached.canFallback(now)) {
                return cached.result;
            }
            throw error;
        }
    }

    private void resolveDashClearKeyHeaderMode(AdaptiveChannel channel) {
        if (channel == null || !channel.needsDashClearKeyManifestProxy()) return;
        if (dashClearKeyNoHeaderMode.containsKey(channel.id)) return;
        if (httpStatus(channel.manifestUrl, channel.manifestHeaders, channel.manifestUserAgent) < 400) {
            dashClearKeyNoHeaderMode.put(channel.id, dashResourcesPreferNoHeaderMode(channel));
            return;
        }
        boolean noHeadersWorks = httpStatus(channel.manifestUrl, java.util.Collections.emptyMap(), "") < 400;
        dashClearKeyNoHeaderMode.put(channel.id, noHeadersWorks);
    }

    private boolean dashResourcesPreferNoHeaderMode(AdaptiveChannel channel) {
        try {
            DownloadResult manifest = downloadTextResult(channel.manifestUrl, channel.manifestHeaders, channel.manifestUserAgent);
            return dashResourcesPreferNoHeaderMode(channel, manifest.text, manifest.finalUrl);
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean dashResourcesPreferNoHeaderMode(AdaptiveChannel channel, String manifest, String finalUrl) {
        try {
            ManifestResourceBase resourceBase = ManifestResourceBase.fromManifestUrl(
                    firstNonBlank(finalUrl, channel.manifestUrl)
            );
            for (String resource : dashInitializationResources(manifest, 10)) {
                String resourceUrl = resourceBase.resourceLocation(resource);
                int withHeaders = httpStatus(resourceUrl, channel.streamHeaders, channel.streamUserAgent);
                if (withHeaders < 400) continue;
                int withoutHeaders = httpStatus(resourceUrl, java.util.Collections.emptyMap(), "");
                if (withoutHeaders < 400) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private List<String> dashInitializationResources(String manifest, int maxResources) throws Exception {
        List<String> resources = new ArrayList<>();
        if (manifest == null || manifest.trim().isEmpty() || maxResources <= 0) return resources;

        Document document = DASH_FACTORY.newDocumentBuilder()
                .parse(new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)));
        NodeList representations = document.getElementsByTagNameNS("*", "Representation");
        for (int i = 0; i < representations.getLength() && resources.size() < maxResources; i++) {
            Node node = representations.item(i);
            if (!(node instanceof Element)) continue;
            Element representation = (Element) node;
            Element segmentTemplate = nearestSegmentTemplate(representation);
            if (segmentTemplate == null) continue;
            String initialization = segmentTemplate.getAttribute("initialization");
            if (initialization == null || initialization.trim().isEmpty()) continue;
            String resource = expandDashTemplate(initialization, representation);
            if (resource.isEmpty() || resource.contains("$") || resources.contains(resource)) continue;
            resources.add(resource);
        }
        return resources;
    }

    private Element nearestSegmentTemplate(Element representation) {
        Node current = representation;
        while (current != null) {
            if (current instanceof Element) {
                Element template = directChildElement((Element) current, "SegmentTemplate");
                if (template != null) return template;
            }
            current = current.getParentNode();
        }
        return null;
    }

    private Element directChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && localName.equals(child.getLocalName())) {
                return (Element) child;
            }
        }
        return null;
    }

    private String expandDashTemplate(String template, Element representation) {
        String resource = template == null ? "" : template.trim();
        if (resource.isEmpty()) return "";
        resource = resource.replace("$RepresentationID$", representation.getAttribute("id"));
        resource = resource.replace("$Bandwidth$", representation.getAttribute("bandwidth"));
        return resource;
    }

    private int httpStatus(String url, Map<String, String> headers, String userAgent) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(3_000);
            connection.setReadTimeout(5_000);
            applyRequestHeaders(connection, headers, userAgent);
            int code = connection.getResponseCode();
            closeQuietly(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            return code;
        } catch (Exception ignored) {
            return 599;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private AdaptiveLocalServer.ProxiedResource openManifestResource(AdaptiveChannel channel, String url, Map<String, String> requestHeaders) throws Exception {
        Map<String, String> upstreamHeaders = proxyRequestHeaders(requestHeaders);
        if (!usesNoHeaderDashMode(channel)) {
            Map<String, String> streamHeaders = new java.util.LinkedHashMap<>(channel.streamHeaders);
            streamHeaders.putAll(upstreamHeaders);
            upstreamHeaders = streamHeaders;
        }
        String upstreamUserAgent = usesNoHeaderDashMode(channel) ? "" : channel.streamUserAgent;
        HttpURLConnection connection = openManifestResourceConnection(url, upstreamHeaders, upstreamUserAgent);
        int code = connection.getResponseCode();
        if (code >= 400 && !upstreamHeaders.isEmpty()) {
            closeQuietly(connection.getErrorStream());
            connection.disconnect();
            connection = openManifestResourceConnection(url, java.util.Collections.emptyMap(), "");
            code = connection.getResponseCode();
        }
        long[] retryDelays = new long[]{250L, 750L};
        for (long retryDelay : retryDelays) {
            if (!isTransientResourceStatus(code)) break;
            closeQuietly(connection.getErrorStream());
            connection.disconnect();
            Thread.sleep(retryDelay);
            connection = openManifestResourceConnection(url, java.util.Collections.emptyMap(), "");
            code = connection.getResponseCode();
        }
        InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) input = new ByteArrayInputStream(new byte[0]);
        return new AdaptiveLocalServer.ProxiedResource(
                code,
                connection.getContentType(),
                connection.getContentLengthLong(),
                responseHeaders(connection),
                input,
                connection::disconnect
        );
    }

    private boolean isTransientResourceStatus(int code) {
        return code == 403 || code == 404 || code == 408 || code == 425 || code == 429 || code >= 500;
    }

    private HttpURLConnection openManifestResourceConnection(String url, Map<String, String> headers, String userAgent) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        applyRequestHeaders(connection, headers, userAgent);
        return connection;
    }

    private Map<String, String> proxyRequestHeaders(Map<String, String> requestHeaders) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        copyRequestHeader(requestHeaders, headers, "range", "Range");
        copyRequestHeader(requestHeaders, headers, "if-range", "If-Range");
        return headers;
    }

    private void copyRequestHeader(Map<String, String> source, Map<String, String> target, String sourceName, String targetName) {
        if (source == null) return;
        String value = source.get(sourceName);
        if (value != null && !value.trim().isEmpty()) {
            target.put(targetName, value.trim());
        }
    }

    private Map<String, String> responseHeaders(HttpURLConnection connection) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        Map<String, List<String>> fields = connection.getHeaderFields();
        if (fields == null) return headers;
        for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
            String name = entry.getKey();
            List<String> values = entry.getValue();
            if (name == null || values == null || values.isEmpty()) continue;
            String value = values.get(0);
            if (value != null && !value.trim().isEmpty()) {
                headers.put(name, value.trim());
            }
        }
        return headers;
    }

    private String downloadText(String url) throws Exception {
        return downloadText(url, null, DEFAULT_USER_AGENT);
    }

    private String downloadText(String url, Map<String, String> headers, String userAgent) throws Exception {
        return downloadTextResult(url, headers, userAgent).text;
    }

    private DownloadResult downloadTextResult(String url, Map<String, String> headers, String userAgent) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        applyRequestHeaders(connection, headers, userAgent);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            closeQuietly(connection.getErrorStream());
            throw new IllegalStateException("HTTP " + code);
        }
        try (InputStream input = connection.getInputStream()) {
            return new DownloadResult(readAllText(input), connection.getURL().toString());
        } finally {
            connection.disconnect();
        }
    }

    private void rememberManifestResourceBase(AdaptiveChannel channel, String finalUrl) {
        if (channel == null || !channel.needsManifestProxy()) return;
        manifestResourceBases.put(channel.id, ManifestResourceBase.fromManifestUrl(
                firstNonBlank(finalUrl, channel.manifestUrl)
        ));
    }

    private String firstNonBlank(String first, String fallback) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        return fallback == null ? "" : fallback.trim();
    }

    private void applyRequestHeaders(HttpURLConnection connection, Map<String, String> headers, String userAgent) {
        String effectiveUserAgent = userAgent == null ? "" : userAgent.trim();
        if ((effectiveUserAgent.isEmpty()) && headers != null) {
            String headerUserAgent = headers.get("User-Agent");
            if (headerUserAgent != null) effectiveUserAgent = headerUserAgent.trim();
        }
        if (!effectiveUserAgent.isEmpty()) {
            connection.setRequestProperty("User-Agent", effectiveUserAgent);
        }
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                if ("User-Agent".equalsIgnoreCase(entry.getKey())) continue;
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    private void closeQuietly(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (Exception ignored) {
        }
    }

    private String readAllText(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        int total = 0;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > 20 * 1024 * 1024) {
                throw new IllegalStateException("M3U is larger than 20 MB");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private String escapeAttr(String value) {
        return value == null ? "" : value.replace("\"", "'");
    }

    private String escapeLine(String value) {
        return value == null ? "" : value.replace("\r", " ").replace("\n", " ").trim();
    }

    static JSONObject mapToJson(Map<String, String> map) throws Exception {
        JSONObject json = new JSONObject();
        if (map == null) return json;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                json.put(entry.getKey(), entry.getValue());
            }
        }
        return json;
    }

    private static final class SourceLine {
        final String name;
        final String url;

        SourceLine(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private static final class SourceResult {
        final List<AdaptiveChannel> channels;
        final List<String> epgUrls;
        final String message;

        SourceResult(List<AdaptiveChannel> channels, List<String> epgUrls, String message) {
            this.channels = channels;
            this.epgUrls = epgUrls;
            this.message = message;
        }
    }

    private static final class DownloadResult {
        final String text;
        final String finalUrl;

        DownloadResult(String text, String finalUrl) {
            this.text = text == null ? "" : text;
            this.finalUrl = finalUrl == null ? "" : finalUrl;
        }
    }

    private static final class CachedManifest {
        final DownloadResult result;
        final long timeMs;

        CachedManifest(DownloadResult result, long timeMs) {
            this.result = result;
            this.timeMs = timeMs;
        }

        boolean isFresh(long now) {
            return result != null && now - timeMs >= 0L && now - timeMs <= MANIFEST_FRESH_CACHE_MS;
        }

        boolean canFallback(long now) {
            return result != null && now - timeMs >= 0L && now - timeMs <= MANIFEST_STALE_FALLBACK_MS;
        }
    }

    private static final class ManifestResourceBase {
        final String baseUrl;
        final String query;

        private ManifestResourceBase(String baseUrl, String query) {
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.query = query == null ? "" : query;
        }

        static ManifestResourceBase fromManifestUrl(String manifestUrl) {
            String normalized = manifestUrl == null ? "" : manifestUrl.trim();
            int hash = normalized.indexOf('#');
            if (hash >= 0) normalized = normalized.substring(0, hash);

            String query = "";
            int queryIndex = normalized.indexOf('?');
            if (queryIndex >= 0) {
                query = normalized.substring(queryIndex + 1);
                normalized = normalized.substring(0, queryIndex);
            }

            String lower = normalized.toLowerCase(Locale.ROOT);
            int manifestIndex = lower.lastIndexOf("/manifest");
            String base;
            if (manifestIndex > 0) {
                base = normalized.substring(0, manifestIndex);
            } else {
                int slash = normalized.lastIndexOf('/');
                base = slash > 0 ? normalized.substring(0, slash) : normalized;
            }
            return new ManifestResourceBase(base, query);
        }

        String resourceLocation(String suffix) {
            String normalizedSuffix = suffix == null ? "" : suffix.trim();
            while (normalizedSuffix.startsWith("/")) {
                normalizedSuffix = normalizedSuffix.substring(1);
            }
            String location = normalizedSuffix.isEmpty() ? baseUrl : baseUrl + "/" + normalizedSuffix;
            if (!query.isEmpty()) {
                location += "?" + query;
            }
            return location;
        }
    }
}
