# SeneAdmin — recepción de reportes SERVER-SENE V1

## Estado de cliente

Esta especificación acompaña la implementación de `ReportInboxStore` y `AdminPayrollRequestSync` en la rama `feature/loan-consumption-approval-client`.

SeneAdmin envía el ACK pendiente en `ba` al consultar:

- `POST /admin/payroll/requests/sync`

El servidor entrega reportes del negocio en la respuesta bajo `b` (alias legible: `reports`). Cada sobre conserva el formato compacto de SENE VENTAS:

| Campo | Significado |
|---|---|
| `e` | ID idempotente del evento |
| `w` | ID del trabajador |
| `k` | Código de tipo de reporte acordado |
| `i` | Fecha/hora de creación |
| `b` | Sobre de reporte cifrado, opaco para el servidor |

El servidor debe filtrar por el negocio asociado al token de SeneAdmin y entregar eventos pendientes de ese negocio únicamente.

## ACK de recepción

1. SeneAdmin valida que el sobre tenga ID y payload.
2. Guarda el sobre completo en un archivo local cifrado con AES-GCM y una clave exclusiva de la instalación, protegida por Android Keystore.
3. Solo después de confirmar la escritura atómica incorpora el ID a la cola de ACK.
4. En la siguiente consulta envía esos IDs en `ba`.
5. El servidor confirma los IDs aceptados en `baa` (alias `report_ack_acks`).
6. SeneAdmin elimina de su cola local únicamente los ACK confirmados por el servidor.

El servidor no debe eliminar su copia por el simple hecho de incluir el reporte en una respuesta. Debe esperar el ACK `ba`, confirmar en `baa` y mantener idempotencia por `e`.

## Importación local y compatibilidad

El buzón conserva los sobres hasta que el patrón importe correctamente el contenido. La importación actual reconoce el formato histórico `SENE-ADMIN-REPORT|1` mediante `SeneReportReader`, y aplica los datos a nómina/perfiles y al historial local antes de retirar el sobre del buzón.

Los reportes nuevos pueden usar `SENE-ADMIN-REPORT|2`: SeneAdmin genera una clave RSA privada en Android Keystore y registra la clave pública `rp` con el servidor. SENE VENTAS cifra con AES-256-GCM y RSA-OAEP SHA-256; SeneAdmin descifra localmente. El lector v1 sigue disponible para reportes y respaldos históricos. La distribución de `rp` depende de SERVER-SENE y, hasta que esté desplegada, se conserva compatibilidad v1.

## No asumir implementación del servidor

Los campos de respuesta `b` y `baa` documentan el contrato que SERVER-SENE deberá implementar. El cliente no puede recibir reportes hasta que el servidor implemente la persistencia, la entrega por negocio y el ACK. Esta especificación no afirma que esos endpoints estén desplegados. SERVER-SENE también debe registrar `rp` durante el canje de vinculación, actualizarlo de forma autenticada y entregarlo solo a instalaciones SENE VENTAS del negocio correspondiente.
