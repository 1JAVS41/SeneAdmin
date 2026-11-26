# SENE GUARDIÁN — alcance ampliado: administración SeneAdmin y operaciones del bot

Fecha: 26-11-2026  
Rama: `feature/sene-guardian-muse-readonly`

## Decisión funcional aprobada por el propietario

SENE GUARDIÁN tendrá dos dominios de trabajo separados:

1. **Administración integral de SeneAdmin:** inventario, ventas, reportes, trabajadores, asistencia, nómina y demás áreas autorizadas del negocio.
2. **Operaciones comerciales equivalentes al bot de Telegram:** informar al propietario de nuevas solicitudes de licencia y de solicitudes de pago/renovación mensual; presentar los datos y códigos correspondientes; permitir consultar el estado; y reproducir el flujo operativo que el bot realiza para la vinculación, dentro de los permisos exactos del bot.

Este alcance amplía la decisión anterior de GUARDIÁN exclusivamente de lectura para el dominio comercial, pero no concede permisos generales sobre SENE-VENTAS. La ampliación queda limitada a las operaciones expresamente descritas aquí.

## Avisos que debe entregar GUARDIÁN

- **Nueva licencia:** detectar una solicitud nueva, avisar al propietario y mostrar los datos de solicitud y el código que corresponda al flujo oficial.
- **Pago o renovación mensual:** detectar la solicitud de pago de mensualidad, avisar al propietario y mostrar sus datos y estado.
- **Cambios de estado:** notificar cambios relevantes (por ejemplo, recibida, pendiente, aprobada, rechazada, vencida o completada) solo cuando esos estados existan en el protocolo real del bot/servidor.
- **Consulta bajo demanda:** responder consultas del propietario sobre solicitudes, códigos y estado.

Los avisos deben ser deduplicados, ordenados por fecha y conservar una referencia de evento para no repetir notificaciones tras reintentos.

## Paridad con el bot: requisito antes de implementar acciones

No se debe adivinar el comportamiento del bot ni inventar endpoints, campos o transiciones. Antes de implementar el adaptador se debe revisar el código fuente real del bot y el protocolo vigente del servidor para documentar:

- cómo descubre solicitudes nuevas y renovaciones;
- qué campos devuelve para cada tipo de solicitud;
- cómo se obtiene y entrega un código temporal;
- qué validaciones y confirmaciones realiza el bot;
- si existe un paso de reclamación/claim y en qué momento lo ejecuta;
- cómo informa errores, expiración, duplicados y cambios de estado.

GUARDIÁN debe reproducir ese flujo, no crear un flujo paralelo ni saltarse validaciones. Una acción que en el bot requiera confirmación explícita debe requerirla también en GUARDIÁN. No se aprobarán ni rechazarán licencias o pagos automáticamente.

## Separación y límites de seguridad

- El canal de SENE GUARDIÁN debe seguir siendo independiente del canal de administración de SeneAdmin.
- No reutilizar el `admin_token` de SeneAdmin, tokens de instalación, PIN, huella, claves privadas de reportes ni secretos de SERVER-SENE.
- No almacenar códigos temporales más tiempo del estrictamente necesario para completar el mismo flujo del bot; nunca incluirlos en logs, analítica ni snapshots generales de Muse.
- Los datos de solicitud solo serán visibles al propietario autenticado y se minimizarán al propósito operativo.
- Las operaciones comerciales deberán usar una identidad de servicio propia, de alcance mínimo, revocable y auditada. No se expondrán credenciales al modelo ni a la APK.
- La paridad con el bot no autoriza acceso a toda la base D1, consultas SQL arbitrarias ni administración general de clientes/licencias.
- No modificar el repositorio SENE-VENTAS, SERVER-SENE, Worker comercial ni D1 comercial como parte de este cambio.
- No desplegar servicios, crear recursos facturables ni contratar planes de pago. Se mantiene la preparación para Cloudflare Workers Free únicamente.

## Plan de implementación seguro

### Fase A — verificación del protocolo (bloqueante)
1. Localizar y revisar el código fuente del bot de Telegram y sus dependencias.
2. Identificar los endpoints y transiciones que el bot utiliza, sin ejecutarlos contra producción.
3. Crear una especificación de contrato con ejemplos ficticios y matriz de permisos.
4. Confirmar que el repositorio/código fuente revisado corresponde al bot actualmente operativo.

### Fase B — adaptador aislado en SeneAdmin
1. Diseñar un módulo separado del Worker de snapshots de negocio.
2. Definir endpoints GUARDIÁN específicos para eventos/consultas y, solo si el protocolo del bot lo requiere, acciones explícitas de paridad.
3. Añadir autenticación de propietario, scopes, confirmación para acciones sensibles, deduplicación, expiración y auditoría sin secretos.
4. Mantener los DTO comerciales fuera del snapshot general de negocio y de las herramientas de resumen de Muse.
5. Añadir pruebas con solicitudes ficticias, reintentos, duplicados, códigos expirados, revocación, permisos y fallos del servidor.

### Fase C — integración y validación
1. Integrar notificaciones en la experiencia de SENE GUARDIÁN.
2. Verificar las herramientas reales disponibles en Muse antes de afirmar que está conectado.
3. Probar primero en entorno simulado/staging sin tocar licencias ni pagos reales.
4. Revisar límites gratuitos y facturación antes de cualquier despliegue.

## Estado real al registrar esta decisión

- La rama contiene un Worker scaffold REST de snapshots de inventario agregado; no es todavía un servidor MCP ni contiene el adaptador del bot.
- El Worker no está desplegado y Muse no está conectado.
- No se ha confirmado el código fuente real del bot ni su protocolo completo en esta revisión.
- No se ha modificado la APK, SERVER-SENE, Worker comercial ni D1 comercial.
- No se ha ejecutado ningún claim, creación de licencia, cobro, aprobación ni renovación.
- Por tanto, esta especificación registra el alcance aprobado; no afirma que las funciones estén ya implementadas u operativas.

## Regla obligatoria: entrega exclusiva al propietario y decisión manual

La información comercial que Muse reciba de SERVER-SENE se entrega primero y únicamente al propietario autenticado de SeneAdmin. Esta regla se aplica tanto a licencias nuevas como a renovaciones mensuales.

- GUARDIÁN debe avisar al propietario cuando llegue una solicitud y presentar los datos operativos y el código temporal que el servidor haya emitido para esa solicitud.
- El código se muestra en una vista privada del propietario; no se incorpora a respuestas públicas, mensajes dirigidos al cliente, notificaciones del cliente ni conversaciones compartidas.
- Muse y GUARDIÁN no envían, reenvían, copian ni revelan el código al cliente por iniciativa propia.
- El propietario decide fuera de cualquier automatismo cuándo y por qué medio comparte el código con el cliente. La decisión de compartir no equivale a verificar un pago ni cambia el estado comercial.
- Antes de mostrar un código, GUARDIÁN debe indicar el tipo de solicitud, el folio, el importe/estado de pago disponible y la advertencia de que solo debe compartirse después de verificar el pago, conforme a las reglas actuales de SERVER-SENE.
- La solicitud y el código deben estar vinculados al mismo folio. No se debe mostrar un código si la correlación no puede verificarse.
- Los códigos son secretos de corta duración: no se guardan en snapshots generales, historial de analítica, logs, trazas, informes ni almacenamiento persistente en claro. La entrega privada debe usar un canal cifrado y de vida limitada.
- Las acciones de aprobación, verificación de pago, activación, renovación, canje o cambio de estado siguen fuera de este alcance salvo que el propietario las autorice expresamente y exista un endpoint oficial, específico y auditado. El código que se presenta al propietario no se reclama ni se canjea automáticamente.

### Comportamiento esperado de la interfaz de Muse

1. Aviso privado: «Hay una solicitud de licencia nueva/renovación pendiente».
2. Vista privada: folio, tipo, fecha, negocio, titular, datos de contacto estrictamente necesarios, importe, estado del pago y código si está disponible.
3. Advertencia visible: «Comparte este código con el cliente solo cuando hayas verificado el pago».
4. Ningún botón ni acción de envío automático al cliente. Si en el futuro se implementa una acción de compartir, debe requerir una orden explícita del propietario, mostrar destinatario y contenido antes de enviar y pedir confirmación final.

## Estado de implementación de esta regla

Esta sección define el comportamiento aprobado, no afirma que la entrega privada ya funcione. La auditoría del protocolo encontró que SERVER-SENE actualmente envía el código en claro a Telegram al crear la solicitud y almacena únicamente su hash en D1; no se identificó una API administrativa existente para que Muse recupere el código. Por ello, la integración real requiere un canal de eventos/entrega seguro en el punto de creación de la solicitud y una interfaz de propietario autenticada. No se debe leer D1 directamente, reutilizar el secreto del bot de Telegram ni inventar una ruta de consulta.

## Canal privado de solicitudes SERVER-SENE (desarrollo)

Se incorporó en esta rama:
- `guardian-worker/src/owner-delivery.ts`: valida HMAC, timestamp anti-replay, AES-256-GCM, formato y correlación del folio; almacena el sobre cifrado en KV con TTL de 24 horas.
- `guardian-worker/src/index.ts`: monta POST/GET `/v1/guardian/owner-events`. El GET exige un `ownerReadToken` independiente del token Muse de snapshots.
- La provisión administrativa de una instalación genera `ownerReadToken` y conserva solo su hash. Se muestra una sola vez junto a los otros tokens de provisión. Revocar la instalación elimina sus eventos pendientes.
- Se añadió una prueba inicial para la configuración ausente del canal.

El código del evento no se guarda en claro en KV ni se incluye en snapshots. Muse conserva su permiso de lectura de métricas, pero no recibe por ese token el código comercial.

### Requisitos para habilitarlo
- Configurar `GUARDIAN_DELIVERY_ENC_KEY` (base64 de 32 bytes) y `GUARDIAN_DELIVERY_HMAC_KEY` (base64 de al menos 32 bytes) como secretos independientes, iguales en ambos Workers.
- Configurar el binding KV `GUARDIAN_DATA` en un entorno de staging.
- Custodiar `ownerReadToken` en una interfaz privada del propietario; no incluirlo en APK, snapshots, logs, telemetría ni contexto general de Muse.
- Ejecutar pruebas integrales de provisión, firma, cifrado, aislamiento por instalación, revocación y expiración antes de cualquier despliegue.

La consulta devuelve eventos de la instalación solicitada repetidamente durante su TTL. Todavía falta un acuse de lectura auditado y un outbox de reintentos. Por tanto, no debe considerarse producción-ready.
