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

- Cliente: `POST /v1/muse/{installationId}/mcp`. Expone únicamente seis herramientas de solo lectura y limitadas a una instalación, todas recortadas del mismo resumen administrativo ya cargado (`get_business_overview`): `get_business_overview` (resumen completo), `list_workers` (devuelve `{ total, employees }` de `workers`; si no hay trabajadores, responde "Todavía no hay trabajadores registrados para esta instalación."), `get_inventory_summary` (devuelve `inventory.categories` y `damagedProducts`), `get_sales_summary` (devuelve `sales.periods` y `approvedMovements.periods`), `list_payrolls` (devuelve `{ total, periods }` de `payroll.periods`, los totales de nómina por periodo; si no hay periodos, responde "Todavía no hay periodos de nómina registrados para esta instalación.") y `get_payroll` (devuelve el periodo de nómina de la fecha pedida en el argumento `period`, formato `YYYY-MM-DD`; si esa fecha no está en el resumen, responde "No hay nómina registrada para el periodo … en esta instalación."). Solo `get_payroll` acepta un argumento (`period`); las otras cinco no aceptan ninguno.
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

## Aviso al MUSE del propietario (push por correo)

Cuando se guarda un evento de licencia, el Worker avisa por sí solo al MUSE del propietario con un correo (Brevo, API v3). Nada consulta nada: el aviso sale en el mismo momento en que se guarda el evento.

- Solo avisan `activation.requested`, `renewal.requested`, `activation.completed` y `renewal.completed`. `license.synced` (pase único de las licencias ya activas) no avisa.
- El correo lleva folio, negocio, tipo (nueva o renovación), monto (solicitud nueva) o fechas de vigencia (licencia activa). **Nunca** lleva el código de activación, el teléfono ni el nombre del titular.
- Un solo aviso por evento: antes de enviar se revisa la llave KV `notify:v1:{event_id}`; después de enviar bien se guarda con TTL de 7 días.
- Mejor esfuerzo: si Brevo falla o no responde en 5 segundos, el evento ya quedó guardado y la respuesta al productor sigue siendo `accepted`. El aviso nunca rompe el flujo comercial.
- Claves opcionales (secretos del Worker, nunca en Git):
  - `GUARDIAN_NOTIFY_BREVO_KEY`: clave API v3 de Brevo (`xkeysib-...`) para enviar el correo.
  - `GUARDIAN_NOTIFY_FROM`: dirección de envío verificada en Brevo (se recomienda una dirección dedicada, no el correo personal del usuario).
  - `GUARDIAN_NOTIFY_TO`: buzón de Muse del propietario (`<handle>@muse.ai`).
- Si falta alguna de las tres, no se envía nada y solo queda un aviso en el registro; los eventos se guardan igual.

## Datos agregados

El exportador Android construye una lista permitida de datos y no envía filas personales:

- Inventario: conteos de artículos, unidades y agotados por categoría.
- Productos dañados: conteo, sin códigos, nombres ni motivos.
- Movimientos aprobados: conteos y unidades por mes, sin importes ni identificadores.
- Nómina: totales por periodo. Se aceptan periodos con un solo trabajador.
- Trabajadores: lista de esa instalación con nombre y sueldo individual por trabajador, solo para el MUSE de esa instalación. Nunca se muestran a otra instalación ni al MUSE propietario. El sueldo es SEMANAL: el exportador toma `profile.weeklySalaryCents` del perfil (leído del JSON `"salary"`) y lo envía como `salaryCents`, en centavos por semana, no mensual. La lista va ordenada por nombre.
- Ventas: ventas netas, pagos a proveedores y préstamos entregados por mes. Se toma el último reporte de cada trabajador y fecha para no sumar reportes acumulativos repetidos; solo se utilizan reportes SYNC que sigan disponibles en el historial local.

No se exportan teléfonos, identificadores de trabajadores, productos, códigos comerciales, credenciales ni PIN. Los nombres y sueldos individuales sí se exportan, solo a la instalación a la que pertenecen: el servidor guarda el resumen solo con los campos permitidos y descarta cualquier otro dato que llegue por accidente.

### Herramientas de nómina implementadas (el snapshot sí las sostiene)

El snapshot trae nómina por periodo (`payroll.periods`, con `period` en formato `YYYY-MM-DD` y totales: `employeeCount`, `payrollCount`, `grossCents`, `absenceCents`, `earlyExitCents`, `loanCents`, `consumptionCents`, `netCents`, `deliveredCents`). Por eso existen `list_payrolls` (todos los periodos guardados, como máximo los últimos 10 por la retención) y `get_payroll` (un periodo por su fecha, argumento `period`). Son totales agregados por periodo, no sueldos individuales: el sueldo individual de cada trabajador vive en `workers.employees` y se consulta con `list_workers`.

### Herramientas no implementadas (el snapshot no tiene los datos)

De la lista aceptada, estas diez herramientas **no** se implementan porque el snapshot actual no trae los datos para responderlas sin inventar campos, datos ni identificadores. Para implementar cualquiera de ellas, primero el exportador Android y el tipo `Snapshot` de `src/index.ts` tendrían que incluir esa categoría de datos:

- `list_inventory_items`: el inventario trae solo conteos por categoría (`itemCount`, `unitsInStock`, `lowStockCount`, `outOfStockCount`); no hay lista de artículos (sin código, nombre ni existencias por artículo).
- `get_inventory_item`: no existe ningún identificador de artículo en el snapshot (sin código de producto), así que no hay por qué buscar un artículo.
- `list_out_of_stock_items`: solo existe el conteo `outOfStockCount` por categoría; no hay lista de qué artículos están agotados.
- `list_sales_movements`: ventas (`sales.periods`) y movimientos aprobados (`approvedMovements.periods`) traen solo totales y conteos por mes; no hay movimientos ni ventas individuales (sin fecha, importe ni identificador por movimiento).
- `list_admin_reports`: el snapshot no trae reportes individuales; de ventas solo trae `reportCount` por mes, sin identificador, título ni contenido de reporte.
- `get_admin_report`: igual que la anterior: no hay reportes individuales ni identificador de reporte en el snapshot.
- `get_worker`: `workers.employees` trae solo nombre y sueldo (`name`, `salaryCents`), sin identificador estable de trabajador; el nombre no es una clave única ni estable (puede repetirse o cambiar), así que no se inventa una búsqueda por nombre.
- `list_payroll_requests`: el snapshot no trae solicitudes de nómina (sin lista de solicitudes, estados ni folios).
- `list_attendance`: el snapshot no trae ningún dato de asistencia.
- `get_attendance_summary`: igual que la anterior: no hay registros de asistencia ni de jornada en el snapshot.

`get_inventory_summary` devuelve los conteos por categoría y el conteo de dañados, y `get_sales_summary` devuelve los totales mensuales de ventas y de movimientos aprobados.

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

El snapshot solo guarda agregados por periodo, no filas individuales: no existen reportes individuales, movimientos individuales ni registros de asistencia en el servidor (ver "Herramientas no implementadas (el snapshot no tiene los datos)"). Por eso la regla de "últimos 10 reportes / movimientos / asistencias individuales" **no se puede cumplir en el servidor con el modelo actual**: no hay nada que recortar. Cuando el exportador y el snapshot lleguen a incluir esas categorías individuales, la misma poda en la ingesta (últimos 10 al guardar) deberá aplicarse a cada una. Tampoco se poda el inventario por categoría (son conteos actuales, no historial) ni los productos dañados (un solo conteo).

## Configuración y estado

- `wrangler.toml` conserva un ID de KV placeholder. No sustituirlo ni desplegar hasta revisar la configuración y el plan gratuito.
- El Worker requiere secretos para administración, cifrado, HMAC y autenticación central del propietario. Los secretos no se guardan en Git ni se solicitan por chat. Las tres claves de aviso al propietario son opcionales (ver [Aviso al MUSE del propietario](#aviso-al-muse-del-propietario-push-por-correo)).
- El productor de SENE VENTAS todavía no envía los eventos cifrados al canal del propietario. Por tanto, el MCP propietario está preparado en código, pero aún no recibe solicitudes reales.
- El exportador Android está implementado como llamada explícita desde hilo de fondo; no se ha integrado un envío automático ni se ha generado una APK nueva.
- La URL del exportador sigue apuntando al Worker de pruebas. No es configuración de producción.

## Verificación automatizada

- Worker CI: TypeScript y pruebas automatizadas de aislamiento entre instalaciones, revocación, validación de snapshots, datos agregados y separación del canal del propietario. Incluye pruebas de `list_workers` (con trabajadores y sin trabajadores), `get_inventory_summary` y `get_sales_summary`, y de `list_payrolls` y `get_payroll` (lista de periodos, periodo por fecha, periodo inexistente, sin nómina, y argumento `period` faltante, mal formado o con campos extra).
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
