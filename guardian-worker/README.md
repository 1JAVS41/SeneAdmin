# SENE GUARDIAN Worker (staging scaffold)

Código fuente de preparación. No está desplegado y no contiene credenciales ni datos reales.

## Rutas
- `GET /v1/health`: estado del servicio, sin información del negocio.
- `POST /v1/admin/installations`: provisiona una instalación. Requiere `GUARDIAN_ADMIN_TOKEN`. Devuelve una sola vez los tokens independientes de ingestión y lectura Muse.
- `POST /v1/admin/installations/revoke`: revoca una instalación y elimina su último snapshot. Requiere `GUARDIAN_ADMIN_TOKEN`.
- `POST /v1/snapshot`: ingestión autenticada con token exclusivo de esa instalación.
- `GET /v1/guardian/business-overview?installationId=...`: consulta con token Muse exclusivo de esa instalación.

## Secretos y aprovisionamiento (pendiente; no realizado)
Configurar en Cloudflare Worker Secrets:
- `GUARDIAN_ADMIN_TOKEN`: secreto administrativo aleatorio de al menos 32 bytes. No reutilizar credenciales de SeneAdmin, SENE-VENTAS, GitHub o Railway.
- No se configuran tokens globales de ingestión ni lectura. La ruta administrativa genera tokens independientes por instalación y guarda sus hashes SHA-256 en KV. Los valores en claro se devuelven una sola vez; quien los pierda debe revocar y volver a provisionar con un flujo de rotación futuro.

Crear el namespace KV, sustituir el ID placeholder de wrangler.toml y revisar cuotas/retención del plan Free antes de desplegar.

## Límite de datos
El esquema solo acepta identificador aleatorio e independiente, fecha, estado general y conteos agregados de inventario. No acepta nombres de empleados, teléfonos, nómina, asistencia individual, reportes, códigos de producto, precios, claves, licencias ni códigos de vinculación SENE-VENTAS.

El identificador de instalación no se deriva de códigos comerciales ni de licencia. KV conserva el snapshot por 35 días y solo la última fotografía. Revocar elimina el snapshot e impide el acceso posterior.

## Verificación automatizada preparada
- `npm run typecheck`: comprobación TypeScript.
- `npm test`: pruebas de salud, credenciales por instalación, aislamiento cruzado, rechazo de snapshots antiguos, revocación y validación básica.
- GitHub Actions ejecuta ambas comprobaciones al cambiar archivos del Worker en esta rama.

Estas pruebas están escritas, pero todavía no se ha confirmado un resultado exitoso de CI.

## Bloqueos antes de producción
1. Añadir y probar rate limiting por instalación, cuotas y auditoría de accesos.
2. Añadir protección de tamaño de cuerpo en streaming y pruebas automatizadas de payloads grandes.
3. Probar concurrencia y evitar carreras en el aprovisionamiento.
4. Ejecutar pruebas locales con Wrangler/Workers runtime: auth separada, revocación, aislamiento cruzado, snapshots antiguos, validación de esquema, TTL y payloads inválidos.
5. Implementar el exportador Android con consentimiento, revocación y DTO allowlist. No se ha modificado la APK.
6. Confirmar protocolo real soportado por Muse; esta API REST no es un servidor MCP.
7. Verificar política de privacidad, consentimiento y coste/cupo gratuito.

No desplegar ni enviar datos reales hasta cerrar todos los bloqueos.
