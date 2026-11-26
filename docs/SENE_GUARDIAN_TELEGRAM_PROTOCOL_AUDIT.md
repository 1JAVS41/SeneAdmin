# Auditoría del flujo Telegram y SERVER-SENE para SENE GUARDIÁN

Fecha: 26-11-2026  
Repositorios revisados: `1JAVS41/SENE-VENTAS` (rama `main`) y `1JAVS41/SeneAdmin` (rama `feature/sene-guardian-muse-readonly`).

## Hallazgo principal

El código disponible de SERVER-SENE no contiene un bot de Telegram con comandos, webhook, polling ni API administrativa de consulta. Lo que existe es una función del Worker que envía mensajes salientes a un chat de Telegram mediante los secretos `TELEGRAM_BOT_TOKEN` y `TELEGRAM_CHAT_ID`.

Por tanto, lo que el usuario llama “bot de Telegram” corresponde, en el código actualmente visible, a **notificaciones automáticas enviadas por SERVER-SENE a Telegram**, no a un servicio de bot que GUARDIÁN pueda consultar directamente.

## Flujo real observado en `server-sene/worker.js`

### Solicitudes de licencia nueva y renovación

La ruta pública `POST /requests` llama a `createRequest` y acepta `type` igual a `activation` o `renewal`, nombre del titular, WhatsApp, nombre del negocio e identificador de instalación.

El Worker:
1. valida los datos y las reglas de negocio/dispositivo;
2. crea el folio y un código aleatorio de 10 caracteres;
3. calcula SHA-256 del código y guarda el hash en `activation_codes` (no el código en claro);
4. crea la solicitud en `activation_requests` y un pago pendiente en `payments`;
5. envía a Telegram el folio, tipo, negocio, titular, WhatsApp, importe y el código en claro, con la advertencia de entregarlo solo después de verificar el pago;
6. marca la notificación como enviada o, si falla Telegram, cancela la solicitud e invalida el código.

Las solicitudes tienen vigencia inicial de 48 horas. La notificación Telegram es la única salida de código en claro identificada en el código revisado.

### Canje del código

La ruta `POST /redeem` valida folio, código e instalación. Comprueba estado, expiración, código consumido/invalidado/bloqueado, coincidencia de instalación y hasta cinco intentos. Si el código es válido, lo consume, completa la solicitud, marca el pago como verificado, activa/renueva el negocio y crea la suscripción por 30 días.

Esta ruta no es una herramienta administrativa para que GUARDIÁN recupere códigos: es el canje que ejecuta el cliente desde su aplicación. GUARDIÁN no debe invocarla para “reclamar” códigos.

### Rutas presentes en el Worker revisado

- `GET /health`
- `GET /config`
- `POST /requests`
- `POST /redeem`
- `POST /account/recovery/request`
- `POST /account/recovery/complete`
- `POST /subscription/status`

No se encontraron rutas de lectura administrativa de solicitudes, lista de solicitudes pendientes, consulta de código en claro, confirmación manual de pagos ni claim administrativo.

## Consecuencia para GUARDIÁN

No es posible reproducir fielmente la entrega del código solo consultando las tablas actuales: el código se conserva como hash y el mensaje de Telegram se envía en el instante de creación. No se debe intentar recuperar el código desde el hash, leer directamente la base D1 ni reutilizar secretos del bot.

La paridad completa exige una integración adicional y explícita en SERVER-SENE, o un canal de eventos equivalente que reciba el código en claro en el momento de creación. El cambio deberá implementarse primero en una rama de desarrollo y probarse con datos ficticios. No desplegarlo ni cambiar producción sin autorización específica.

## Diseño mínimo recomendado para una futura extensión

- Crear un canal GUARDIÁN autenticado y separado de los secretos de Telegram y de las credenciales de SeneAdmin.
- Enviar eventos de activación/renovación desde el punto donde se crea la solicitud, con folio, tipo, fecha, importe, negocio, estado y datos de contacto estrictamente necesarios.
- Para el código temporal, transmitirlo una sola vez por un canal cifrado de entrega al propietario, no persistirlo en claro ni en logs, y aplicar caducidad/acuse. El canal de notificación ordinario de Muse no debe recibirlo automáticamente si no se puede garantizar su confidencialidad.
- Mantener la regla de negocio: el código se entrega al cliente solo después de verificar el pago; GUARDIÁN debe presentar al propietario la advertencia y no marcar el pago como verificado por sí mismo.
- Los avisos de renovación deben distinguir “solicitud creada/pago pendiente” de “pago verificado/renovación completada”. No inferir un pago a partir de una solicitud.
- La operación de canje sigue siendo del cliente a través de `POST /redeem`, no una acción de GUARDIÁN.
- Toda acción que pueda modificar solicitudes, pagos, códigos, negocios o suscripciones requerirá un endpoint de servidor dedicado, alcance mínimo, confirmación explícita y auditoría.

## Límites de esta auditoría

- Se revisó el archivo `server-sene/worker.js` visible en `main` y el código Android de SeneAdmin señalado en la revisión previa.
- En el árbol visible del repositorio SENE-VENTAS no apareció un proyecto separado de bot; el Worker usa Telegram Bot API para enviar mensajes.
- No se accedió a Cloudflare, D1 de producción, secretos, chats de Telegram ni registros de clientes.
- No se realizaron llamadas a rutas comerciales ni se crearon solicitudes/códigos reales.
- No se modificó SENE-VENTAS, SERVER-SENE, D1 ni APK.
