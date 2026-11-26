# Exportador Android de SENE GUARDIÁN

Estado: código fuente preparado en la rama de pruebas. No se ha publicado ni instalado una APK.

## Qué está implementado

- `GuardianSnapshotExporter`: lectura de snapshots locales de `INVENTARIO_MAESTRO` y `INVENTARIO_SENE`, reducción a conteos agregados y envío HTTPS manual al Worker de pruebas.
- `GuardianConfigStore`: cifra instalación, token de ingestión y consentimiento con AES-GCM; la clave AES no exportable se genera y conserva en Android Keystore.
- `GuardianSettingsActivity`: pantalla privada para registrar la instalación, guardar el token, otorgar o retirar consentimiento, enviar una captura manual y borrar la configuración local.
- Acceso desde la opción SENE GUARDIÁN en el menú principal. La pantalla está registrada como Activity no exportada y solo se abre desde SeneAdmin después del acceso habitual del propietario.
- El envío no se programa automáticamente. Cada captura requiere consentimiento guardado y pulsar "ENVIAR CAPTURA AHORA".

## Configuración manual

En el archivo de conexión descifrado, copiar únicamente:
- `installationId`
- `ingestToken`

No introducir `adminToken` ni `museReadToken` en SeneAdmin. El token se introduce en el campo protegido y se guarda cifrado. El campo se limpia después de guardar.

## Datos transmitidos

Por cada inventario local disponible: categoría, cantidad de artículos, suma de existencias no negativas, cantidad agotada y fecha de captura. No se transmiten códigos, nombres, precios, pasillos ni estantes.

El estado del negocio se transmite como `unknown` y el indicador de bajo stock como `null`, hasta verificar las fuentes y reglas reales. No se inventan valores.

## Límites

- Solo lectura: no modifica almacenes locales ni datos del negocio.
- No lee PIN, licencias, códigos comerciales, nómina, asistencia, reportes ni claves privadas.
- No usa `ServerPairing`, `AdminPayrollRequestSync` ni el token de MUSE.
- HTTPS fijo de Worker de pruebas; no sigue redirecciones.
- Sin sincronización automática, scheduler ni servicio persistente.
- "Desactivar y borrar credenciales" elimina la configuración local; no revoca la instalación en el servidor. La revocación administrativa sigue siendo un procedimiento separado.

## Pendiente antes de datos reales

1. Compilar la fuente Android y corregir cualquier error detectado.
2. Probar la pantalla, el cifrado/descifrado y el envío con una instalación ficticia y datos inventados.
3. Verificar la identidad y correspondencia entre installationId y token de ingestión.
4. Confirmar el alcance de los datos y el comportamiento de consentimiento con el propietario.
5. Verificar los límites gratuitos y retención de Cloudflare antes de un uso sostenido.

Esta fase no genera APK, no cambia producción y no despliega Worker. La prueba de compilación de CI solo compila Kotlin; no ensambla APK.
