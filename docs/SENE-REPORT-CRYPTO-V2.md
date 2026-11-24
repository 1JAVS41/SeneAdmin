# Cifrado de reportes SENE — versión 2

SeneAdmin genera una clave RSA de 2048 bits dentro de Android Keystore. La clave privada no se exporta y la clave pública se registra en SERVER-SENE durante el canje de vinculación (`POST /admin/pairing/claim`) y en sincronizaciones posteriores mediante el campo compacto `rp`.

El servidor debe asociar la clave pública al negocio vinculado y entregarla únicamente a instalaciones SENE VENTAS autenticadas de ese negocio. SENE VENTAS cifra cada reporte con una clave AES-256 aleatoria y la envuelve con RSA-OAEP SHA-256 / MGF1 SHA-256. El cuerpo se cifra con AES-256-GCM y el encabezado se autentica como AAD.

SeneAdmin descifra únicamente en el dispositivo que conserva la clave privada Android Keystore. El servidor no puede descifrar reportes.

## Formato

- Encabezado: `SENE-ADMIN-REPORT|2`, `ID`, `WORKER`, `DATE`, `TIME`, `NUMBER`, `TYPE`, `ALG=RSA-OAEP-SHA256+A256GCM`.
- Campos cifrados: `WRAPPED_KEY`, `IV`, `DATA` (Base64 URL-safe).
- La clave AES se crea de nuevo por reporte; el IV es aleatorio y de 12 bytes.

## Compatibilidad

SeneAdmin conserva lectura de reportes v1 con la clave compartida histórica y acepta v2 sin esa clave. El historial local de reportes se cifra además en reposo con AES-GCM y una clave de instalación Android Keystore. La migración del historial anterior solo elimina su copia en preferencias después de verificar que el archivo cifrado se guardó correctamente.

## Pendiente de SERVER-SENE

SERVER-SENE debe registrar y distribuir `rp`, filtrar por negocio, soportar rotación/revinculación al cambiar de dispositivo y nunca sobrescribir una clave pública existente sin un proceso explícito de rotación. Hasta que se implemente, la entrega E2E v2 no puede activarse entre teléfonos. Los reportes v1 y los respaldos antiguos no deben borrarse ni modificarse.
