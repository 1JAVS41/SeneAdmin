# SENE GUARDIÁN Worker

Código de desarrollo de SENE GUARDIÁN. Esta rama no se ha desplegado en producción ni ha modificado la APK publicada.

## Rutas HTTP

- `GET /v1/health`: estado del servicio; no devuelve datos del negocio.
- `POST /v1/admin/installations`: aprovisiona una instalación con `GUARDIAN_ADMIN_TOKEN`. Genera credenciales separadas de ingestión y lectura MUSE; los valores en claro se devuelven una sola vez.
- `POST /v1/admin/installations/revoke`: revoca la instalación y elimina su último snapshot.
- `POST /v1/snapshot`: recibe el resumen de una instalación con su token exclusivo de ingestión.
- `GET /v1/guardian/business-overview?installationId=...`: consulta autenticada con el token MUSE exclusivo de esa instalación.
- `POST /v1/guardian/owner-events`: recibe eventos de licencia cifrados con AES-GCM y autenticados con HMAC.
- `GET /v1/guardian/owner-events`: recupera eventos únicamente con la credencial central del propietario.

## Conectores MUSE

- Cliente: `POST /v1/muse/{installationId}/mcp`. Expone únicamente `get_business_overview`, de solo lectura y limitado a una instalación.
- Propietario: `POST /v1/muse/owner/mcp`. Expone únicamente `list_owner_license_requests`, autenticado con la credencial central del propietario. No acepta tokens de instalación.
- Las solicitudes de licencia nueva y renovación pertenecen exclusivamente al MUSE del propietario. No se exponen en el MCP de clientes ni se ejecutan acciones de aprobación, activación o envío.

## Datos agregados

El exportador Android construye una lista permitida de datos y no envía filas personales:

- Inventario: conteos de artículos, unidades y agotados por categoría.
- Productos dañados: conteo, sin códigos, nombres ni motivos.
- Movimientos aprobados: conteos y unidades por mes, sin importes ni identificadores.
- Nómina: totales por periodo; omite los importes si hay menos de tres trabajadores distintos.
- Ventas: ventas netas, pagos a proveedores y préstamos entregados por mes. Se toma el último reporte de cada trabajador y fecha para no sumar reportes acumulativos repetidos; solo se utilizan reportes SYNC que sigan disponibles en el historial local.

No se exportan nombres, teléfonos, identificadores de trabajadores, productos, códigos comerciales, credenciales ni importes salariales individuales.

## Configuración y estado

- `wrangler.toml` conserva un ID de KV placeholder. No sustituirlo ni desplegar hasta revisar la configuración y el plan gratuito.
- El Worker requiere secretos para administración, cifrado, HMAC y autenticación central del propietario. Los secretos no se guardan en Git ni se solicitan por chat.
- El productor de SENE VENTAS todavía no envía los eventos cifrados al canal del propietario. Por tanto, el MCP propietario está preparado en código, pero aún no recibe solicitudes reales.
- El exportador Android está implementado como llamada explícita desde hilo de fondo; no se ha integrado un envío automático ni se ha generado una APK nueva.
- La URL del exportador sigue apuntando al Worker de pruebas. No es configuración de producción.

## Verificación automatizada

- Worker CI: TypeScript y pruebas automatizadas de aislamiento entre instalaciones, revocación, validación de snapshots, datos agregados y separación del canal del propietario.
- Android: workflow de compilación Kotlin; no ensambla APK.
- Las pruebas no sustituyen la verificación de runtime real en Cloudflare ni la validación con datos de una instalación.

## Pendiente antes de producción

1. Completar y probar el productor de eventos de licencia hacia el MUSE del propietario, sin enviar códigos al MUSE cliente.
2. Verificar secretos, KV, cuotas, retención y límites del plan gratuito.
3. Integrar consentimiento, configuración y revocación del exportador en el flujo Android.
4. Probar en entorno de pruebas real el aislamiento, la revocación, la ingestión, los MCP cliente/propietario y el cifrado de eventos.
5. Confirmar los límites de privacidad y la retención de datos antes de activar el servicio.

No desplegar ni enviar datos reales hasta cerrar estos puntos y comprobar que no generan cargos.
