# DRM Edge Cases Implementation Plan

## Objetivo

Ampliar StreamVault Adaptive Bridge para cubrir casuisticas avanzadas que Kodi `inputstream.adaptive` suele resolver mejor: wrappers/unwrappers de licencia, server certificates, key rotation, PSSH sintetizado, parsing DRM completo desde manifests y compatibilidad fina por plataforma.

El objetivo no es saltarse DRM ni incluir claves, tokens o streams reales. El plugin debe limitarse a transportar y normalizar metadata legitima proporcionada por listas M3U/KODIPROP, manifests y servidores autorizados, dejando el descifrado en Android MediaDrm/Media3.

## Alcance

- Mejorar el modelo interno DRM del plugin.
- Extender el contrato `drm_json` hacia StreamVault host de forma compatible.
- Anadir proxy local generico de licencias para aplicar transformaciones declarativas.
- Parsear DRM desde DASH, HLS y SmoothStreaming/ISML cuando la M3U no traiga toda la informacion.
- Sintetizar o normalizar PSSH cuando sea necesario para que Media3 pueda inicializar la sesion DRM.
- Mejorar diagnostico y pruebas con fixtures ficticias.

## Fuera De Alcance

- No hardcodear canales, listas privadas, claves, tokens ni URLs reales.
- No implementar decryption propio en el plugin.
- No aceptar scripts arbitrarios o codigo remoto para transformar licencias.
- No romper compatibilidad con el `drm_json` actual ni con listas M3U ya soportadas.

## Arquitectura Actual Relevante

Paquete real: `com.streamvault.plugin.adaptivebridge` (ruta `app/src/main/java/com/streamvault/plugin/adaptivebridge/`).

- `KodiPropsM3uParser.java`: parsea M3U/KODIPROP, cabeceras, `license_type`, `license_key` y `inputstream.adaptive.drm` parcial.
- `AdaptiveChannel.java`: guarda el modelo de canal y serializa `drm_json`. Hoy hardcodea `forceDefaultLicenseUrl=true` y `playClearContentWithoutKey=true` en `drmJson()`.
- `DashClearKeyManifestRewriter.java`: reescribe MPD ClearKey y genera PSSH ClearKey v1.
- `AdaptiveBridge.java`: prepara playback, descarga/proxy de manifests, cachea manifests y resuelve fallback de cabeceras.
- `AdaptiveLocalServer.java`: expone `/playlist.m3u`, `/play/{channelId}`, `/manifest/{channelId}/...`, `/license/clearkey/{channelId}` y `/status.json`. Servidor de sockets a mano: no lee body POST (el endpoint ClearKey actual responde JWK sin consumir el cuerpo del request).
- `StreamVaultAdaptiveBridgePluginService.java`: responde `MSG_PREPARE_PLAYBACK` con URL, tipo de stream, cabeceras, User-Agent y DRM.

No existe `app/src/test/` ni `app/src/androidTest/` en el repo — la infraestructura de tests hay que bootstrappearla (ver Fase 0).

## Principios De Diseno

- KODIPROP explicito manda sobre inferencias desde manifest. La precedencia debe aplicarse en codigo, no como convencion verbal, y debe haber un test que la fije.
- Manifest manda sobre heuristicas de URL.
- Las transformaciones de licencia deben ser declarativas, auditables y limitadas.
- Los logs y diagnosticos nunca deben mostrar secretos completos.
- Cada ampliacion debe tener fixture ficticia y test unitario o de integracion local.
- Los cambios al host deben ser versionados y compatibles con clientes anteriores.

## Restriccion Legal

`xbmc/inputstream.adaptive` esta licenciado como GPL-2.0. Alinearse a sus *funcionalidades* es legitimo, pero copiar codigo, comentarios, estructuras internas de parser o tablas de mapeo introduciria contaminacion GPL en el plugin. **Toda implementacion debe partir de cero contra specs publicas**: DASH-IF MPD, ISO/IEC 23009-1 (MPEG-DASH), RFC 8216 (HLS), MS-SSTR (SmoothStreaming) y ISO/IEC 23001-7 para PSSH/Common Encryption. Si surge una duda concreta, consultar la spec o el codigo de Media3/ExoPlayer (Apache-2.0), no `inputstream.adaptive`.

## Fase 0 - Bootstrap De Tests

### Cambios

Antes de cualquier fase funcional, montar infraestructura de tests. Hoy no existe `app/src/test/` ni `app/src/androidTest/`.

### Tareas

- Anadir dependencias JUnit 4 (o 5) y Mockito a `app/build.gradle.kts` en `testImplementation`.
- Decidir si los parsers de manifest se prueban con JVM puro (preferible, mas rapido) o instrumentado. Sugerencia: tests JVM, evitar dependencias de Android framework en clases nuevas de parsing/PSSH.
- Crear `app/src/test/java/com/streamvault/plugin/adaptivebridge/` con un test sentinel que compile y ejecute.
- Crear `app/src/test/resources/fixtures/` con subcarpetas `mpd/`, `hls/`, `isml/`, `license/` vacias.
- Configurar tarea `:app:testDebugUnitTest` y dejarla pasando en CI/local antes de Fase 1.

### Criterios De Aceptacion

- `./gradlew --no-daemon :app:testDebugUnitTest` ejecuta y pasa con el test sentinel.
- Estructura preparada para anadir fixtures de cada fase.

## Fase 1 - Modelo DRM V2

### Cambios

Crear o ampliar el modelo `AdaptiveChannel.Drm` para soportar:

- `schemaVersion`
- `scheme`
- `schemeUuid`
- `licenseUrl`
- `licenseHeaders`
- `keyRequestParameters`
- `serverCertificate`
- `serverCertificateUrl`
- `requestWrapper`
- `responseUnwrapper`
- `psshBoxes`
- `defaultKids`
- `multiSession`
- `forceDefaultLicenseUrl`
- `playClearContentWithoutKey`
- `source`

### Tareas

- Mantener constructor/serializacion compatible con el modelo actual.
- Separar cabeceras de manifest/stream de cabeceras de licencia.
- Normalizar KID en UUID, hex y base64url desde un unico helper.
- Crear tests de serializacion para ClearKey actual, Widevine basico y PlayReady basico.
- Documentar que hoy `forceDefaultLicenseUrl` y `playClearContentWithoutKey` estan hardcodeados a `true` en `AdaptiveChannel.drmJson()`. Decidir defaults en v2 (recomendado: mantener `true` por defecto para no cambiar comportamiento) y exponerlos como campos opcionales del modelo.

### Criterios De Aceptacion

- Las listas ClearKey actuales siguen reproduciendo.
- `drm_json` actual sigue presente cuando el host no soporte v2.
- El nuevo `drm_json` v2 no expone claves o tokens en logs.

## Fase 2 - Contrato `drm_json` V2 Con StreamVault Host

### Cambios

Definir un contrato versionado entre plugin y host:

```json
{
  "schemaVersion": 2,
  "scheme": "WIDEVINE",
  "schemeUuid": "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",
  "licenseUrl": "http://127.0.0.1:<port>/license/proxy/<channelId>",
  "headers": {},
  "keyRequestParameters": {},
  "multiSession": true,
  "forceDefaultLicenseUrl": true,
  "playClearContentWithoutKey": true,
  "serverCertificate": "",
  "pssh": [],
  "defaultKids": []
}
```

### Tareas

- Documentar fallback a `schemaVersion` 1.
- Coordinar cambios en StreamVault host para aplicar campos nuevos en Media3.
- Anadir feature flag/capability para que el host indique soporte de v2 si fuera necesario.
- Mantener `licenseUrl` directa para casos simples y proxy local para casos con wrappers.

### Criterios De Aceptacion

- Host antiguo ignora campos nuevos sin romper playback existente.
- Host nuevo puede consumir `serverCertificate`, `keyRequestParameters`, `multiSession` y `pssh/defaultKids` si los soporta.

## Fase 3 - Proxy Local Generico De Licencias

### Cambios

Anadir endpoint:

```text
POST /license/proxy/{channelId}
```

Flujo:

1. Media3 envia key request al proxy local.
2. El plugin aplica `requestWrapper`.
3. El plugin llama al servidor de licencia real con cabeceras y parametros configurados.
4. El plugin aplica `responseUnwrapper`.
5. El plugin devuelve bytes al host.

### Wrappers Soportados Inicialmente

- `raw`: cuerpo original.
- `base64_body`: challenge binario convertido a base64.
- `json_challenge`: JSON con campo configurable, por ejemplo `{ "challenge": "<base64>" }`.
- `form_challenge`: `application/x-www-form-urlencoded` con campo configurable.

### Unwrappers Soportados Inicialmente

- `raw`: respuesta binaria original.
- `base64_body`: respuesta completa base64 -> bytes.
- `json_field_base64`: extraer campo JSON simple -> base64 -> bytes.
- `json_field_raw`: extraer campo JSON simple como string.

### Configuracion Por Canal

Los wrappers/unwrappers se declaran por canal en KODIPROP. Sintaxis propuesta:

```text
# KODIPROP:streamvault.adaptive.license_request_wrapper=json_challenge:challenge
# KODIPROP:streamvault.adaptive.license_response_unwrapper=json_field_base64:license
# KODIPROP:streamvault.adaptive.license_request_content_type=application/json
```

- Prefijo `streamvault.adaptive.*` para no chocar con KODIPROP estandar de Kodi.
- Formato `<modo>:<campo>` cuando el modo requiere nombre de campo (JSON/form).
- Si no se declara, se asume `raw`/`raw` y el plugin manda la URL directa al host (sin proxy).

### Criterio De Activacion Del Proxy

El proxy local **solo se interpone** si se cumple al menos una de estas condiciones:

- `requestWrapper != raw` o `responseUnwrapper != raw`.
- Hay `serverCertificate` y el host no soporta entregarlo via `drm_json` v2.
- Hay `keyRequestParameters` que el host no propaga.

En cualquier otro caso `drm_json` lleva `licenseUrl` directa al servidor real. Esto evita un salto innecesario y reduce superficie de fallo.

### Tareas

- Crear `LicenseProxy` o modulo equivalente.
- Reusar timeouts y helper de cabeceras de `AdaptiveBridge`.
- Propagar codigos HTTP utiles y cuerpo de error sanitizado.
- Anadir tests con license server fake local.
- **Extender `AdaptiveLocalServer`** para leer body POST (Content-Length y chunked) y hacer streaming de bytes binarios. El servidor actual no consume body en el endpoint ClearKey existente. Si la extension resulta invasiva, evaluar migracion a NanoHTTPD en una PR separada antes de esta fase.
- Anadir KODIPROP `streamvault.adaptive.license_request_wrapper` y `streamvault.adaptive.license_response_unwrapper` al parser, con validacion estricta de modos soportados.

### Criterios De Aceptacion

- ClearKey local sigue usando `/license/clearkey/{channelId}`.
- Widevine/PlayReady simples pueden usar URL directa o proxy.
- Wrappers declarativos cubren servidores que envuelven challenge/respuesta sin codigo arbitrario.

## Fase 4 - Parsing DRM Desde Manifests

### Cambios

Crear `DrmManifestParser` con parsers por formato:

- DASH/MPD:
  - `ContentProtection`
  - `schemeIdUri`
  - `cenc:default_KID`
  - `cenc:pssh`
  - `mspr:pro`
- HLS:
  - `#EXT-X-KEY`
  - `#EXT-X-SESSION-KEY`
  - `KEYFORMAT`
  - `URI`
  - `IV`
- SmoothStreaming/ISML:
  - `ProtectionHeader`
  - system ID
  - init data PlayReady

### Precedencia

1. KODIPROP `inputstream.adaptive.drm`
2. KODIPROP `license_type` / `license_key`
3. DRM extraido del manifest
4. Inferencia por tipo de manifest o URL

### Tareas

- Parsear manifests descargados durante `prepareChannel` o `manifest`.
- Cachear resultado por canal y final URL.
- Evitar descargas duplicadas aprovechando `manifestCache`.
- Anadir fixtures MPD/HLS/ISML con valores ficticios.

### Criterios De Aceptacion

- Un MPD con `ContentProtection` pero sin KODIPROP completo produce `drm_json` util.
- Un HLS con `EXT-X-SESSION-KEY` puede poblar `licenseUrl` y scheme.
- ISML sigue funcionando con ClearKey actual y prepara base para PlayReady.

## Fase 4.5 - Salvaguardas PlayReady / ISML Existentes

### Contexto

`AdaptiveChannel.rewriteSmoothStreamingManifestForClearKey` reemplaza el `SystemID` PlayReady por el `SystemID` ClearKey en manifests ISML, pero **solo** cuando `needsSmoothStreamingClearKeyManifestProxy()` devuelve `true`, es decir cuando `streamType == SMOOTH_STREAMING` y `drm.scheme == CLEARKEY` (que hoy solo lo asigna `KodiPropsM3uParser` desde KODIPROP). Cuando Fase 4 introduzca deteccion automatica de PlayReady desde `ProtectionHeader`, hay riesgo de que el nuevo parser sobreescriba `drm.scheme` a `PLAYREADY` aunque KODIPROP lo declarase como `CLEARKEY`, anulando el rewrite y rompiendo silenciosamente las listas ISML+ClearKey que hoy funcionan.

### Reglas De Precedencia A Hardcodear

- Si KODIPROP declara `inputstream.adaptive.license_type=org.w3.clearkey` o `license_type=ClearKey` (cualquier variante reconocida hoy por `KodiPropsM3uParser`), el `scheme` resultante es `CLEARKEY` y **no puede ser sobreescrito** por lo que detecte el parser de manifest. La deteccion de PlayReady en `ProtectionHeader` se registra como `drmDetectedInManifest=PLAYREADY` para diagnostico, pero **no muta** `drm.scheme`.
- El rewrite PlayReady -> ClearKey solo se aplica si `drm.scheme == CLEARKEY` y el modo es "ClearKey override explicito" desde KODIPROP. Cualquier otro camino (incluso PlayReady detectado en manifest sin KODIPROP) **debe dejar el manifest intacto** y entregar el scheme real al host.
- Si KODIPROP no declara scheme y el manifest ISML lleva PlayReady, el `scheme` final es `PLAYREADY` y no se aplica rewrite.

### Tareas

- Cambiar `AdaptiveChannel.rewriteSmoothStreamingManifestForClearKey` para que documente la condicion de "ClearKey override explicito" y opcionalmente reciba esa intencion como flag (`drm.sourcePrecedence == KODIPROP_CLEARKEY`) en lugar de inferirla de `drm.scheme`.
- En el nuevo `DrmManifestParser` (Fase 4), garantizar que `merge(kodipropDrm, manifestDrm)` nunca cambia `scheme` si `kodipropDrm.scheme != null`. Anadir assert/precondicion.
- Anadir campo `drm.source` (ya listado en Fase 1) con valores tipo `KODIPROP`, `MANIFEST_DASH`, `MANIFEST_HLS`, `MANIFEST_ISML`, `URL_HEURISTIC` para auditar de donde viene cada campo.
- Loggear cuando el parser detecte un scheme distinto al de KODIPROP, sin mutar el modelo. Util para diagnosticar listas mal configuradas.

### Tests De Regresion Obligatorios

- **ISML + KODIPROP ClearKey + manifest con `ProtectionHeader` PlayReady**: tras el merge, `drm.scheme == CLEARKEY` y `rewriteManifestForPlayback` aplica el reemplazo de SystemID. Equivalente al comportamiento actual.
- **ISML sin KODIPROP + manifest con PlayReady**: `drm.scheme == PLAYREADY`, **no** se aplica rewrite, manifest se entrega intacto.
- **ISML + KODIPROP ClearKey + manifest sin PlayReady**: comportamiento actual preservado, rewrite no es necesario, no se inserta SystemID.
- **DASH + KODIPROP ClearKey + manifest con Widevine `ContentProtection`**: ClearKey gana, `DashClearKeyManifestRewriter` actua sobre el MPD; el Widevine detectado se ignora silenciosamente.

### Criterios De Aceptacion

- Listas ISML+ClearKey existentes reproducen identicamente antes y despues de Fase 4.
- El parser de Fase 4 nunca puede degradar un canal ClearKey explicito a otro scheme.
- Diagnostico (`/status.json` o por canal) muestra `drmSource` y, si difiere, `drmDetectedInManifest`.

## Fase 5 - PSSH Builder Y Manifest Rewriter Generico

### Cambios

Extraer la generacion de PSSH de `DashClearKeyManifestRewriter` a una utilidad reusable:

- `PsshBox`
- `DashDrmManifestRewriter`
- `DrmSystemIds`

### Soporte Inicial

- ClearKey PSSH v1 actual.
- Widevine PSSH sintetico cuando hay KID pero falta `cenc:pssh`.
- Normalizacion de `cenc:default_KID`.
- Conservacion de PSSH existentes.

### Tareas

- Separar "leer DRM del MPD" de "modificar MPD".
- Insertar `ContentProtection` solo cuando falte informacion necesaria.
- No eliminar DRM metadata existente salvo que sea una conversion ClearKey explicita.
- Anadir tests binarios para validar estructura PSSH.

### Criterios De Aceptacion

- MPD ClearKey actual genera el mismo resultado funcional.
- MPD Widevine con KID pero sin PSSH puede entregar PSSH sintetico al host o al manifest.
- No se duplican entradas `ContentProtection`.

## Fase 6 - Key Rotation Y Multikey

### Cambios

Detectar multiples KID/PSSH a nivel de:

- `Period`
- `AdaptationSet`
- `Representation`
- HLS session/media keys

### Tareas

- Activar `multiSession=true` automaticamente cuando existan KID multiples o rotacion.
- Guardar `defaultKids` por Period/AdaptationSet cuando sea relevante.
- Exponer diagnostico de `kidCount`, `psshCount` y `multiSessionReason`.
- Validar que el host usa multi-session cuando corresponde.

### Criterios De Aceptacion

- Un manifest con multiples KID produce `multiSession=true`.
- Un manifest con una sola key conserva `multiSession=false` salvo configuracion explicita.
- Los cambios no degradan ClearKey de una sola key.

## Fase 7 - Server Certificates

### Cambios

Soportar certificados de servidor DRM desde:

- base64 inline
- URL
- fichero local o `content://`

### Tareas

- Parsear `server_certificate`, `serverCertificate` y variantes razonables en DRM JSON.
- Descargar/cachear certificados por URL con TTL.
- Enviar certificado al host en `drm_json` v2.
- Sanitizar logs para no imprimir el certificado completo.

### Criterios De Aceptacion

- El host puede aplicar certificado cuando Media3/MediaDrm lo soporte.
- Si el dispositivo o scheme no lo soporta, se informa con diagnostico claro.
- No se bloquea playback de canales que no usan server certificate.

## Fase 8 - Compatibilidad Fina Por Plataforma

### Cambios

Anadir decision layer para diferencias Android/MediaDrm:

- scheme soportado por dispositivo
- security level si el host lo expone
- soporte HEVC/HDR separado de DRM
- ClearKey en Android antiguo
- PlayReady limitado segun dispositivo

### Tareas

- Exponer capability request desde host o endpoint diagnostico.
- Registrar decisiones sin datos sensibles.
- Permitir fallback controlado: URL directa, proxy de manifest, proxy de licencia, o error explicativo.

### Criterios De Aceptacion

- Chromecast muestra diagnostico accionable cuando falla por plataforma.
- Nexus 5X u otros dispositivos antiguos no bloquean casos que si pueden reproducir.
- El usuario puede distinguir fallo de DRM, codec, red o metadata.

## Fase 9 - Diagnostico Y Observabilidad

### Cambios

Ampliar `/status.json` y, si conviene, anadir endpoint por canal:

```text
GET /status/channel/{channelId}
```

Hoy `statusJson()` se sirve global desde el `Handler` interno de `AdaptiveLocalServer`. Anadir endpoint por canal requiere extender ese interface con un nuevo metodo (`JSONObject channelStatusJson(String id)`) — cambio de API interna trivial pero hay que listarlo en la PR.

### Campos Sugeridos

- `streamType`
- `drmScheme`
- `drmSource`
- `licenseMode`
- `manifestProxy`
- `licenseProxy`
- `kidCount`
- `psshCount`
- `multiSession`
- `serverCertificatePresent`
- `wrapperMode`
- `lastManifestStatus`
- `lastLicenseStatus`

### Criterios De Seguridad

- No mostrar license URL completa si contiene query sensible.
- No mostrar headers sensibles.
- No mostrar claves ClearKey.
- No mostrar cuerpos de licencia.

## Fase 10 - Tests Y Validacion

### Fixtures

Crear fixtures ficticias en tests:

- MPD ClearKey con KID unico.
- MPD ClearKey con multiples KID.
- MPD Widevine con PSSH.
- MPD Widevine sin PSSH pero con `default_KID`.
- HLS con `EXT-X-KEY`.
- HLS con `EXT-X-SESSION-KEY`.
- ISML con `ProtectionHeader`.
- License server fake con wrappers JSON/form/base64.

### Tests Unitarios

- `KodiPropsM3uParser` para variantes KODIPROP.
- `DrmManifestParser` para MPD/HLS/ISML.
- `PsshBox` para estructura binaria y system IDs.
- `LicenseProxy` para wrappers/unwrappers.
- `AdaptiveChannel.drmJson` para v1/v2.

### Validacion Local

Usar el build estandar del proyecto:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew --no-daemon :app:assembleDebug :app:printVersionName :app:printVersionCode
```

Despues de cambios de codigo:

```sh
graphify update .
```

### Validacion Manual

- Instalar APK debug en Chromecast.
- Verificar discovery del plugin.
- Verificar reproduccion de casos existentes ClearKey.
- Probar fixtures o streams propios/autorizados para Widevine/PlayReady.
- Revisar `/status.json` y diagnostico por canal.

## Orden Recomendado

0. Bootstrap de tests (JUnit + estructura de fixtures).
1. Modelo DRM v2.
2. Contrato `drm_json` v2 con fallback.
3. PSSH builder y rewriter generico (utilidad antes que sus consumidores).
4. Parser DRM desde manifests + salvaguardas PlayReady/ISML (Fase 4.5).
5. Proxy generico de licencias.
6. Key rotation y multikey.
7. Server certificates.
8. Diagnostico extendido.
9. Compatibilidad por plataforma (puede ir en paralelo, depende de comportamiento Media3 en runtime).
10. Tests adicionales e instalacion en Chromecast.

Nota: la Fase 5 original (PSSH builder) se adelanta sobre la Fase 4 original (parser de manifest) porque el parser produce PSSH; conviene tener la utilidad primero. La Fase 8 original (compatibilidad plataforma) se mueve al final porque depende menos del modelo y mas de pruebas en dispositivo.

## Release Hygiene Por Fase

Cada fase que cambie comportamiento observable debe cerrar con:

- Bump de `versionName` y `versionCode` en `app/build.gradle.kts`.
- Sincronizar `AdaptiveBridge.VERSION_NAME` y `VERSION_CODE`.
- Entrada en `docs/Changelog.md` describiendo el cambio funcional (no implementacion).
- `graphify update .` para actualizar el grafo.
- Build de validacion: `./gradlew --no-daemon :app:assembleDebug :app:printVersionName :app:printVersionCode`.
- Si hay tests nuevos: `./gradlew --no-daemon :app:testDebugUnitTest`.

## Riesgos

- Cambios en el host pueden ser necesarios antes de que algunas mejoras sean visibles.
- Wrappers reales pueden tener variantes propietarias no cubiertas por transformaciones declarativas.
- PlayReady puede depender mucho del dispositivo Android concreto.
- Server certificates pueden requerir API o configuracion especifica en Media3/MediaDrm.
- Sintetizar PSSH mal puede romper streams que ya traian metadata valida.

## Preguntas Para Revisar Antes De Implementar

- Que version minima de StreamVault host aceptara `drm_json` v2?
- El host debe anunciar capabilities DRM al plugin?
- Queremos endpoint `/status/channel/{channelId}` o ampliar solo `/status.json`?
- Que wrappers declarativos son prioritarios segun listas reales del usuario?
- Debe el proxy de licencia vivir siempre en el plugin o algunos casos deben implementarse directamente en el host?
- Como versionamos cambios de contrato entre plugin y host en releases beta?

## Primer Incremento Sugerido

Implementar una primera PR pequena con:

- Fase 0 ejecutada: JUnit configurado, `app/src/test/java/...` creado, test sentinel pasando, fixtures vacias listas.
- `AdaptiveChannel.Drm` ampliado internamente con los campos de Fase 1 (todos opcionales, defaults conservadores; `forceDefaultLicenseUrl` y `playClearContentWithoutKey` siguen siendo `true` por defecto).
- `drm_json` v2 con `schemaVersion=2` y **todos los campos v1 presentes en paralelo** para que el host antiguo siga funcionando.
- Tests de serializacion para ClearKey actual, Widevine basico y PlayReady basico.
- Documentacion del contrato v1/v2 en este mismo doc o en uno acompanante.
- Release hygiene: bump version, Changelog, `graphify update .`.

Ese incremento **no debe** tocar el host, los wrappers de licencia, ni el parser de manifest. No debe cambiar reproduccion existente. Deja base para proxy generico, parsing desde manifests y PSSH avanzado.
