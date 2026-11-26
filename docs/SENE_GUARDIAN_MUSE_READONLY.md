# SENE GUARDIAN — integración de solo lectura con Muse

## Estado
Diseño inicial en la rama `feature/sene-guardian-muse-readonly`, basada en Build #7 (`8e8ce19a6ed0c01438fbf3bf0a67e258ecc7d829`). El usuario autorizó preparar la alternativa gratuita Cloudflare Workers Free. Esta autorización no permite activar planes de pago, desplegar con cargos, modificar el servidor comercial ni conectar datos reales todavía.

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

## Plataforma de alojamiento elegida para preparar
**Cloudflare Workers Free** como primera opción, porque ejecuta solicitudes HTTPS en infraestructura administrada sin mantener una máquina virtual encendida. La preparación debe respetar exclusivamente el nivel gratuito.

Condiciones:
- No habilitar Workers Paid, planes pagos, servicios de pago ni facturación adicional.
- No desplegar hasta revisar los límites y la facturación visibles de la cuenta Cloudflare, y contar con una forma comprobable de evitar cargos.
- Diseñar sin procesos permanentes, cron, polling, WebSockets persistentes ni conexiones salientes continuas.
- El límite gratuito publicado de solicitudes de Workers es diario y la CPU por invocación está limitada; alcanzar una cuota puede provocar errores o rechazos hasta su restablecimiento. Por ello, el nivel gratuito no equivale a una garantía contractual de disponibilidad 24/7.
- No guardar datos sensibles en variables de entorno, logs, KV ni repositorio. Los secretos solo se configurarían como secretos del servicio después de revisar el diseño.
- Si se requiere persistencia, evaluar un almacén independiente y gratuito de Cloudflare, con límites, aislamiento, retención y costes revisados antes de seleccionarlo. Nunca usar ni enlazar la D1 de SERVER-SENE.
- La integración con Muse depende de que el producto/cuenta de Meta permita realmente un conector personalizado compatible. No asumir que una app de Muse acepta MCP o REST hasta verificarlo.

## Arquitectura prevista
1. **SeneAdmin (Android):** un exportador explícito de solo lectura recopila vistas permitidas desde los almacenes locales ya existentes. No inspecciona SharedPreferences ni archivos arbitrarios.
2. **API SENE GUARDIÁN independiente:** Worker HTTPS separado, autenticación propia y almacenamiento aislado si se aprueba después de evaluar cuotas y retención. Recibe snapshots autenticados de una instalación autorizada y mantiene solo el estado mínimo necesario.
3. **Muse:** conector de consulta que usa credenciales de acceso GUARDIÁN, solo si Meta confirma compatibilidad. Sus herramientas serán de consulta únicamente.
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
- Revisar los límites vigentes y la facturación de Cloudflare antes de desplegar; si no puede garantizarse el uso gratuito, detenerse.
- Definir autenticación y flujo de alta independiente de SENE-VENTAS.
- Probar el conector en Muse con datos ficticios y confirmar compatibilidad real antes de permitir datos reales.
- Revisar la política de privacidad y el consentimiento para los datos de trabajadores.

## Estado de seguridad
La rama es una propuesta de implementación, no una conexión activa. No hay Worker desplegado, credenciales de Cloudflare configuradas, APK modificada ni conexión con Muse. No se deben generar códigos, compartir secretos, instalar una APK ni conectar una cuenta Muse real hasta completar los requisitos y probar con datos ficticios.
