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


## Primera revisión estática del código (26-11-2026)
Revisada la rama de trabajo, sin modificar archivos de la APK ni desplegar servicios.

### Componentes y datos localizados
- `InventoryLocalStore`: mantiene snapshots de `INVENTARIO_MAESTRO` y `INVENTARIO_SENE` en un archivo cifrado AES-GCM con clave Android Keystore. Es el punto de lectura preferente para un resumen de inventario; no se debe leer directamente el archivo cifrado desde el exportador.
- `SharedInventoryManager`: gestiona la carpeta compartida, inventarios y reportes. Sus operaciones de carpeta permiten lectura y escritura, por lo que GUARDIÁN no debe invocar métodos genéricos de guardado, limpieza ni sincronización. La lectura de reportes archivados depende de claves del propietario y descifradores existentes; no exportar archivos crudos.
- `OwnerPayrollStore`: contiene perfiles, movimientos, nóminas, solicitudes y asistencia. Los modelos incluyen nombre, teléfono, salario, importes y eventos personales. La persistencia usa preferencias compartidas y JSON; el exportador deberá llamar únicamente a getters de lectura y construir DTO minimizados. No exponer teléfonos ni identificadores de trabajador por defecto.
- `SeneAdminStore`: historial de reportes cifrado con AES-GCM y Android Keystore. Su método `reports()` devuelve cuerpos completos de reportes; no exponer cuerpos completos a Muse sin un filtro y una autorización específica.
- `ReportInboxStore`: mantiene eventos y acuses cifrados. No debe reutilizarse para GUARDIÁN porque es parte del flujo de sincronización administrativa.
- `ServerPairing` y `AdminPayrollRequestSync`: contienen el token administrativo y ejecutan flujos comerciales/de sincronización con el servidor. GUARDIÁN queda expresamente separado; no llamar ni reutilizar estas clases, credenciales, endpoints o colas.
- `AndroidManifest.xml`: declara INTERNET; no existe aún un componente/exportador GUARDIÁN ni un flujo de consentimiento/revocación para esta integración.
- `MainActivity`: programa sincronización administrativa existente y controla el PIN/huella. GUARDIÁN no debe interceptar ni alterar ese flujo.

### Consecuencias para el diseño
1. La primera versión de GUARDIÁN no enviará nóminas nominales, teléfonos, reportes completos ni cuerpos sin filtrar. Empezará con resúmenes agregados y campos estrictamente necesarios.
2. Se necesita un módulo Android separado con interfaz de lectura explícita, DTO allowlist y pruebas; no se debe dar acceso genérico a preferencias, archivos o clases de sincronización.
3. La sincronización hacia Cloudflare deberá ser una operación HTTPS saliente autenticada, limitada, reintentable y disparada por el dispositivo bajo una política clara. No crear un servicio Android permanente ni polling.
4. El Worker deberá exponer endpoints de consulta separados de la ruta de recepción de snapshots; validar tamaño, esquema, fecha, instalación autorizada, cuota y revocación antes de almacenar.
5. La identidad de negocio/instalación debe generarse para GUARDIÁN y no derivarse de códigos de vinculación, tokens, licencias ni claves comerciales.
6. Antes de habilitar nómina/asistencia individual, se debe definir consentimiento y base de acceso; la primera fase puede limitarse a métricas agregadas.
7. Esta revisión es estática y parcial: todavía falta recorrer todas las funciones y formatos de reportes, validar las reglas de cálculo de nómina/asistencia, revisar pruebas y compilar. No equivale a una auditoría completa.

### Próximas tareas seguras
- Completar el inventario de getters y formatos de reportes sin ejecutar operaciones de escritura.
- Diseñar DTO allowlist y pruebas unitarias con datos ficticios.
- Preparar el Worker localmente en la rama de trabajo, sin secretos, sin despliegue y sin enlazar almacenamiento de pago.
- Verificar por separado los límites de la cuenta Cloudflare y la compatibilidad documentada de conectores de Muse antes de cualquier conexión real.


## Segunda revisión estática: almacenes y flujo Android (26-11-2026)

### Inventario cifrado
- `InventoryLocalStore.snapshot(context, category)` es el getter más directo para obtener una fotografía local; solo acepta `INVENTARIO_MAESTRO` e `INVENTARIO_SENE`.
- La fotografía contiene código, nombre, precio, existencias, pasillo y estante. Para Muse, el DTO inicial debe omitir códigos internos y ubicación detallada salvo necesidad funcional; agregar conteos, existencias bajas y agotados.
- `InventoryLocalStore.snapshot` descifra internamente con Android Keystore y no modifica el almacén. El exportador debe usarlo, nunca abrir el archivo cifrado ni llamar a `save` o `cleanupIfDue`.

### Reportes
- `SeneAdminStore.reports(context)` descifra el historial y devuelve `SeneReport` con `workerId`, fecha, hora, número, tipo y cuerpo. La lectura puede ejecutar limpieza de retención internamente; por ello no es un getter estrictamente libre de efectos secundarios y no debe usarse desde GUARDIÁN sin separar primero una lectura pura.
- `SeneReportReader` requiere la clave del propietario para formato v1; `SeneReportV2Reader` usa la clave privada RSA local para formato v2. GUARDIÁN nunca recibirá ni exportará esas claves.
- Los cuerpos de reporte son texto estructurado y pueden contener datos personales/financieros. No se enviarán cuerpos completos a la API; primero se deben definir parsers por tipo y DTOs agregados, con pruebas sobre muestras ficticias.
- `SharedInventoryManager.archivedReports` depende de una clave del propietario y puede leer carpetas compartidas. No se usará como mecanismo automático de exportación de GUARDIÁN.

### Nómina y asistencia
- `OwnerPayrollStore` expone getters para perfiles, movimientos, solicitudes, nóminas, entradas y resúmenes de asistencia. Los modelos mezclan importes, nombres, teléfonos, IDs internos y estados operativos.
- Antes de exponer datos se debe especificar qué campos agregados se necesitan por función, evitar nombres/teléfonos/IDs, y verificar cálculos y periodos con pruebas. La primera fase no debe transmitir nómina individual ni historial individual.
- No llamar a `createOrGetPayroll`, `syncUnrecordedAbsences`, `markPaid`, `addEntry`, `upsertProfile`, `queueRequestDecision`, `applyServerDecision` ni otros métodos que creen, modifiquen o confirmen datos.

### Ciclo de vida Android
- `MainActivity.onCreate` programa sincronización administrativa existente y ejecuta limpieza de almacenes en segundo plano; `onResume` puede iniciar `AdminPayrollRequestSync` cuando la instalación está vinculada.
- No se añadirá una tarea permanente, servicio foreground, polling ni un segundo scheduler. La futura sincronización GUARDIÁN deberá ser explícita, con intervalo moderado y límites, y no bloquear ni alterar el flujo actual.
- El manifiesto ya declara permiso INTERNET; no existe actualmente una pantalla de consentimiento, control de alcance ni revocación GUARDIÁN.

### Dependencias y pruebas
- El proyecto usa Android Gradle Plugin 8.7.3, Kotlin 2.0.21, SDK 35, Java 17 y WorkManager 2.10.1. No se encontró configuración de dependencias de pruebas en el archivo de módulo revisado.
- Antes de tocar APK se deben añadir pruebas unitarias/instrumentadas para parsers, agregaciones, autorización, revocación, límites de payload y aislamiento entre instalaciones.
- La revisión sigue siendo estática y no incluye compilación ni ejecución de pruebas. No se ha modificado código Kotlin ni AndroidManifest.

### Decisión técnica provisional
La vía más segura para la primera entrega es una fase inicial de **inventario agregado y estado del negocio**, sin datos nominales de empleados ni contenido completo de reportes. Ventas, nómina y asistencia se incorporarán solo después de crear parsers de reportes verificados y DTOs minimizados. La API remota no debe aceptar comandos ni ofrecer endpoints de escritura sobre datos comerciales.


## Primera base de API independiente creada (26-11-2026)
Se añadió un scaffold de Cloudflare Worker en `guardian-worker/` dentro de esta rama. Es código fuente de preparación, no está desplegado ni conectado a datos reales.

Archivos:
- `guardian-worker/src/index.ts`: endpoints iniciales de salud, recepción de snapshots agregados y consulta de resumen de negocio.
- `guardian-worker/wrangler.toml`: configuración con namespace KV pendiente de crear.
- `guardian-worker/README.md`: configuración pendiente, límites de seguridad y pasos de validación.

### Allowlist inicial
El esquema solo acepta:
- Identificador aleatorio e independiente de instalación.
- Fecha de captura y estado general.
- Conteos agregados por categoría de inventario: artículos, unidades, bajo stock y agotados.

No acepta empleados, teléfonos, nómina, asistencia individual, cuerpos de reportes, códigos de producto, precios, claves, licencias ni códigos de vinculación SENE-VENTAS.

### Controles implementados en el scaffold
- Autenticación Bearer separada para ingestión y consulta de Muse.
- Límite de cuerpo de 32 KB.
- Validación estricta de esquema y fechas.
- Rechazo de snapshots más antiguos.
- Retención KV de 35 días y reemplazo de la última fotografía.
- API de consulta GET; no existe endpoint de escritura comercial.

### Bloqueos antes de pruebas/despliegue
Este scaffold aún NO es apto para producción:
1. Falta un registro de instalaciones autorizadas y revocación por instalación; el identificador no debe bastar por sí solo para autorizar acceso.
2. Falta rate limiting por instalación y controles de abuso.
3. Falta prueba automatizada de autenticación, aislamiento, datos malformados, tamaño, orden temporal y revocación.
4. Falta probar localmente con Wrangler y revisar compatibilidad TypeScript/runtime.
5. Falta implementar el exportador Android con consentimiento y DTO allowlist; no se ha modificado la APK.
6. Falta confirmar si Muse admite este mecanismo/API o exige MCP/otro protocolo; el Worker actual no es un servidor MCP.
7. Falta crear namespace KV y configurar secretos en Cloudflare. No se han creado, no se han compartido secretos y no se ha desplegado nada.

No desplegar hasta cerrar estos bloqueos y verificar que el uso se mantiene dentro del plan gratuito de Cloudflare.


## Tercera revisión estática: almacenes y límites de lectura (26-11-2026)

### Inventario compartido
- `InventoryLocalStore.snapshot(context, category)` lee el archivo cifrado AES-GCM local y construye un snapshot. Es el punto de lectura más acotado, pero devuelve filas completas (código, nombre, precio, existencia, pasillo y estante); GUARDIÁN debe transformarlo inmediatamente a conteos agregados y descartar las filas.
- `SharedInventoryManager.list(...)` NO es un getter puro: puede migrar el respaldo más reciente a `InventoryLocalStore.save`. También lee archivos SAF, descifra inventarios y requiere la clave del propietario. No debe ser invocado por el exportador automático.
- `listAvailable(...)` puede delegar a `list` y hereda ese efecto secundario.
- `save`, `cleanupIfDue`, `cleanupAllIfDue`, `applySyncedInventoryEvent`, `applyApprovedConsumption` y funciones de reportes son explícitamente fuera de los límites de GUARDIÁN.
- La lectura inicial debe limitarse a `InventoryLocalStore.snapshot`, con un DTO nuevo que solo contenga categoría, fecha, número de artículos, unidades totales, conteo de bajo stock y agotados. La regla exacta de “bajo stock” debe definirse a partir de una regla de negocio existente; no inventar umbrales.

### Nómina y asistencia
- `OwnerPayrollStore.profiles`, `attendanceSummary`, `weekAttendance`, `entries`, `payrolls`, `requests` y `approvedMovements` exponen datos nominales, salario, importes, fechas, motivos, descripciones, identificadores de trabajador y, en algunos modelos, códigos de producto/cantidades.
- `weekAttendance` reconstruye días y turnos con fechas y horas; `attendanceSummary` incluye motivos y horas de entrada/salida. No transmitir esos modelos directamente.
- `createOrGetPayroll`, `markPaid`, `addEntry`, `upsertProfile`, `deleteProfile`, `applySyncedAttendance`, `applyServerDecision`, `queueRequestDecision`, `upsertServerRequest` y demás métodos de importación/aplicación son operaciones de escritura y quedan prohibidos para GUARDIÁN.
- Las vistas futuras de nómina/asistencia requerirán DTOs agregados separados, consentimiento del patrón y definición explícita de qué métricas puede consultar Muse. No enviar nombres, teléfonos, descripciones libres, motivos ni IDs de personas en la primera fase.

### Historial y reportes
- `SeneAdminStore.reports(context)` llama a `readArray`, que puede migrar el formato legado y ejecutar limpieza de retención. No es lectura estrictamente pura.
- `SharedInventoryManager.archivedReports` lee archivos compartidos y descifra contenido con claves; el cuerpo de los reportes puede incluir datos personales, inventario y nómina.
- `ReportInboxStore` forma parte del canal de eventos/acuse. No reutilizarlo como API de GUARDIÁN.
- No exponer reportes crudos, archivos cifrados, claves de descifrado ni el almacén de acuses. Una futura búsqueda de reportes debe procesar localmente una lista permitida de campos resumidos y sin identificadores personales.

### Riesgo pendiente detectado en el Worker scaffold
La primera versión del Worker usa dos secretos globales (`INGEST_TOKEN` y `MUSE_READ_TOKEN`) y acepta un `installationId` suministrado en la consulta. Aunque los secretos no están publicados, este esquema no proporciona autorización por instalación: quien tenga el token de lectura y conozca otro identificador podría consultar esa instalación. El token de ingestión global también permitiría suplantar snapshots de cualquier instalación.

**Bloqueo obligatorio:** sustituir antes de cualquier despliegue los tokens globales por credenciales independientes por instalación, registro de instalaciones autorizadas, revocación inmediata y aislamiento comprobado. La provisión debe requerir un secreto administrativo separado, no reutilizar tokens de SeneAdmin ni credenciales comerciales. Los secretos de cada instalación se almacenarán solo como hashes cuando sea viable y se mostrarán una sola vez al provisionar. Añadir rate limiting y auditoría de accesos. Hasta implementar y probar este control, el Worker scaffold es solo demostrativo y no debe recibir datos reales.

### Decisión de alcance
Se mantiene la primera fase en **estado del negocio + inventario agregado**, sin nómina nominal, asistencia individual ni reportes. No se añadieron cambios a Kotlin, AndroidManifest, base de datos, APK, servidor comercial o Railway. Esta revisión es estática; no equivale a compilación ni pruebas automatizadas.
