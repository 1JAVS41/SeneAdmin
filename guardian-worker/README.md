# SENE GUARDIÁN Worker

Este Worker ofrece dos conectores MCP separados y de solo lectura:

- **MUSE propietario:** `/v1/muse/owner/mcp`, autenticado con la credencial central exclusiva ya existente.
- **MUSE de cada cliente:** `/v1/muse/{installationId}/mcp`, autenticado con el token propio de esa instalación.

El MUSE propietario conserva sus credenciales y conexión actuales. No se debe volver a invitar, reconectar ni rotar su token como parte de estos cambios.

## Datos y separación

- El almacenamiento propio de GUARDIÁN es el binding KV `GUARDIAN_DATA`.
- No se conecta a D1 de SENE VENTAS ni consulta su tabla de negocios para construir el inventario de licencias.
- No llama a `/redeem`, no administra licencias y no ejecuta aprobaciones, activaciones, renovaciones ni cancelaciones.
- El conector de clientes solo entrega indicadores de su propia instalación: inventario, personal, totales de nómina, asistencia, reportes y productos dañados.
- El conector de clientes no publica licencias, folios, códigos, solicitudes ni información de otros negocios.
- La verificación de vigencia que ya existe para el MUSE cliente consulta únicamente el estado de suscripción mediante la ruta `/subscription/status`; si no puede comprobarlo, deniega la consulta. Esto no convierte a SERVER-SENE en el servidor de GUARDIÁN.

## Herramientas del MUSE propietario

- `list_owner_license_requests`: consulta solicitudes pendientes de licencia nueva y renovación recibidas por el canal cifrado del propietario.
- `list_owner_license_inventory`: herramienta reservada al propietario. Actualmente informa que el inventario no está disponible porque GUARDIÁN todavía no recibe una fuente autenticada de altas y renovaciones completadas. No debe mostrar cero licencias ni datos inventados.

Las solicitudes recibidas no equivalen al inventario de licencias activas. El canal actual de eventos contiene solicitudes pendientes; no demuestra por sí mismo qué licencias fueron activadas o renovadas después. Para habilitar el inventario real se necesita un emisor autorizado de eventos de activación/renovación hacia GUARDIÁN. Hasta contar con ese emisor, no se debe fingir sincronización ni modificar SERVER-SENE para obtenerla.

## Configuración y despliegue

El archivo `wrangler.toml` declara el binding `GUARDIAN_DATA`. Su ID debe corresponder al namespace KV existente de GUARDIÁN. El valor de marcador `REPLACE_WITH_KV_NAMESPACE_ID` no es un ID válido: antes de cualquier despliegue debe identificarse el namespace ya existente y verificarse que pertenece a la cuenta correcta. No crear namespaces, bases de datos ni servicios nuevos para resolverlo.

Secretos que deben conservarse sin cambios:

- `GUARDIAN_OWNER_CENTRAL_TOKEN_HASH`
- `GUARDIAN_DELIVERY_ENC_KEY`
- `GUARDIAN_DELIVERY_HMAC_KEY`
- `GUARDIAN_ADMIN_TOKEN` (si sigue siendo necesario para la administración de instalaciones)

No imprimir ni compartir secretos. No regenerar la credencial central del propietario.

## Pruebas

Desde `guardian-worker`:

```bash
npm run typecheck
npm test
```

Las pruebas automatizadas del Worker pasaron en CI para el último cambio registrado en la rama de trabajo. El APK se compila en un flujo separado y no demuestra que GUARDIÁN esté desplegado ni que el inventario central esté sincronizado.

## Límites de esta rama

Los cambios de esta rama no deben desplegarse en producción sin autorización. No modificar SERVER-SENE, su base D1, el flujo comercial de SENE VENTAS ni la conexión exclusiva del MUSE propietario. Cualquier costo o recurso nuevo requiere autorización previa.
