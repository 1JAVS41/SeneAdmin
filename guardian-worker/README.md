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
- Propietario: `POST /v1/muse/owner/mcp`. Expone `list_owner_license_requests` (solicitudes pendientes, con folio y código) y `list_owner_active_licenses` (total y lista de licencias activas, sin código), autenticado con la credencial central del propietario. No acepta tokens de instalación.
- Las solicitudes de licencia nueva y renovación pertenecen exclusivamente al MUSE del propietario. No se exponen en el MCP de clientes ni se ejecutan acciones de aprobación, activación o envío.

## Licencias activas (solo propietario)

Una licencia activa es una activación o renovación **completada** que el productor de SENE VENTAS envió al canal del propietario. No se inventa ninguna licencia a partir de una solicitud pendiente: si no hay eventos completados, el total es 0.

Contrato del productor (evento cifrado a `POST /v1/guardian/owner-events`, mismos encabezados, HMAC y AES-GCM que las solicitudes):

- `event_type`: `"activation.completed"` o `"renewal.completed"`.
- `payment_status`: `"paid"`.
- `valid_from` / `valid_until`: fechas ISO, con `valid_until` después de `valid_from`.
- Los demás campos son los mismos que en una solicitud: `event_id` (UUID), `installation_id`, `folio` (`SENE-...`), `request_type` (`activation` o `renewal`), `created_at`, `amount_cents` (> 0), `currency` (`MXN`), `business_name`, `holder_name`, `whatsapp`, `code`.
- Retención: una licencia completada se guarda hasta un día después de su `valid_until` (máximo 90 días); una solicitud pendiente se guarda un día.
- `GET /v1/guardian/owner-events?kind=active` devuelve `{ total, licenses }` con solo `folio`, `installationId`, `businessName`, `requestType`, `validFrom`, `validUntil` y `status: "active"`, ordenadas por vencimiento. Sin código, sin WhatsApp, sin nombre del titular y sin importe.
- `GET /v1/guardian/owner-events` (sin `kind`) sigue devolviendo solo las solicitudes pendientes.

**Pendiente:** el productor de SENE VENTAS todavía no envía eventos completados (tampoco envía las solicitudes). Hasta que lo haga, `list_owner_active_licenses` devuelve total 0, lo cual no es un error ni significa que no haya licencias: significa que el servidor no ha recibido ninguna.

## Datos agregados

El exportador Android construye una lista permitida de datos y no envía filas personales:

- Inventario: conteos de artículos, unidades y agotados por categoría.
- Productos dañados: conteo, sin códigos, nombres ni motivos.
- Movimientos aprobados: conteos y unidades por mes, sin importes ni identificadores.
- Nómina: totales por periodo. Se aceptan periodos con un solo trabajador.
- Trabajadores: lista de esa instalación con nombre y sueldo individual por trabajador, solo para el MUSE de esa instalación. Nunca se muestran a otra instalación ni al MUSE propietario.
- Ventas: ventas netas, pagos a proveedores y préstamos entregados por mes. Se toma el último reporte de cada trabajador y fecha para no sumar reportes acumulativos repetidos; solo se utilizan reportes SYNC que sigan disponibles en el historial local.

No se exportan teléfonos, identificadores de trabajadores, productos, códigos comerciales, credenciales ni PIN. Los nombres y sueldos individuales sí se exportan, solo a la instalación a la que pertenecen: el servidor guarda el resumen solo con los campos permitidos y descarta cualquier otro dato que llegue por accidente.

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

1. Completar y probar el productor de eventos de licencia hacia el MUSE del propietario, sin enviar códigos al MUSE cliente. El productor debe enviar también los eventos completados (`activation.completed` / `renewal.completed`, con `payment_status: "paid"` y `valid_from` / `valid_until`) para que `list_owner_active_licenses` pueda devolver licencias reales; hoy devuelve total 0 porque no recibe ninguno.
2. Verificar secretos, KV, cuotas, retención y límites del plan gratuito.
3. Integrar consentimiento, configuración y revocación del exportador en el flujo Android.
4. Probar en entorno de pruebas real el aislamiento, la revocación, la ingestión, los MCP cliente/propietario y el cifrado de eventos.
5. Confirmar los límites de privacidad y la retención de datos antes de activar el servicio.

No desplegar ni enviar datos reales hasta cerrar estos puntos y comprobar que no generan cargos.
