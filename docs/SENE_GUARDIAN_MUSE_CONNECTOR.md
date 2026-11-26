# SENE GUARDIÁN — conector de solo lectura para Meta Muse

## Estado
El Worker aislado `sene-guardian-test` ya está desplegado en Cloudflare y su endpoint `/v1/health` respondió `online` durante la comprobación manual. Esto valida el alojamiento, no una conexión real con Meta Muse. Aún no se ha creado una instalación de prueba ni emitido un `museReadToken`, porque las variables administrativas y las claves de entrega no están configuradas.

## Transporte
Endpoint MCP remoto, HTTP Streamable (modo stateless, respuestas JSON), con autenticación Bearer por instalación:

`POST https://sene-guardian-test.nagavs28.workers.dev/v1/muse/<installationId>/mcp`

El token es el `museReadToken` emitido una sola vez por el aprovisionamiento administrativo. Solo debe guardarse en el almacén seguro de credenciales del conector de Muse; nunca en el repositorio, URL, APK, logs o prompts.

La ruta acepta solicitudes servidor-a-servidor sin encabezado `Origin`. Si una solicitud incluye `Origin` (por ejemplo, desde un navegador), se rechaza salvo que el origen exacto esté en la variable opcional `MUSE_ALLOWED_ORIGINS`, separada por comas. No se debe abrir con comodines; agrega únicamente orígenes oficiales confirmados del cliente que se vaya a probar.

## Herramientas expuestas
- `get_business_overview`: solo lectura; devuelve estado del negocio, agregados de inventario y fecha de captura.
- No existen herramientas de escritura, pagos, nómina detallada, licencias, códigos de activación/renovación ni eventos del propietario central en este conector.

El ID de instalación queda fijado en la ruta y el token se valida contra el hash propio de esa instalación. No se acepta un ID alternativo en los argumentos de la herramienta. Revocar la instalación invalida inmediatamente el token.

## Configuración de Muse
En una cuenta/país donde Muse permita conectores personalizados, pedirle que cree un conector remoto con:
- Nombre: SENE GUARDIÁN
- URL: endpoint MCP de la instalación
- Autenticación: Bearer token
- Secreto: museReadToken de esa instalación, ingresado en el almacén seguro de credenciales de Muse.

La disponibilidad de Muse y el método exacto de autenticación/transportes admitidos deben confirmarse en la cuenta real antes de considerarlo operativo. Meta indica que los conectores personalizados se configuran desde Muse y que sus credenciales se guardan en Secure Credentials Store. Meta advierte que no revisa esos conectores: conceder solo el alcance mínimo.

## Límites de costo y despliegue
El código no llama a una API de modelos ni a WhatsApp. El conector por sí solo no genera cargos de IA; el alojamiento está sujeto a límites y condiciones del plan gratuito de Cloudflare. La URL anterior es exclusivamente de pruebas. No usar datos reales, no configurar producción y no crear recursos de pago. La creación de instalaciones y emisión de tokens requiere primero preparar secretos administrativos de prueba de forma segura.
