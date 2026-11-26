# SENE GUARDIAN — integración de solo lectura con Muse

## Estado
Diseño inicial. Esta rama parte de la compilación Build #7
(`8e8ce19a6ed0c01438fbf3bf0a67e258ecc7d829`). No habilita conexiones ni cambia la APK publicada.

## Objetivo
Exponer a Muse, mediante una API HTTPS independiente, vistas de solo lectura de los datos del negocio que SeneAdmin ya tiene autorizados y sincronizados. La APK enviará únicamente los datos seleccionados al servicio GUARDIÁN; Muse consultará ese servicio. No se entrega a Muse el token administrativo de SeneAdmin.

## Límites obligatorios
- No llamar a `/admin/pairing/claim` ni a `/admin/payroll/requests/sync` desde GUARDIÁN.
- No leer, generar, reclamar, almacenar ni transmitir códigos de vinculación producidos por SENE-VENTAS.
- No reutilizar `admin_token`, claves privadas de reportes ni credenciales de SERVER-SENE.
- No conectar GUARDIÁN con SERVER-SENE, su Worker ni su base D1.
- El canal GUARDIÁN no tendrá herramientas de escritura sobre SeneAdmin ni sobre el negocio.
- Separación por negocio e instalación, autenticación revocable, caducidad, límites de tasa y registro de auditoría.
- No transmitir secretos, PIN, huellas, claves privadas ni identificadores de licencia.
- Los datos personales de trabajadores deben minimizarse; los datos detallados de nómina/asistencia solo se expondrán cuando sean necesarios para una consulta autorizada.

## Arquitectura prevista
1. **SeneAdmin (Android):** un exportador explícito de solo lectura recopila vistas permitidas desde los almacenes locales ya existentes. No inspecciona SharedPreferences ni archivos arbitrarios.
2. **Servicio SENE GUARDIÁN independiente:** API HTTPS propia, almacenamiento aislado del servidor comercial y de D1. Recibe snapshots firmados/autenticados de una instalación autorizada y mantiene el último estado necesario.
3. **Muse:** conector personalizado de consulta que usa credenciales de acceso GUARDIÁN. Sus herramientas serán GET/consulta únicamente.
4. **Revocación:** el dueño puede deshabilitar la instalación y revocar el acceso sin afectar la licencia o vinculación comercial de SeneAdmin.

## Herramientas de consulta previstas para Muse
- `business_overview`: indicadores generales y alertas.
- `inventory_summary`: existencias, agotados, mínimos y cambios.
- `sales_summary`: totales y tendencias presentes en reportes importados.
- `payroll_summary`: totales, periodos y anomalías; sin datos personales innecesarios.
- `attendance_summary`: incidencias y registros incompletos.
- `reports_search`: buscar/consultar reportes que SeneAdmin pueda descifrar y que el dueño haya autorizado compartir.
- `guardian_status`: última sincronización, alcance autorizado y estado de revocación.

No se crearán herramientas para editar, borrar, confirmar nóminas, sincronizar decisiones administrativas, cambiar licencias o vincular instalaciones.

## Requisitos antes de activar
- Confirmar el inventario real de modelos y almacenes de SeneAdmin mediante revisión completa del código.
- Implementar pruebas unitarias para el filtrado de datos y pruebas de aislamiento entre negocios.
- Elegir y configurar un alojamiento independiente con HTTPS y almacenamiento aislado. No hay credenciales de despliegue disponibles en este repositorio.
- Definir autenticación y flujo de alta independiente de SENE-VENTAS.
- Probar el conector en Muse con datos ficticios antes de permitir datos reales.
- Revisar la política de privacidad y el consentimiento para los datos de trabajadores.

## Estado de seguridad
Hasta que se completen esos requisitos, no se debe generar ningún código, compartir secretos, instalar una APK, ni conectar una cuenta Muse real. La rama es una propuesta de implementación, no una conexión activa.
