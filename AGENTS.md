## graphify

This project uses graphify for codebase navigation. The local graph lives at `graphify-out/`, which is intentionally git-ignored because graphify writes machine-local paths into its cache.

When the user types `/graphify`, use the local `graphify` CLI before doing anything else.

Rules:
- If `graphify-out/GRAPH_REPORT.md` exists, read it before reading source files, running grep/glob searches, or answering codebase questions. If it does not exist, run `graphify update .` first.
- IF `graphify-out/wiki/index.md` EXISTS, navigate it instead of reading raw files
- For cross-module "how does X relate to Y" questions, prefer `graphify query "<question>"`, `graphify path "<A>" "<B>"`, or `graphify explain "<concept>"` over grep — these traverse the graph's EXTRACTED + INFERRED edges instead of scanning files
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## Project Notes

This is the StreamVault Adaptive Bridge companion plugin. It imports user-provided M3U/M3U8 sources with Kodi `inputstream.adaptive` metadata and exposes a local StreamVault `provider.m3u` playlist plus `playback.prepare` metadata for DASH, HLS and SmoothStreaming/ISML playback.

Core files:
- `AdaptiveBridge.java`: central runtime, catalog refresh, local provider playlist, playback metadata, manifest proxy helpers and plugin manifest/config values.
- `StreamVaultAdaptiveBridgePluginService.java`: Android Messenger IPC service for the StreamVault plugin API.
- `AdaptiveLocalServer.java`: localhost HTTP server for `/playlist.m3u`, `/play/{channelId}`, `/manifest/{channelId}/...`, `/license/clearkey/{channelId}` and `/status.json`.
- `KodiPropsM3uParser.java`: M3U/KODIPROP parser for manifest type, headers, DRM and ClearKey forms.
- `AdaptiveChannel.java`: parsed channel model and playback/DRM serialization.
- `AdaptiveBridgeConfigActivity.java`: native configuration UI for M3U sources.

## StreamVault Contract

- The plugin is discovered through the exported service action `com.streamvault.plugin.API`.
- Activation/deactivation must happen through StreamVault > Plugins. Do not reintroduce standalone Start/Stop buttons in the plugin Activity, because StreamVault owns provider creation/removal.
- When enabled, StreamVault calls `MSG_SET_ENABLED=true`, then `MSG_GET_PROVIDER_URL`, then creates or updates the associated M3U provider and syncs it.
- When disabled, StreamVault calls `MSG_SET_ENABLED=false` and removes the associated provider.
- `GET_PROVIDER_URL` should only return a provider URL while the plugin is enabled.

## Content And Privacy

- Do not hardcode real channels, customer playlists, ClearKey values, tokens, private URLs or test streams in source or docs.
- Documentation examples must use fictional domains and fictional keys only.
- The user supplies all playlists through URL, file, absolute path or `content://` URI. Inline M3U input was removed and should stay removed unless explicitly requested.

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

Nexus 5X may not decode some HDR/HEVC Main10 streams even when the plugin correctly serves manifests, licenses and segments.

## Release Hygiene

- Keep `AdaptiveBridge.VERSION_NAME`, `AdaptiveBridge.VERSION_CODE`, `app/build.gradle.kts`, and `AndroidManifest.xml` metadata in sync.
- Update `docs/Changelog.md` when changing behavior.
- After code changes, run `graphify update .` and then run the Gradle build command above.
