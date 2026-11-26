# Conexión MUSE de SENE GUARDIÁN (prueba)

Este procedimiento crea una instalación exclusiva para MUSE en el Worker de pruebas. No despliega producción ni modifica la APK.

## Preparación única requerida

En GitHub, abre **Settings → Secrets and variables → Actions → New repository secret** y agrega:

- Nombre: `MUSE_SETUP_PASSPHRASE`
- Valor: una frase aleatoria de al menos 20 caracteres, que solo tú conozcas. Guárdala en tu gestor de contraseñas. No la envíes por chat.

El flujo reutiliza `CLOUDFLARE_API_TOKEN` y `CLOUDFLARE_ACCOUNT_ID` ya configurados. No crea servicios de pago ni requiere tarjeta.

## Crear la conexión

1. Abre **Actions → SENE GUARDIAN - Crear conexión MUSE de prueba**.
2. Pulsa **Run workflow** y confirma la rama `feature/sene-guardian-muse-readonly`.
3. Espera a que termine correctamente.
4. Descarga el artefacto `sene-muse-connection-encrypted`. Caduca en 1 día.

El archivo contiene las credenciales de instalación, cifradas con AES-256-CBC, PBKDF2 y salt. Los tokens no se escriben en los logs. El paquete cifrado incluye también la credencial administrativa necesaria para revocar la instalación; mantenlo privado.

## Descifrar en tu equipo

En un equipo con OpenSSL, coloca el archivo descargado en una carpeta privada y ejecuta:

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in muse-connection.json.enc -out muse-connection.json
```

OpenSSL solicitará la frase `MUSE_SETUP_PASSPHRASE`. No la pongas como argumento del comando ni la compartas. Abre el JSON localmente: contiene `mcpUrl`, `authentication` y `museReadToken` para registrar el conector en MUSE mediante su Secure Credentials Store. También contiene `installationId`, `ingestToken` y `adminToken`; no los pegues en chats ni los publiques.

Después de guardar el Bearer en el almacén seguro de MUSE, elimina de forma segura las copias locales y el artefacto descargado. No compartas el archivo JSON descifrado.

## Alcance y estado

- Solo Worker de pruebas: `sene-guardian-test`.
- No cambia ni publica la APK.
- No conecta ni envía datos reales desde SeneAdmin; el exportador Android aún no está implementado.
- No habilita producción.
- La conexión MCP disponible expone únicamente `get_business_overview`, con datos de prueba hasta que exista ingestión real.
- La revocación requiere el `adminToken` cifrado dentro del paquete.
