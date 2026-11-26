# Exportador Android de SENE GUARDIÁN (fase inicial)

Estado: implementación de preparación en rama de pruebas. No está integrada en pantallas, no se ejecuta automáticamente y no está incluida en una APK publicada.

## Alcance

`GuardianSnapshotExporter.export(context, installationId, ingestToken)` crea y envía una captura agregada a:

`https://sene-guardian-test.nagavs28.workers.dev/v1/snapshot`

Lee exclusivamente `InventoryLocalStore.snapshot` para `INVENTARIO_MAESTRO` y `INVENTARIO_SENE`. No utiliza `SharedInventoryManager.list`, porque esa ruta puede migrar y escribir en la caché.

Por categoría solo envía:
- categoría;
- cantidad de artículos;
- suma de existencias no negativas;
- cantidad de artículos agotados;
- fecha de captura.

El estado general del negocio se envía como `unknown` porque todavía no se ha identificado una fuente de lectura pura y fiable para ese estado.

## Bajo stock

`lowStockCount` se envía como `null` (desconocido). No se establece un umbral artificial. El Worker valida y conserva ese valor nulo; MUSE lo recibirá como desconocido hasta que se documente una regla de negocio existente.

## Seguridad y límites

- La llamada es explícita; no hay scheduler, WorkManager, polling ni servicio persistente.
- Debe invocarse desde un hilo de fondo; la clase rechaza el hilo principal.
- La URL es HTTPS fija de pruebas; no sigue redirecciones.
- El token de ingestión se recibe como argumento, no se persiste, registra ni devuelve.
- No añadir el token a preferencias, archivos, logs ni recursos.
- La identidad de instalación debe corresponder al token de ingestión de esa misma instalación.
- Se descartan filas detalladas y no se transmiten códigos, nombres, precios, pasillos ni estantes.
- No se leen PIN, claves privadas, licencias, códigos comerciales, nómina, asistencia ni reportes.
- La clase no cambia inventarios, respaldos ni otros datos locales.

## Pendiente antes de usar en un dispositivo real

1. Definir e implementar almacenamiento seguro y ciclo de vida de la credencial de ingestión, con revocación y sin exponerla.
2. Añadir consentimiento explícito del propietario y una interfaz para activar/desactivar y solicitar la captura.
3. Acordar si la captura será manual o periódica; no añadir automatización sin decisión expresa.
4. Confirmar el estado real del negocio y la regla oficial de bajo stock.
5. Añadir pruebas Android con snapshots ficticios y compilar la app en CI.
6. Verificar el Worker de pruebas y sus límites gratuitos antes de cualquier envío real.

Esta entrega solo añade código fuente a la rama de trabajo. No modifica MainActivity, AndroidManifest, APK, Worker desplegado ni producción.
