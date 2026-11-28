# Exportador Android de SENE GUARDIÁN

Estado: exportador apuntando a PRODUCCIÓN en la rama `feature/guardian-exporter-production` (PR en borrador). No se ha publicado ni instalado una APK nueva todavía.

## Qué está implementado

- `GuardianSnapshotExporter`: lectura de snapshots locales de `INVENTARIO_MAESTRO` y `INVENTARIO_SENE`, reducción a conteos agregados y envío HTTPS al Worker de PRODUCCIÓN (`https://sene-guardian-api.nagavs28.workers.dev/v1/snapshot`). El Worker de pruebas ya no recibe capturas de esta app.
- `GuardianAutoSyncWorker` / `GuardianAutoSyncScheduler`: envío automático cada 2 horas con WorkManager, con el mismo patrón que ya usaba `SeneAdminAutoSyncWorker` (trabajo único sin duplicados, solo con red, reintento exponencial de 15 minutos). Se programa al abrir SeneAdmin (`MainActivity`) y al guardar el consentimiento. No se inventó ningún permiso nuevo ni pantalla nueva.
- `GuardianConfigStore`: cifra instalación, token de ingestión y consentimiento con AES-GCM; la clave AES no exportable se genera y conserva en Android Keystore.
- `GuardianSettingsActivity`: pantalla privada para registrar la instalación, guardar el token, otorgar o retirar consentimiento, enviar una captura manual y borrar la configuración local. Al retirar el consentimiento o desactivar, el envío automático se cancela.
- Acceso desde la opción SENE GUARDIÁN en el menú principal. La pantalla está registrada como Activity no exportada y solo se abre desde SeneAdmin después del acceso habitual del propietario.
- El envío automático nunca se hace sin consentimiento: el Worker vuelve a leer la configuración en cada ejecución y si no hay consentimiento guardado termina sin enviar nada.

## Configuración manual

En el archivo de conexión descifrado, copiar únicamente:
- `installationId`
- `ingestToken`

No introducir `adminToken` ni `museReadToken` en SeneAdmin. El token se introduce en el campo protegido y se guarda cifrado. El campo se limpia después de guardar.

Importante: la instalación de producción necesita su propio `installationId` e `ingestToken` de producción. Los de pruebas no sirven en producción; si el teléfono todavía tiene guardada la configuración de pruebas, hay que borrarla con "DESACTIVAR Y BORRAR CREDENCIALES" y registrar la de producción.

## Datos transmitidos

Por cada inventario local disponible: categoría, cantidad de artículos, suma de existencias no negativas, cantidad agotada y fecha de captura. No se transmiten códigos, nombres, precios, pasillos ni estantes.

Trabajadores de esta instalación: nombre y sueldo semanal (`salaryCents`, en centavos por semana, leído de `weeklySalaryCents` del perfil). Solo para el MUSE de esta instalación; nunca se comparte con otra instalación ni con el MUSE propietario. No se envían teléfono, PIN, credenciales ni identificadores.

También se envían solo totales: nómina por periodo, ventas mensuales (totales netos), conteo de productos dañados y conteos de movimientos aprobados por mes. El estado del negocio se transmite como `unknown` y el indicador de bajo stock como `null`, hasta verificar las fuentes y reglas reales. No se inventan valores.

## Límites

- Solo lectura: no modifica almacenes locales ni datos del negocio.
- No lee PIN, licencias, códigos comerciales ni claves privadas.
- No usa `ServerPairing`, `AdminPayrollRequestSync` ni el token de MUSE.
- HTTPS fijo del Worker de producción; no sigue redirecciones.
- "Desactivar y borrar credenciales" elimina la configuración local y cancela el envío automático; no revoca la instalación en el servidor. La revocación administrativa sigue siendo un procedimiento separado.

## Pendiente antes de datos reales

1. Que la compilación de Kotlin en CI esté verde en esta rama (workflow `SENE GUARDIAN Android Kotlin compile check`; solo compila Kotlin, no ensambla APK).
2. Construir la APK desde esta rama con el workflow `Compilar APK SENE ADMIN (pruebas)` (`.github/workflows/build-apk.yml`) mediante `workflow_dispatch` sobre la rama `feature/guardian-exporter-production`. Ese workflow solo produce una APK instalable si los secretos de firma permanente (`SENE_KEYSTORE_BASE64`, `SENE_STORE_PASSWORD`, `SENE_KEY_ALIAS`, `SENE_KEY_PASSWORD`) están configurados; sin ellos la APK es solo de depuración y NO debe instalarse.
3. Instalar la APK firmada en el teléfono del propietario, registrar la configuración de PRODUCCIÓN (installationId e ingestToken de producción) en SENE GUARDIÁN, marcar el consentimiento y comprobar que la captura llega al Worker de producción (luego `get_business_overview` del MUSE cliente debe dejar de decir que no hay captura disponible).
4. Verificar los límites gratuitos y retención de Cloudflare antes de un uso sostenido.
