# Changelog

## Unreleased - 2026-05-18

### Changed

- Matched Kodi `inputstream.adaptive` header scoping more closely: `common_headers` apply everywhere, `manifest_headers` are used for manifest downloads, and `stream_headers` are used for media resources.
- Proxied DASH ClearKey media resources when stream-scoped headers are present, so playback no longer depends on the host player preserving those headers across local manifest redirects.
- Rewrote the public README and changelog in English for a broader audience.
- Removed the duplicate misspelled `docs/Chagelog.md` file.
- Updated repository guidance to use only the canonical `docs/Changelog.md`.

## 1.1.20-beta.1 - 2026-05-18

### Changed

- Renamed the project to StreamVault Adaptive Bridge.
- Changed the Android package to `com.streamvault.plugin.adaptivebridge` and the plugin ID to `com.streamvault.plugins.adaptivebridge`.
- Renamed the configuration activity to `AdaptiveBridgeConfigActivity` and the IPC service to `StreamVaultAdaptiveBridgePluginService`.
- Updated GitHub Actions so signed releases can be published to the `stable` or `beta` channel from `workflow_dispatch`.
- Published `versionName` values with a `-beta` suffix as GitHub prereleases, kept them out of the latest release slot, and generated the `StreamVault-Adaptive-Bridge-beta.apk` alias.
- Updated the beta plugin version to `1.1.20-beta.1` (`versionCode` 23).

### Validation

- Completed `graphify update .` after the rename.
- Completed the debug build and confirmed version `1.1.20-beta.1` (`versionCode` 23).
- Installed the APK on a Chromecast test device and verified package `com.streamvault.plugin.adaptivebridge`.
- Opened `AdaptiveBridgeConfigActivity` on the test device and verified `StreamVaultAdaptiveBridgePluginService` discovery through `com.streamvault.plugin.API`.

## 1.1.18 - 2026-05-14

### Changed

- Downloaded M3U sources in parallel with `ExecutorService.invokeAll`, reducing catalog refresh time from N x T to T for N sources.
- Updated `refreshCatalog` so cache reads such as `cachedChannelCount` and `cachedMessage` are not blocked by refresh work; the catalog now uses `AtomicReference`, and refresh exclusion uses a dedicated `ReentrantLock`.
- Initialized the DASH `DocumentBuilderFactory` once as a static field, avoiding service-loader lookup on each playback.
- Reduced ClearKey negotiation HTTP test timeouts to 3 s connect / 5 s read for faster first play on DASH + ClearKey channels.
- Changed the local HTTP server thread pool to bounded `newFixedThreadPool(6)` instead of `newCachedThreadPool`.
- Optimized `toHex` and `stableId` in the M3U parser to use a character table instead of per-byte `String.format`.
- Updated the plugin version to `1.1.18` (`versionCode` 21).

## 1.1.17 - 2026-05-14

### Fixed

- Generated playlists now advertise a canonical local `x-tvg-url` (`/epg.xml`) so StreamVault syncs EPG data through the plugin itself.
- The local server now exposes `/epg.xml`, redirects to the first XMLTV URL declared by the M3U sources, and returns a valid empty XMLTV document when no EPG is available.
- Updated the plugin version to `1.1.17` (`versionCode` 20).

### Validation

- Completed the debug build.
- Installed APK `1.1.17` (`versionCode` 20) on a Chromecast test device.
- Verified that `/playlist.m3u` advertises `#EXTM3U x-tvg-url="http://127.0.0.1:39078/epg.xml"`.
- Verified that `/epg.xml` responds with `302` to the first declared XMLTV source and returns valid XMLTV when following the redirect.
- Verified StreamVault plugin detection, activation, and provider sync with a user-supplied playlist.

## 1.1.16 - 2026-05-13

### Fixed

- Re-emitted embedded EPG URLs from `#EXTM3U` using only the canonical `x-tvg-url` attribute in `/playlist.m3u`, while still reading aliases such as `url-tvg`, `tvg-url`, and `url-xml`.
- Updated the plugin version to `1.1.16` (`versionCode` 19).

### Validation

- Completed the debug build.
- Installed APK `1.1.16` (`versionCode` 19) on a Chromecast test device and validated `/playlist.m3u`; the header is served as `#EXTM3U x-tvg-url="..."`.
- Confirmed that the matching StreamVault host downloaded the playlist; host-side EPG persistence remained a separate follow-up.

## 1.1.15 - 2026-05-13

### Fixed

- Preserved EPG URLs declared in the M3U header (`x-tvg-url`, `url-tvg`, `tvg-url`, or `url-xml`) and re-emitted them in `/playlist.m3u` so StreamVault can attach them to the provider.
- Deduplicated EPG URLs when combining multiple sources and showed only the count in catalog status, without exposing the URLs.
- Updated the plugin version to `1.1.15` (`versionCode` 18).

### Validation

- Completed the debug build.

## 1.1.14 - 2026-05-13

### Changed

- Removed inline M3U configuration. The plugin now accepts M3U sources only by URL, file, absolute path, or `content://` URI.
- Removed `inline_m3u` from the configuration schema and stopped trying to parse pasted playlist content in the catalog.
- `GET_PROVIDER_URL` now returns the provider URL only while the plugin is enabled.
- Fixed the configuration activity status badge so it recognizes localized `Ready` states.
- Removed standalone start and stop buttons from the activity so provider creation and removal are controlled only from `StreamVault > Plugins`.
- Updated the plugin version to `1.1.14` (`versionCode` 17).

### Validation

- Completed the debug build.

## 1.1.13 - 2026-05-13

### Changed

- Redesigned the configuration activity with visual panels, status badges, a compact provider URL row, and grouped primary actions.
- Replaced the large M3U source text area with source rows that show name, compacted URL, edit, and remove actions.
- Added a compact form for name plus URL/file/content URI, including an integrated file picker.
- Moved inline M3U content into a separate constrained panel before inline support was later removed.
- Updated the plugin version to `1.1.13` (`versionCode` 16).

### Validation

- Completed the debug build.
- Verified debug installation on a Chromecast test device.
- Opened the configuration activity directly on the test device and reviewed it with configured user sources.

## 1.1.12 - 2026-05-13

### Fixed

- Added a short MPD cache per channel to avoid consecutive downloads of the same manifest during preparation and the first Media3 refreshes.
- Served the latest valid manifest for a short window when the CDN temporarily fails during MPD refresh, instead of returning `502` to the player immediately.
- Added internal retry handling for transient `403`, `404`, `408`, `425`, `429`, and `5xx` responses on proxied live DASH fragments before returning an error to the player.
- Updated the plugin version to `1.1.12` (`versionCode` 15).

### Validation

- Completed the debug build.
- Verified debug installation on a Chromecast test device.
- Verified local provider import using a user-supplied playlist without documenting private channel data.
- Verified DASH playback startup on the test device with local MPD `200` responses and DASH fragment `200` responses.
- Confirmed that no real URLs, keys, or test channels are hardcoded in the plugin or documentation.

## 1.1.10 - 2026-05-13

### Fixed

- Served DASH manifest resources directly through the proxy when a channel is in no-header mode, instead of returning `302` to the CDN.
- Propagated safe response headers for proxied DASH resources, including `Accept-Ranges`, `Content-Range`, `ETag`, `Last-Modified`, `Cache-Control`, and `Expires`.
- Retried a resource request without `Range` if the ranged request fails, before returning the error to the player.
- Updated the plugin version to `1.1.10` (`versionCode` 13).

### Validation

- Verified local MPD responses with `200`.
- Verified initialization segments and ranged requests with `200/206` responses from the local proxy.

## 1.1.8 - 2026-05-13

### Fixed

- Updated the DASH header-mode selector to test MPD initialization segments, not only the manifest.
- Fixed HDR/HEVC channels where the CDN accepted playlist headers for the manifest but returned `403` for fragments when `Referer` was forwarded.
- Updated the plugin version to `1.1.8` (`versionCode` 11).

### Validation

- Verified local manifest responses with `200`.
- Verified audio/video initialization fragments without `403` after enabling no-header mode.
- Confirmed that the original failure was a `Source error / HTTP 403` on an initialization segment, not M3U parsing.
- Confirmed that HDR/HEVC Main10 playback remains dependent on the actual decoder capabilities of the device.

## 1.1.7 - 2026-05-13

### Fixed

- Remembered the final manifest URL after redirects in the DASH proxy and used that URL as the base for relative segment redirects.
- Preserved query strings and tokens from the final manifest URL for relative DASH segments, avoiding `404` errors on CDNs that serve high-bitrate manifests from a signed redirected URL.
- Updated the plugin version to `1.1.7` (`versionCode` 10).

### Validation

- Completed the debug build.
- Verified debug installation on a test device.
- Verified preview and fullscreen playback for a high-bitrate adaptive channel from a user-provided source.
- Confirmed that HDR/HEVC Main10 channels still depend on real device decoder support.

## 1.1.6 - 2026-05-13

### Fixed

- DASH manifest fallback now retries without headers and without a forced User-Agent, avoiding `403` responses from CDNs that reject inherited playlist headers.
- Correctly closed probe responses and error bodies to avoid connection leaks.
- Stopped logging normal player disconnects during canceled manifest loads as proxy failures.
- Updated the plugin version to `1.1.6` (`versionCode` 9).

### Validation

- Verified manifest proxy behavior with user-provided adaptive sources.
- Verified HEVC/HDR codec detection without exposing URLs, keys, or channel names.

## 1.1.5 - 2026-05-13

### Fixed

- Kept the plugin service running as a foreground service while enabled, so Android does not stop it for inactivity and the local proxy stays available after the StreamVault Messenger call ends.
- Changed the preferred local port to `39078` to avoid collisions with other StreamVault plugins that may use `39077`.
- Returned `502 Bad Gateway` from the manifest proxy when the origin does not respond, instead of closing the connection without a response body.

### Validation

- Verified debug installation on a test device.
- Verified import of a user-provided M3U playlist.
- Verified `/status.json`, `/playlist.m3u`, playback preparation, and local MPD + ClearKey manifest proxy behavior on the new port.
- Verified that the foreground service remained active in the background.

## 1.1.4 - 2026-05-13

### Changed

- Added a local proxy for MPD + ClearKey manifests.
- Added ClearKey `ContentProtection` with generated PSSH v1 to MPD manifests served by the plugin.
- Kept DASH segments on the original origin while preserving headers and User-Agent metadata during playback preparation.
- Updated the plugin version to `1.1.4` (`versionCode` 7).

### Validation

- Verified import using a user-provided playlist.
- Verified local `/playlist.m3u`, `/play/{channelId}`, and `/license/clearkey/{channelId}` endpoints.
- Verified local `/manifest/{channelId}/manifest.mpd` output with ClearKey `ContentProtection` and `cenc:pssh`.

## 1.1.3 - 2026-05-13

### Changed

- Removed all preconfigured content from the plugin.
- Reset persisted configuration so older sources from previous builds are not reused.
- Required users to provide their own sources as remote URLs, local files, or inline M3U content. Inline support was removed later in `1.1.14`.
- Accepted `http://`, `https://`, `file://`, `content://`, absolute paths, and the `Name|source` format in the source field.
- Switched configuration to native activity mode so users can select files with the Android document picker.
- Documented generic examples for MPD and ISML manifests with `inputstream.adaptive`.
- Updated the plugin version to `1.1.3` (`versionCode` 6).

### Validation

- Completed the debug build.
- Verified import of user-owned M3U/M3U8 sources from URL, file, and inline content.
- Verified adaptive playback preparation with final URL, stream type, headers, and DRM metadata.

## 1.1.2 - 2026-05-13

### Changed

- Documented full ISML + ClearKey compatibility.
- StreamVault host reconstructs SmoothStreaming initialization data as ClearKey PSSH v1 for Android MediaDrm.

## 1.1.1 - 2026-05-13

### Added

- Added a local proxy for SmoothStreaming/ISML + ClearKey manifests.
- Redirected SmoothStreaming fragments to the original CDN.

## 1.1.0 - 2026-05-13

### Added

- Added `SMOOTH_STREAMING` output support for `ism` and `isml` manifests.
- Added the required StreamVault host compatibility for SmoothStreaming playback through Media3 `SsMediaSource`.

## 1.0.1 - 2026-05-13

### Added

- Added support for `inputstream.adaptive.license_key` with multiple ClearKey pairs in `kid:key,kid:key` format.

## 1.0.0 - 2026-05-12

### Added

- Created the Android project for `com.streamvault.plugin.adaptivebridge`.
- Added a Messenger service compatible with `com.streamvault.plugin.API`.
- Added the plugin manifest with `provider.m3u`, `playback.prepare`, and `configuration.schema` capabilities.
- Added the local HTTP server on `127.0.0.1:39077`.
- Added `/playlist.m3u`, `/play/{channelId}`, `/license/clearkey/{channelId}`, and `/status.json` endpoints.
- Added a Kodi-compatible M3U parser for `#KODIPROP`, `#EXTINF`, `#EXTGRP`, `#EXTVLCOPT:http-user-agent`, and `#EXTVLCOPT:http-referer`.
- Added adaptive stream preparation with final URL, DASH/HLS type, headers, User-Agent, and DRM metadata.
