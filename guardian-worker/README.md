# SENE GUARDIÁN Worker · despliegue en Cloudflare

Este Worker proporciona a MUSE una consulta administrativa de solo lectura, separada por instalación. La ruta MCP de cliente verifica la licencia SENE VENTAS en cada consulta y deniega los datos si está vencida o si la API de licencias no responde correctamente. El canal MCP del propietario permanece separado.

## 1. Crear el almacenamiento KV

Con Wrangler autenticado en la cuenta Cloudflare correcta:

```bash
cd guardian-worker
npx wrangler kv namespace create GUARDIAN_DATA
```

Copia el `id` devuelto y sustituye únicamente `REPLACE_WITH_KV_NAMESPACE_ID` en `wrangler.toml`. El binding debe llamarse exactamente `GUARDIAN_DATA`. No inventes ni reutilices IDs de otra cuenta.

## 2. Configurar secretos

Genera un secreto administrativo privado de al menos 32 bytes, por ejemplo localmente con `openssl rand -hex 32`. Configura exactamente el mismo valor en este Worker y en el Worker `server-sene-api` de SENE VENTAS:

```bash
npx wrangler secret put GUARDIAN_ADMIN_TOKEN
```

No pegues el valor en el repositorio, en WhatsApp ni en esta conversación.

Este Worker también usa los secretos ya existentes para el canal central del propietario:
- `GUARDIAN_DELIVERY_ENC_KEY`
- `GUARDIAN_DELIVERY_HMAC_KEY`
- `GUARDIAN_OWNER_CENTRAL_TOKEN_HASH`

Consérvalos sin cambios. No los regeneres como parte de esta integración porque podría interrumpirse la conexión del MUSE propietario. Si alguno falta en producción, detén el despliegue y revisa primero la configuración actual.

## 3. Variables opcionales

- `SENE_API_URL`: URL base del Worker SENE VENTAS; si se omite, se usa `https://server-sene-api.nagavs28.workers.dev`.
- `MUSE_ALLOWED_ORIGINS`: lista separada por comas solo si se requieren clientes de navegador con encabezado Origin. Las llamadas servidor-a-servidor pueden omitirlo.

## 4. Desplegar y verificar

Después de confirmar KV y secretos:

```bash
npx wrangler deploy
```

Comprueba `GET https://sene-guardian-api.nagavs28.workers.dev/v1/health`, el binding KV y que el secreto administrativo coincide con el de SENE VENTAS. En SENE VENTAS conserva D1 `DB` y los secretos Telegram; agrega `GUARDIAN_ADMIN_TOKEN` sin sustituir los existentes.

No ejecutes aprovisionamiento real hasta confirmar ambos Workers. La validación funcional debe cubrir licencia activa, vencida y error de API. La ruta de cliente es `/v1/muse/{installationId}/mcp`; la del MUSE propietario es `/v1/muse/owner/mcp` y debe permanecer separada.

## 5. Alcance de la captura

SeneAdmin envía una captura agregada cuando el propietario la autoriza y pulsa el envío manual. No es sincronización automática en segundo plano. Se envían contadores de inventario, personal, nómina, asistencia, reportes y productos dañados; no se envían nombres, teléfonos, importes individuales ni textos de reportes. Las ventas individuales no tienen una lista propia disponible en SeneAdmin, por lo que no se deben inventar.

## 6. Pruebas

`npm run typecheck` y `npm test` comprueban tipos y rutas del Worker. Las pruebas automatizadas ya pasaron en CI antes del último ajuste del exportador Android; vuelve a ejecutarlas antes del despliegue final. No se ha desplegado este código en Cloudflare.



## Inventario central para el MUSE propietario

- La ruta MCP existente `/v1/muse/owner/mcp` conserva la credencial central exclusiva; no requiere reconectar ni rotar el token del propietario.
- La herramienta de solo lectura `list_owner_license_inventory` consulta directamente la misma base D1 de licencias usada por SERVER-SENE mediante el binding `SENE_LICENSE_DB`. No realiza peticiones HTTP a SENE VENTAS ni necesita una ruta de inventario en ese Worker.
- En `wrangler.toml`, configura `SENE_LICENSE_DB` con el `database_name` y `database_id` de la base D1 existente de SERVER-SENE. No crees otra base: ambos Workers deben apuntar a la misma.
- `list_owner_license_requests` continúa leyendo los eventos cifrados de solicitudes y códigos del canal central del propietario.
- El binding se utiliza solo después de autenticar la credencial central existente del propietario. No se expone en MCP de clientes.
- Conserva sin cambios `GUARDIAN_OWNER_CENTRAL_TOKEN_HASH`, `GUARDIAN_DELIVERY_ENC_KEY` y `GUARDIAN_DELIVERY_HMAC_KEY`.
