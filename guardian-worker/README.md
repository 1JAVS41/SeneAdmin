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

- Cliente: `POST /v1/muse/{installationId}/mcp`. Expone únicamente cuatro herramientas de solo lectura y limitadas a una instalación, todas sin argumentos y recortadas del mismo resumen administrativo ya cargado (`get_business_overview`): `get_business_overview` (resumen completo), `list_workers` (devuelve `{ total, employees }` de `workers`; si no hay trabajadores, responde "Todavía no hay trabajadores registrados para esta instalación."), `get_inventory_summary` (devuelve `inventory.categories` y `damagedProducts`) y `get_sales_summary` (devuelve `sales.periods` y `approvedMovements.periods`).
- Propietario: `POST /v1/muse/owner/mcp`. Expone `list_owner_license_requests` (solicitudes pendientes, con folio y código) y `list_owner_active_licenses` (total y lista de licencias activas, sin código), autenticado con la credencial central del propietario. No acepta tokens de instalación.
- Las solicitudes de licencia nueva y renovación pertenecen exclusivamente al MUSE del propietario. No se exponen en el MCP de clientes ni se ejecutan acciones de aprobación, activación o envío.

## Licencias activas (solo propietario)

Una licencia activa es una activación o renovación **completada** que el productor de SENE VENTAS envió al canal del propietario. No se inventa ninguna licencia a partir de una solicitud pendiente: si no hay eventos completados, el total es 0.

Contrato del productor (evento cifrado a `POST /v1/guardian/owner-events`, mismos encabezados, HMAC y AES-GCM que las solicitudes):

- `event_type`: `"activation.completed"` o `"renewal.completed"`.
- `payment_status`: `"paid"`.
- `valid_from` / `valid_until`: fechas ISO, con `valid_until` después de `valid_from`.
- Los demás campos son los mismos que en una solicitud: `event_id` (UUID), `installation_id`, `folio` (`SENE-...`), `request_type` (`activation` o `renewal`), `created_at`, `amount_cents` (> 0), `currency` (`MXN`), `business_name`, `holder_name`, `whatsapp`, `code`.
- Retención: una licencia completada se guarda para siempre (sin fecha de caducidad en KV); una solicitud pendiente se guarda un día. Ver [Retención y limpieza cada 30 días](#retención-y-limpieza-cada-30-días).
- `GET /v1/guardian/owner-events?kind=active` devuelve `{ total, licenses }` con solo `folio`, `installationId`, `businessName`, `requestType`, `validFrom`, `validUntil` y `status: "active"`, ordenadas por vencimiento. Sin código, sin WhatsApp, sin nombre del titular y sin importe.
- `GET /v1/guardian/owner-events` (sin `kind`) sigue devolviendo solo las solicitudes pendientes.

**Pendiente:** el productor de SENE VENTAS todavía no envía eventos completados (tampoco envía las solicitudes). Hasta que lo haga, `list_owner_active_licenses` devuelve total 0, lo cual no es un error ni significa que no haya licencias: significa que el servidor no ha recibido ninguna.

## Datos agregados

El exportador Android construye una lista permitida de datos y no envía filas personales:

- Inventario: conteos de artículos, unidades y agotados por categoría.
- Productos dañados: conteo, sin códigos, nombres ni motivos.
- Movimientos aprobados: conteos y unidades por mes, sin importes ni identificadores.
- Nómina: totales por periodo. Se aceptan periodos con un solo trabajador.
- Trabajadores: lista de esa instalación con nombre y sueldo individual por trabajador, solo para el MUSE de esa instalación. Nunca se muestran a otra instalación ni al MUSE propietario. El sueldo es SEMANAL: el exportador toma `profile.weeklySalaryCents` del perfil (leído del JSON `"salary"`) y lo envía como `salaryCents`, en centavos por semana, no mensual. La lista va ordenada por nombre.
- Ventas: ventas netas, pagos a proveedores y préstamos entregados por mes. Se toma el último reporte de cada trabajador y fecha para no sumar reportes acumulativos repetidos; solo se utilizan reportes SYNC que sigan disponibles en el historial local.

No se exportan teléfonos, identificadores de trabajadores, productos, códigos comerciales, credenciales ni PIN. Los nombres y sueldos individuales sí se exportan, solo a la instalación a la que pertenecen: el servidor guarda el resumen solo con los campos permitidos y descarta cualquier otro dato que llegue por accidente.

### Por qué no existen herramientas de lista de artículos ni de movimientos individuales

El snapshot no contiene lista de artículos ni movimientos individuales, solo conteos y totales: inventario trae conteos por categoría, productos dañados trae solo un conteo, ventas y movimientos aprobados traen solo totales por mes. Por eso no existen `list_out_of_stock_items`, `get_inventory_item` ni `list_sales_movements`: no hay datos en el resumen para responderlas sin inventar campos. `get_inventory_summary` devuelve los conteos por categoría y el conteo de dañados, y `get_sales_summary` devuelve los totales mensuales de ventas y de movimientos aprobados.

## Retención y limpieza cada 30 días

Regla del usuario (2026-11-27): todo el proyecto debe ser ahorrativo en solicitudes al servidor.

- **Por negocio, solo los últimos 10 de cada categoría.** Al guardar un snapshot (`POST /v1/snapshot`), el servidor recorta a los últimos 10 periodos cada categoría agregada: movimientos aprobados, nómina por periodo y ventas por mes. Los periodos más antiguos se descartan en la ingesta; el exportador puede seguir enviando hasta 36 periodos y el servidor guarda solo los últimos 10.
- **El historial de licencias se conserva siempre.** Un evento de licencia completado (`activation.completed` / `renewal.completed`) se guarda en KV sin fecha de caducidad (sin `expirationTtl`): nunca se borra por la limpieza, por si un cliente regresa a SENE-VENTAS. Antes se guardaba hasta un día después de su vencimiento, con un máximo de 90 días; ese tope ya no existe.
- **La caducidad de las licencias no cambia.** Cada licencia vence en su fecha establecida (`valid_until` se guarda intacta). Que un evento completado se conserve para siempre no lo mantiene activo: `list_owner_active_licenses` solo muestra las licencias con `valid_until` vigente. Una licencia vencida queda en el historial, pero no aparece como activa.
- **El snapshot se conserva 30 días.** Si una instalación no envía un snapshot nuevo en 30 días, KV lo elimina solo (`expirationTtl` de 30 días; antes 35).
- **Las solicitudes pendientes siguen guardándose un día**, como antes.
- **Sin gasto extra de solicitudes.** No hay trabajo programado ni endpoint de limpieza: la poda ocurre al guardar (el servidor ya reescribía el snapshot) y los eventos completados simplemente no llevan TTL, así que la limpieza no cuesta lecturas ni escrituras adicionales. No se añadió ningún cron de Cloudflare ni se tocan los secretos.
- **La lista de trabajadores no se poda.** Es la plantilla actual del negocio, no un historial: si un negocio tiene 12 trabajadores, se guardan los 12.

### Limitación documentada (sin inventar datos)

El snapshot solo guarda agregados por periodo, no filas individuales: no existen reportes individuales, movimientos individuales ni registros de asistencia en el servidor (ver "Por qué no existen herramientas de lista de artículos ni de movimientos individuales"). Por eso la regla de "últimos 10 reportes / movimientos / asistencias individuales" **no se puede cumplir en el servidor con el modelo actual**: no hay nada que recortar. Cuando el exportador y el snapshot lleguen a incluir esas categorías individuales, la misma poda en la ingesta (últimos 10 al guardar) deberá aplicarse a cada una. Tampoco se poda el inventario por categoría (son conteos actuales, no historial) ni los productos dañados (un solo conteo).

## Configuración y estado

- `wrangler.toml` conserva un ID de KV placeholder. No sustituirlo ni desplegar hasta revisar la configuración y el plan gratuito.
- El Worker requiere secretos para administración, cifrado, HMAC y autenticación central del propietario. Los secretos no se guardan en Git ni se solicitan por chat.
- El productor de SENE VENTAS todavía no envía los eventos cifrados al canal del propietario. Por tanto, el MCP propietario está preparado en código, pero aún no recibe solicitudes reales.
- El exportador Android está implementado como llamada explícita desde hilo de fondo; no se ha integrado un envío automático ni se ha generado una APK nueva.
- La URL del exportador sigue apuntando al Worker de pruebas. No es configuración de producción.

## Verificación automatizada

- Worker CI: TypeScript y pruebas automatizadas de aislamiento entre instalaciones, revocación, validación de snapshots, datos agregados y separación del canal del propietario. Incluye pruebas de `list_workers` (con trabajadores y sin trabajadores), `get_inventory_summary` y `get_sales_summary`.
- Retención (`test/retention.test.ts`): poda a los últimos 10 periodos por categoría al guardar el snapshot, TTL de 30 días del snapshot, plantilla de trabajadores completa (no se poda), eventos de licencia completados guardados sin caducidad (historial permanente) frente a solicitudes pendientes con un día, y caducidad de licencias sin cambios (una licencia vencida queda en el historial pero no se lista como activa).
- Android: workflow de compilación Kotlin; no ensambla APK. El workflow `guardian-android-compile.yml` solo corre en push, no en `pull_request`, así que la compilación Kotlin no corre en los PR de esta rama.
- Las pruebas no sustituyen la verificación de runtime real en Cloudflare ni la validación con datos de una instalación.

## Pendiente antes de producción

1. Completar y probar el productor de eventos de licencia hacia el MUSE del propietario, sin enviar códigos al MUSE cliente. El productor debe enviar también los eventos completados (`activation.completed` / `renewal.completed`, con `payment_status: "paid"` y `valid_from` / `valid_until`) para que `list_owner_active_licenses` pueda devolver licencias reales; hoy devuelve total 0 porque no recibe ninguno.
2. Verificar secretos, KV, cuotas y límites del plan gratuito. La retención ya está implementada en código (últimos 10 periodos, historial de licencias permanente, TTL de 30 días); falta comprobarla en runtime real en Cloudflare.
3. Integrar consentimiento, configuración y revocación del exportador en el flujo Android.
4. Probar en entorno de pruebas real el aislamiento, la revocación, la ingestión, los MCP cliente/propietario y el cifrado de eventos.
5. Confirmar los límites de privacidad y la retención de datos antes de activar el servicio.

No desplegar ni enviar datos reales hasta cerrar estos puntos y comprobar que no generan cargos.
