# StreamVault Adaptive Bridge

<p align="center">
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge/releases/latest/download/StreamVault-Adaptive-Bridge.apk"><img src="https://img.shields.io/badge/Download-StreamVault--Adaptive--Bridge.apk-2ea44f?style=for-the-badge&logo=android" alt="Download StreamVault Adaptive Bridge APK" /></a>
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge/releases/latest"><img src="https://img.shields.io/github/v/release/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge?display_name=tag&style=for-the-badge&color=0f766e&cacheSeconds=60" alt="Latest StreamVault Adaptive Bridge release" /></a>
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge/releases"><img src="https://img.shields.io/github/downloads/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge/total?style=for-the-badge&color=8b5cf6&cacheSeconds=60" alt="Total downloads" /></a>
	<a href="docs/Changelog.md"><img src="https://img.shields.io/badge/Changelog-View-2563eb?style=for-the-badge" alt="View changelog" /></a>
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge/actions/workflows/build-apk.yml"><img src="https://img.shields.io/github/actions/workflow/status/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge/build-apk.yml?branch=main&style=for-the-badge&label=CI" alt="GitHub Actions status" /></a>
	<a href="https://ko-fi.com/jopsis"><img src="https://img.shields.io/badge/Support-Ko--fi-ff5f5f?style=for-the-badge&logo=kofi" alt="Support on Ko-fi" /></a>
</p>

StreamVault Adaptive Bridge is a companion plugin for StreamVault IPTV. It imports user-provided M3U/M3U8 playlists that include Kodi `inputstream.adaptive` metadata, then exposes them to StreamVault as a local `provider.m3u` playlist plus `playback.prepare` metadata for DASH, HLS, and SmoothStreaming/ISML playback through Android Media3.

The plugin does not include channels, playlists, keys, tokens, or preconfigured content. Users must provide their own authorized sources by remote URL, local file, absolute path, or Android `content://` URI.

Current plugin version: `1.1.20-beta.2` (`versionCode` 24).

## Features

- Discovery through the exported StreamVault plugin service action `com.streamvault.plugin.API`.
- Local `provider.m3u` playlist, normally served at `http://127.0.0.1:39078/playlist.m3u`. If the preferred port is unavailable, the configuration screen shows the active port.
- `playback.prepare` support that turns local `/play/{channelId}` URLs into real adaptive manifests and playback metadata.
- M3U/M3U8 sources from `http://`, `https://`, `file://`, `content://`, or absolute local paths.
- Parser support for `#EXTM3U`, `#EXTINF`, `#EXTGRP`, `#EXTVLCOPT:http-user-agent`, `#EXTVLCOPT:http-referer`, and `#KODIPROP`.
- Kodi `inputstream.adaptive` metadata for DASH, HLS, SmoothStreaming, manifest headers, stream headers, license headers, and DRM.
- ClearKey `kid:key` values in hexadecimal, base64, or JWK/base64url form converted into a local JWK response at `/license/clearkey/{channelId}`, including comma-separated key lists, JSON maps, and Kodi `drm_legacy` data URI entries.
- Local DASH/MPD ClearKey manifest proxy at `/manifest/{channelId}/manifest.mpd`, including generated ClearKey `ContentProtection` with PSSH v1 while keeping media segments on the original CDN.
- Header fallback for strict CDNs: if DASH manifests or initialization segments return `403` with playlist headers, the plugin retries without forced headers or User-Agent.
- Local SmoothStreaming/ISML ClearKey manifest proxy at `/manifest/{channelId}/Manifest`, with ClearKey-compatible protection metadata for StreamVault host playback.
- Foreground service while the plugin is enabled, keeping the local `127.0.0.1` proxy alive during preview and fullscreen playback.
- Native Android configuration activity for remote URLs, local files, absolute paths, Android document picker entries, and `content://` sources.

## Requirements

- StreamVault IPTV with plugin support for `provider.m3u` and `playback.prepare`.
- Android playback hardware and codecs compatible with the target streams.
- User-owned or otherwise authorized M3U/M3U8 sources.
- Kodi-style `inputstream.adaptive` metadata when adaptive playback configuration is required.

This plugin does not bypass DRM, discover keys, or provide access to content. It only forwards metadata already present in user-provided playlists.

## M3U Sources

In `M3U source URLs or files`, add one source per line. The configuration screen also includes `Add file`, which opens the Android document picker and stores the read permission for the selected M3U/M3U8 file.

```text
https://playlists.example.com/main.m3u8
Sports|https://playlists.example.com/sports.m3u
Local file|file:///sdcard/Download/adaptive.m3u8
/sdcard/Download/adaptive.m3u8
content://com.example.provider/document/adaptive.m3u8
```

The `Name|source` format assigns a readable name to a source. Local files must be readable by Android for the plugin APK.

Inline pasted M3U content is intentionally not supported. Keep playlist content in a URL, file, absolute path, or `content://` URI.

## Provider Lifecycle

StreamVault owns plugin activation and provider creation.

When the plugin is enabled from `StreamVault > Plugins`, StreamVault sends `MSG_SET_ENABLED=true`. The plugin starts the local proxy, refreshes the configured sources, advertises its `provider.m3u` capability, and returns a provider URL when StreamVault requests `MSG_GET_PROVIDER_URL`. StreamVault then creates or updates the associated M3U provider and syncs it.

When the plugin is disabled from `StreamVault > Plugins`, StreamVault sends `MSG_SET_ENABLED=false`, the plugin stops the proxy, and StreamVault removes the associated provider.

The native configuration screen only manages sources and manual refresh. It does not include standalone start or stop buttons, because enabling and disabling must stay synchronized with StreamVault provider ownership.

During normal app startup, StreamVault runs its own provider checks and scheduled sync jobs. When it syncs this plugin provider, it downloads `http://127.0.0.1:<port>/playlist.m3u`; the plugin refreshes configured sources if its short cache has expired. The plugin never writes channels directly into the StreamVault database.

## Supported KODIPROP Keys

- `inputstream=inputstream.adaptive`
- `inputstream.adaptive.manifest_type`
- `inputstream.adaptive.license_type`
- `inputstream.adaptive.license_key`
- `inputstream.adaptive.manifest_headers`
- `inputstream.adaptive.stream_headers`
- `inputstream.adaptive.common_headers`
- `inputstream.adaptive.license_headers`
- `inputstream.adaptive.drm_legacy`
- Partial `inputstream.adaptive.drm` support for `org.w3.clearkey`, `com.widevine.alpha`, and `com.microsoft.playready`

## DASH ClearKey Example

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="dash.example" group-title="DASH",Example DASH Channel
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
#KODIPROP:inputstream.adaptive.license_key=00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100
#EXTVLCOPT:http-user-agent=Mozilla/5.0
https://media.example.com/live/example/manifest.mpd
```

JSON key maps are also accepted:

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="dash.json.example" group-title="DASH",Example DASH JSON Channel
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
#KODIPROP:inputstream.adaptive.license_key={"00112233445566778899aabbccddeeff":"ffeeddccbbaa99887766554433221100","11223344556677889900aabbccddeeff":"00ffeeddccbbaa998877665544332211"}
https://media.example.com/live/example-json/manifest.mpd
```

JWK/base64url ClearKey entries are normalized to the same local Android ClearKey response:

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="dash.jwk.example" group-title="DASH",Example DASH JWK Channel
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
#KODIPROP:inputstream.adaptive.license_key={"keys":[{"kty":"oct","kid":"AAECAwQFBgcICQoLDA0ODw","k":"ABEiM0RVZneImaq7zN3u_w"}],"type":"temporary"}
https://media.example.com/live/example-jwk/manifest.mpd
```

For MPD + ClearKey, some manifests declare `ContentProtection` only for other DRM systems even though the playlist provides ClearKey values. In that case, the plugin serves the MPD through the local proxy and adds Android Media3-compatible ClearKey metadata.

## SmoothStreaming ClearKey Example

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="isml.example" group-title="SMOOTH",Example ISML Channel
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=ism
#KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
#KODIPROP:inputstream.adaptive.license_key=00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100
https://media.example.com/live/example/Manifest
```

For ISML + ClearKey, the origin manifest may contain a SmoothStreaming `ProtectionHeader`. The plugin serves it through the local proxy and StreamVault host normalizes the initialization data into Android ClearKey-compatible PSSH before opening the DRM session.

## Build And Validation

Use the bundled Android Studio JBR when building locally:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew --no-daemon :app:assembleDebug :app:printVersionName :app:printVersionCode
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For device checks, set `ANDROID_SERIAL` to the target device id before running ADB commands:

```sh
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" shell am start -n com.streamvault.plugin.adaptivebridge/.AdaptiveBridgeConfigActivity
```

Some older devices may not decode HDR/HEVC Main10 streams even when the plugin correctly serves manifests, licenses, and segments.

## Releases

GitHub Actions can publish signed builds from the `Build signed APK` workflow.

- `stable`: requires a `versionName` without prerelease suffixes, creates or updates a normal GitHub Release, and keeps the APK alias `StreamVault-Adaptive-Bridge.apk`.
- `beta`: requires a `versionName` with a `-beta` suffix, for example `1.1.20-beta.2`, creates or updates a GitHub prerelease, and publishes the APK alias `StreamVault-Adaptive-Bridge-beta.apk` without marking it as latest.

## Privacy And Content Policy

- Do not commit real channels, customer playlists, ClearKey values, tokens, private URLs, or test streams.
- Documentation examples must use fictional domains and fictional keys only.
- Users are responsible for providing sources they are authorized to access.
