# StreamVault Adaptive Bridge

<p align="center">
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge/releases/latest/download/StreamVault-Adaptive-Bridge.apk"><img src="https://img.shields.io/badge/Download-StreamVault--Adaptive--Bridge.apk-2ea44f?style=for-the-badge&logo=android" alt="Download StreamVault Adaptive Bridge APK" /></a>
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge/releases/latest"><img src="https://img.shields.io/github/v/release/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge?display_name=tag&style=for-the-badge&color=0f766e&cacheSeconds=60" alt="Latest StreamVault Adaptive Bridge release" /></a>
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge/releases"><img src="https://img.shields.io/github/downloads/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge/total?style=for-the-badge&color=8b5cf6&cacheSeconds=60" alt="Total Downloads" /></a>
	<a href="docs/Changelog.md"><img src="https://img.shields.io/badge/Changelog-View-2563eb?style=for-the-badge" alt="View changelog" /></a>
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge/actions/workflows/build-apk.yml"><img src="https://img.shields.io/github/actions/workflow/status/jopsis/StreamVault-IPTV-Plugin-Adaptive-Bridge/build-apk.yml?branch=main&style=for-the-badge&label=CI" alt="GitHub Actions status" /></a>
	<a href="https://ko-fi.com/jopsis"><img src="https://img.shields.io/badge/Support-Ko--fi-ff5f5f?style=for-the-badge&logo=kofi" alt="Support on Ko-fi" /></a>
</p>

Plugin companion para StreamVault-IPTV que actua como puente entre listas M3U/M3U8 propias con metadatos estilo Kodi `inputstream.adaptive` y la reproduccion adaptativa de StreamVault/Media3.

El plugin no incluye canales ni contenidos preconfigurados. El usuario debe proporcionar sus propias listas mediante URL remota, fichero local, ruta local o URI `content://`.
La configuracion persistida se inicia limpia en esta version para no reutilizar fuentes heredadas de builds anteriores.

Version actual del plugin: `1.1.20-beta.1` (`versionCode` 23).

## Funcionalidad

- Descubrimiento por `com.streamvault.plugin.API`.
- Proveedor `provider.m3u` servido normalmente en `http://127.0.0.1:39078/playlist.m3u` para evitar colisiones con otros plugins locales. Si el puerto estuviera ocupado, la configuracion muestra el puerto efectivo.
- Preparacion `playback.prepare` para transformar URLs locales `/play/{channelId}` en manifests DASH/HLS/SmoothStreaming reales.
- Fuentes M3U/M3U8 desde `http://`, `https://`, `file://`, `content://` o ruta absoluta local.
- Parser compatible con `#EXTM3U`, `#EXTINF`, `#EXTGRP`, `#EXTVLCOPT:http-user-agent`, `#EXTVLCOPT:http-referer` y `#KODIPROP`.
- Soporte `inputstream.adaptive` para DASH/HLS/SmoothStreaming, cabeceras de manifest/stream/licencia y DRM ClearKey/Widevine/PlayReady.
- ClearKey `kid:key` en hexadecimal convertido a JWK local mediante `/license/clearkey/{channelId}`, incluyendo listas separadas por coma y mapas JSON `{ "kid": "key" }`.
- Proxy local para DASH/MPD + ClearKey: sirve el manifest en `/manifest/{channelId}/manifest.mpd`, anade `ContentProtection` ClearKey con PSSH v1 generado desde los KID de la lista y mantiene los segmentos en el CDN original usando la URL final del manifest tras redirecciones, incluyendo query/token cuando el CDN lo requiere.
- Fallback de cabeceras para CDNs sensibles: si el manifest DASH o sus segmentos de inicializacion responden `403` con las cabeceras de la lista, el plugin reintenta sin cabeceras ni User-Agent forzado.
- Proxy local para SmoothStreaming/ISML + ClearKey: sirve el manifest en `/manifest/{channelId}/Manifest`, marca el `ProtectionHeader` como ClearKey y mantiene los fragmentos en el CDN original. StreamVault host convierte ese init data ISML a PSSH ClearKey v1 antes de abrir la sesion DRM.
- Servicio foreground local mientras el plugin esta habilitado, necesario para que Android no cierre el proxy `127.0.0.1` durante preview y reproduccion fullscreen.
- Configuracion nativa `activity` para URLs/ficheros M3U, rutas locales, URI `content://` y selector de documentos Android.

## Fuentes M3U

En `M3U source URLs or files` se admite una fuente por linea. La pantalla de configuracion incluye `Add file` para seleccionar un fichero M3U/M3U8 con el selector de documentos de Android y guardar su permiso de lectura.

```text
<remote_m3u_url>
Sports|<sports_m3u_url>
Local|file:///sdcard/Download/adaptive.m3u8
/sdcard/Download/adaptive.m3u8
content://com.example.provider/document/adaptive.m3u8
```

El formato `Name|source` permite asignar un nombre legible a la fuente. Los ficheros locales deben ser legibles por Android para el APK del plugin.

## Ciclo De Vida Del Proveedor

Al activar el plugin desde StreamVault, el host envia `MSG_SET_ENABLED=true`. El plugin arranca el proxy local, refresca las fuentes configuradas y, como anuncia `provider.m3u`, StreamVault pide `MSG_GET_PROVIDER_URL`, crea o actualiza el proveedor M3U asociado y fuerza la sincronizacion del proveedor.

Al desactivar el plugin desde StreamVault, el host envia `MSG_SET_ENABLED=false`, detiene el proxy y elimina el proveedor M3U que tenia asociado a este plugin.

La pantalla nativa de configuracion solo gestiona fuentes y refresco manual. La activacion/desactivacion debe hacerse desde StreamVault > Plugins para que el estado del plugin y el proveedor asociado permanezcan sincronizados.

En arranque normal de la app, StreamVault mantiene trabajos periodicos y un chequeo inicial de proveedores. Cuando sincroniza este proveedor M3U, descarga `http://127.0.0.1:<puerto>/playlist.m3u`; en ese momento el plugin vuelve a leer las fuentes configuradas si su cache corta ya expiro. El plugin no empuja canales directamente a la base de StreamVault: siempre expone la lista y StreamVault la importa/sincroniza.

## KODIPROP Soportados

- `inputstream=inputstream.adaptive`
- `inputstream.adaptive.manifest_type`
- `inputstream.adaptive.license_type`
- `inputstream.adaptive.license_key`
- `inputstream.adaptive.manifest_headers`
- `inputstream.adaptive.stream_headers`
- `inputstream.adaptive.common_headers`
- `inputstream.adaptive.license_headers`
- `inputstream.adaptive.drm` parcial para `org.w3.clearkey`, `com.widevine.alpha` y `com.microsoft.playready`

## Ejemplo MPD Con ClearKey

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="dash.channel" group-title="DASH",Canal DASH
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
#KODIPROP:inputstream.adaptive.license_key=<kid_hex_32>:<key_hex_32>
#EXTVLCOPT:http-user-agent=Mozilla/5.0
<dash_manifest_url>
```

Tambien se acepta el mapa JSON de claves:

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="dash.json" group-title="DASH",Canal DASH JSON
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
#KODIPROP:inputstream.adaptive.license_key={"<kid_hex_32>":"<key_hex_32>","<second_kid_hex_32>":"<second_key_hex_32>"}
<dash_manifest_url>
```

## Ejemplo ISML/SmoothStreaming Con ClearKey

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="isml.channel" group-title="SMOOTH",Canal ISML
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=ism
#KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
#KODIPROP:inputstream.adaptive.license_key=<kid_hex_32>:<key_hex_32>
<isml_manifest_url>
```

Para ISML + ClearKey, el manifest puede declarar un `ProtectionHeader` de origen SmoothStreaming. El plugin lo sirve por proxy local y StreamVault host normaliza el init data para Android ClearKey.

Para MPD + ClearKey, algunos manifests solo declaran `ContentProtection` de otros DRM aunque la lista entregue claves ClearKey. En ese caso el plugin sirve el MPD por proxy local y anade una entrada ClearKey compatible con Android Media3.

## Releases

GitHub Actions permite publicar el canal estable o beta desde `Build signed APK`.

- `stable`: usa un `versionName` sin sufijos de prerelease, crea o actualiza un GitHub Release normal y mantiene el APK `StreamVault-Adaptive-Bridge.apk`.
- `beta`: requiere un `versionName` con sufijo `-beta`, por ejemplo `1.1.20-beta.1`, crea o actualiza un GitHub prerelease y publica el alias `StreamVault-Adaptive-Bridge-beta.apk` sin marcarlo como latest.
