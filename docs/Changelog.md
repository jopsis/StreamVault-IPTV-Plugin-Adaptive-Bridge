# Changelog

## 1.1.20-beta.1 - 2026-05-18

### Changed

- Proyecto renombrado a StreamVault Adaptive Bridge.
- Package Android cambiado a `com.streamvault.plugin.adaptivebridge` y plugin ID cambiado a `com.streamvault.plugins.adaptivebridge`.
- Activity de configuracion renombrada a `AdaptiveBridgeConfigActivity` y servicio IPC a `StreamVaultAdaptiveBridgePluginService`.
- GitHub Actions ahora permite publicar releases firmadas en canal `stable` o `beta` desde `workflow_dispatch`.
- Los tags/versionName con sufijo `-beta` se publican como GitHub prerelease, no se marcan como latest y generan el alias `StreamVault-Adaptive-Bridge-beta.apk`.
- Versión beta del plugin actualizada a `1.1.20-beta.1` (`versionCode` 23).

### Validation

- `graphify update .` completado tras el rename.
- Build debug completado correctamente y version confirmada como `1.1.20-beta.1` (`versionCode` 23).
- APK instalado en Chromecast y verificado con package `com.streamvault.plugin.adaptivebridge`.
- Activity `AdaptiveBridgeConfigActivity` abierta en Chromecast y servicio `StreamVaultAdaptiveBridgePluginService` verificado para discovery `com.streamvault.plugin.API`.

## 1.1.18 - 2026-05-14

### Changed

- Las fuentes M3U ahora se descargan en paralelo (`ExecutorService.invokeAll`), reduciendo el tiempo de refresco de catálogo de N×T a T para N fuentes.
- `refreshCatalog` ya no bloquea lecturas de caché (`cachedChannelCount`, `cachedMessage`): el catálogo se actualiza vía `AtomicReference` y la exclusión mutua de refresco usa `ReentrantLock` propio.
- `DocumentBuilderFactory` para análisis DASH se inicializa una sola vez como campo estático, eliminando el service-loader lookup en cada reproducción.
- Los timeouts de las pruebas HTTP de negociación ClearKey reducidos a 3 s (connect) / 5 s (read) para agilizar el primer play de canales DASH+ClearKey.
- El pool de threads del servidor HTTP local cambiado a `newFixedThreadPool(6)` (acotado) en lugar de `newCachedThreadPool`.
- `toHex` y `stableId` en el parser M3U usan tabla de caracteres en lugar de `String.format` por byte.
- Versión del plugin actualizada a `1.1.18` (`versionCode` 21).

## 1.1.17 - 2026-05-14

### Fixed

- La playlist generada anuncia ahora un `x-tvg-url` local canonico (`/epg.xml`) para que StreamVault sincronice el EPG contra el propio plugin.
- El servidor local expone `/epg.xml`, redirige al primer XMLTV declarado por las fuentes M3U y devuelve un XMLTV vacio valido si no hay EPG disponible.
- Version del plugin actualizada a `1.1.17` (`versionCode` 20).

### Validation

- Build debug del plugin completado correctamente.
- APK `1.1.17` (`versionCode` 20) instalado en Chromecast.
- `/playlist.m3u` anuncia `#EXTM3U x-tvg-url="http://127.0.0.1:39078/epg.xml"`.
- `/epg.xml` responde con `302` hacia el primer XMLTV declarado por la lista y siguiendo la redireccion devuelve XMLTV valido.
- StreamVault beta en Chromecast detecta el plugin `1.1.17`, permite activarlo y sincroniza el proveedor con 182 canales.

## 1.1.16 - 2026-05-13

### Fixed

- Reemite las URLs EPG embebidas de `#EXTM3U` usando solo el atributo canonico `x-tvg-url` en `/playlist.m3u`, manteniendo la lectura de alias como `url-tvg`, `tvg-url` y `url-xml`.
- Version del plugin actualizada a `1.1.16` (`versionCode` 19).

### Validation

- Build debug del plugin completado correctamente.
- APK `1.1.16` (`versionCode` 19) instalado en Chromecast y validado contra `/playlist.m3u`; el header se sirve como `#EXTM3U x-tvg-url="..."`.
- StreamVault host `1.0.11` en Chromecast descarga la playlist pero sigue dejando `epg_url` vacio en el proveedor; queda identificado como seguimiento del host.

## 1.1.15 - 2026-05-13

### Fixed

- Conserva las URLs EPG declaradas en la cabecera M3U (`x-tvg-url`, `url-tvg`, `tvg-url` o `url-xml`) y las reemite en `/playlist.m3u` para que StreamVault pueda marcarlas en el proveedor.
- Deduplica las URLs EPG al combinar varias fuentes y muestra el recuento en el estado del catalogo sin exponer las URLs.
- Version del plugin actualizada a `1.1.15` (`versionCode` 18).

### Validation

- Build debug del plugin completado correctamente.

## 1.1.14 - 2026-05-13

### Changed

- Eliminada la configuracion de M3U inline: el plugin ahora acepta solo fuentes M3U por URL, fichero, ruta local o URI `content://`.
- El schema de configuracion ya no expone `inline_m3u` y el catalogo ya no intenta parsear contenido pegado.
- `GET_PROVIDER_URL` solo entrega la URL del proveedor cuando el plugin esta activado.
- El badge de estado de la Activity reconoce correctamente el estado localizado `Listo`/`Ready`.
- Eliminados los botones `Iniciar`/`Detener` de la Activity para que el alta/baja del proveedor se haga solo desde StreamVault > Plugins.
- Version del plugin actualizada a `1.1.14` (`versionCode` 17).

### Validation

- Build debug del plugin completado correctamente.

## 1.1.13 - 2026-05-13

### Changed

- Redisenada la Activity de configuracion con paneles visuales, estado en badges, URL del proveedor en fila compacta y acciones principales agrupadas.
- Las fuentes M3U ya no se editan en una caja gigante: ahora se muestran como filas con nombre, URL compactada, editar y quitar.
- Anade formulario compacto para nombre + URL/fichero/content URI y selector de fichero integrado.
- El M3U inline queda en un panel independiente con altura contenida.
- Version del plugin actualizada a `1.1.13` (`versionCode` 16).

### Validation

- Build debug del plugin completado correctamente.
- Instalacion debug en Chromecast verificada.
- Activity de configuracion abierta directamente en Chromecast y revisada visualmente con la lista del usuario cargada.

## 1.1.12 - 2026-05-13

### Fixed

- Anade cache corta de MPD por canal para evitar descargas consecutivas del mismo manifest durante la preparacion y los primeros refrescos de Media3.
- Si el CDN falla temporalmente al refrescar el MPD, el plugin sirve el ultimo manifest valido durante una ventana corta en vez de devolver `502` al reproductor.
- Los fragmentos DASH live servidos por proxy local reintentan internamente respuestas transitorias `403`, `404`, `408`, `425`, `429` o `5xx` antes de propagar el fallo.
- Version del plugin actualizada a `1.1.12` (`versionCode` 15).

### Validation

- Build debug del plugin completado correctamente.
- Instalacion debug en Chromecast verificada.
- Proveedor local verificado con 182 canales importados desde lista del usuario.
- Reproduccion HDR/HEVC en Chromecast verificada con `first-frame-success`, decoder hardware `video/hevc`, estado `PLAYING`, MPD local `200` y fragmentos DASH `200`.
- No quedan URLs, claves ni canales de prueba hardcodeados en el plugin ni en la documentacion.

## 1.1.10 - 2026-05-13

### Fixed

- El proxy DASH sirve directamente los recursos de manifest cuando el canal esta en modo sin cabeceras, en vez de devolver `302` al CDN.
- Los recursos DASH proxificados propagan cabeceras seguras como `Accept-Ranges`, `Content-Range`, `ETag`, `Last-Modified`, `Cache-Control` y `Expires`.
- Si un recurso falla con `Range`, el proxy reintenta sin `Range` antes de devolver el error al reproductor.
- Version del plugin actualizada a `1.1.10` (`versionCode` 13).

### Validation

- Manifest MPD local verificado con `200`.
- Segmentos de inicializacion y peticiones con `Range` verificados con respuestas `200/206` desde el proxy local.

## 1.1.8 - 2026-05-13

### Fixed

- El selector de modo de cabeceras DASH comprueba tambien segmentos de inicializacion del MPD, no solo el manifest.
- Corrige canales HDR/HEVC donde el CDN aceptaba el manifest con cabeceras de la lista, pero devolvia `403` en fragmentos cuando se reenviaba `Referer`.
- Version del plugin actualizada a `1.1.8` (`versionCode` 11).

### Validation

- Manifest local de canal HDR/HEVC verificado con `200`.
- Fragmentos de inicializacion de audio/video HDR/HEVC verificados sin `403` al activar el modo sin cabeceras.
- Logcat previo confirmo que el fallo era `Source error / HTTP 403` en init segment, no parsing de M3U.
- Tras el fix, el canal HDR/HEVC avanza hasta descarga de fragmentos `200`; en Nexus 5X el fallo restante es de decoder HEVC/HLG 10-bit con `NO_EXCEEDS_CAPABILITIES`.

## 1.1.7 - 2026-05-13

### Fixed

- El proxy DASH recuerda la URL final del manifest tras redirecciones y usa esa base para redirigir segmentos relativos.
- Los segmentos DASH relativos conservan la query/token del manifest final, evitando `404` en CDNs que entregan manifests 4K/HDR desde una URL firmada distinta a la URL original de la lista.
- Version del plugin actualizada a `1.1.7` (`versionCode` 10).

### Validation

- Build debug del plugin completado correctamente.
- Instalacion debug en Nexus 5X verificada.
- Preview y reproduccion fullscreen de un canal 4K verificados en Nexus 5X.
- Los canales HDR/HEVC Main10 quedan condicionados al soporte real del decoder del dispositivo.

## 1.1.6 - 2026-05-13

### Fixed

- El fallback de manifests DASH ahora reintenta sin cabeceras y sin User-Agent forzado, evitando `403` en CDNs que rechazan cabeceras heredadas de la lista.
- El proxy cierra correctamente respuestas de prueba y cuerpos de error para evitar fugas de conexiones.
- Las desconexiones normales del reproductor mientras cancela una carga de manifest ya no se registran como fallo del proxy.
- Version del plugin actualizada a `1.1.6` (`versionCode` 9).

### Validation

- Manifest proxy verificado en canales HDR/4K de la lista del usuario.
- Verificada deteccion de canales HEVC/HDR con codecs `hvc1` sin exponer URLs ni claves.

## 1.1.5 - 2026-05-13

### Fixed

- Mantiene el servicio del plugin como foreground service mientras esta habilitado para que Android no lo pare por inactividad y el proxy local no desaparezca al terminar la llamada Messenger de StreamVault.
- Cambia el puerto local preferido a `39078` para evitar colisiones con otros plugins de StreamVault que usen `39077`.
- El proxy de manifests devuelve `502 Bad Gateway` si el origen no responde, en vez de cerrar la conexion sin cuerpo.

### Validation

- Instalacion debug en Nexus 5X.
- Verificacion de importacion de lista propia con 182 canales MPD.
- Verificacion de `/status.json`, `/playlist.m3u`, preparacion de reproduccion y proxy local de manifest MPD + ClearKey en el puerto nuevo.
- Verificacion del servicio foreground en segundo plano durante mas de dos minutos sin parada por `app idle`.

## 1.1.4 - 2026-05-13

### Changed

- Anadio proxy local para manifests MPD + ClearKey.
- Los MPD ClearKey servidos por el plugin incorporan `ContentProtection` ClearKey con PSSH v1 generado desde los KID de la lista.
- Los segmentos DASH siguen redirigiendose al origen original, conservando cabeceras y User-Agent en la preparacion de reproduccion.
- Version del plugin actualizada a `1.1.4` (`versionCode` 7).

### Validation

- Importacion de lista propia con 182 canales verificada.
- Endpoints locales `/playlist.m3u`, `/play/{channelId}` y `/license/clearkey/{channelId}` verificados.
- Manifest local `/manifest/{channelId}/manifest.mpd` verificado con `ContentProtection` ClearKey y `cenc:pssh`.

## 1.1.3 - 2026-05-13

### Changed

- Eliminado todo contenido preconfigurado del plugin.
- Reiniciada la configuracion persistida para que no se arrastren fuentes heredadas de builds anteriores.
- Las fuentes deben ser proporcionadas por el usuario como URL remota, fichero local o M3U inline.
- El campo de fuentes acepta `http://`, `https://`, `file://`, `content://`, rutas absolutas y el formato `Name|source`.
- La configuracion pasa a modo nativo `activity` para permitir seleccion de fichero con el selector de documentos de Android.
- Documentados ejemplos genericos para manifests MPD y ISML con `inputstream.adaptive`.
- Version del plugin actualizada a `1.1.3` (`versionCode` 6).

### Validation

- Build debug del plugin completado correctamente.
- Importacion de listas propias M3U/M3U8 desde URL, fichero e inline.
- Preparacion de reproduccion adaptativa con URL final, tipo de stream, cabeceras y DRM.

## 1.1.2 - 2026-05-13

### Changed

- Documentada la compatibilidad ISML + ClearKey completa.
- StreamVault host reconstruye init data SmoothStreaming como PSSH ClearKey v1 para Android MediaDrm.

## 1.1.1 - 2026-05-13

### Added

- Proxy local para manifests SmoothStreaming/ISML + ClearKey.
- Redireccion de fragmentos SmoothStreaming al CDN original.

## 1.1.0 - 2026-05-13

### Added

- Soporte de salida `SMOOTH_STREAMING` para manifests `ism`/`isml`.
- Compatibilidad requerida en StreamVault host para reproducir SmoothStreaming mediante Media3 `SsMediaSource`.

## 1.0.1 - 2026-05-13

### Added

- Soporte para `inputstream.adaptive.license_key` con varias claves ClearKey en formato `kid:key,kid:key`.

## 1.0.0 - 2026-05-12

### Added

- Proyecto Android completo para `com.streamvault.plugin.adaptivebridge`.
- Servicio Messenger compatible con `com.streamvault.plugin.API`.
- Manifest de plugin con capacidades `provider.m3u`, `playback.prepare` y `configuration.schema`.
- Servidor HTTP local en `127.0.0.1:39077`.
- Endpoints `/playlist.m3u`, `/play/{channelId}`, `/license/clearkey/{channelId}` y `/status.json`.
- Parser M3U/Kodi para `#KODIPROP`, `#EXTINF`, `#EXTGRP`, `#EXTVLCOPT:http-user-agent` y `#EXTVLCOPT:http-referer`.
- Preparacion de stream adaptativo con URL final, tipo DASH/HLS, cabeceras, User-Agent y DRM.
- Conversion ClearKey hexadecimal `kid:key` a JWK local.
- Conversion ClearKey desde mapas JSON `{kid:key}`.
- Configuracion host-rendered para fuentes remotas, ficheros locales y M3U inline.
