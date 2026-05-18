package com.streamvault.plugin.adaptivebridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AdaptiveCatalog {
    final List<AdaptiveChannel> channels;
    final List<String> epgUrls;
    final Map<String, AdaptiveChannel> channelsById;
    final String message;

    AdaptiveCatalog(List<AdaptiveChannel> channels, String message) {
        this(channels, Collections.emptyList(), message);
    }

    AdaptiveCatalog(List<AdaptiveChannel> channels, List<String> epgUrls, String message) {
        this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
        this.epgUrls = Collections.unmodifiableList(new ArrayList<>(epgUrls));
        Map<String, AdaptiveChannel> map = new LinkedHashMap<>();
        for (AdaptiveChannel channel : channels) {
            map.put(channel.id, channel);
        }
        this.channelsById = Collections.unmodifiableMap(map);
        this.message = message == null ? "" : message;
    }

    AdaptiveChannel channel(String id) {
        return channelsById.get(id);
    }
}
