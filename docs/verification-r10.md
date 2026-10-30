# Verificación de R10 — Definición del alta ante fallos

Fecha: 30/10/2026. Estado: **definición completada y aprobada por el usuario**.
R10 no implementa la recuperación: establece el comportamiento que R11–R12
deberán implementar, y R13 deberá considerar en la deduplicación del consumidor.

## Inspección y hallazgos

Se leyeron AGENTS.md, architecture.md, decisions.md y roadmap.md antes de editar,
y el flujo de alta con sus DTO, mapper, controlador y manejo de errores.

- `PatientService.java:42`: valida conflictos, guarda y confirma el paciente,
  llama a billing y luego al productor Kafka; no conserva progreso y descarta
  la respuesta billing. La escritura se realiza en `saveAndFlush`, línea 93.
- `BillingServiceGrpcClient.java:24`: el canal no conserva un cierre administrado;
  la llamada de la línea 37 no configura deadline. Estos cambios siguen en R11.
- `KafkaProducer.java:31`: espera el future con un límite de 10 segundos y propaga
  un error genérico. No registra publicación pendiente ni confirmación incierta.
- `GlobalExceptionHandler.java:24`: conflictos de unicidad usan el 400 existente;
  no existen aún los manejadores 502/503/409 acordados en D18.
- `BillingAccountService.java:24`: la operación ya recupera la cuenta por patientId
  y resuelve carreras de unicidad; es la base de la repetición segura de la RPC.

Estos hallazgos proceden de la lectura del código actual, no de nuevas pruebas
de fallos en ejecución. Los reportes R01–R09 conservan su fecha y su alcance.

## Resultado y aprobación

Se preparó [patient-registration-semantics.md](patient-registration-semantics.md)
como propuesta revisable antes de solicitar aprobación. El usuario respondió
**«Aprobar la propuesta completa»** a la consulta que detallaba billing síncrono,
evento durable con entrega posterior, clave opcional y códigos 502/503/409.
Se registró como D18 y se marcaron los cinco criterios de definición de R10.

El documento aprobado especifica el significado del 200, fallos SQL/billing/Kafka,
estados y resultados inciertos, reglas de reintentos y revisión, repetición con y
sin clave, concurrencia, cambios de pacientes pendientes y eliminación posterior.
Incluye diez grupos de escenarios exigidos a las implementaciones posteriores.

## Archivos modificados en R10

- `docs/patient-registration-semantics.md`: especificación aprobada, nueva.
- `docs/decisions.md`: D18; P04 resuelta; P05 y aprobación del patrón R12 pendientes.
- `docs/roadmap.md`: R10 completada como definición, R11–R19 pendientes.
- `docs/architecture.md`: referencia a D18 separada del comportamiento actual.
- `docs/verification-r10.md`: esta evidencia y límites.

## Comandos y verificaciones

- `Get-Content` y `rg -n` sobre documentación, clases y contratos: secuencia,
  validaciones, resultados y ausencia de seguimiento identificados en el código.
- Revisión Git del cambio documental y `git diff --check`: sin errores de
  espacios. Se comparó el snapshot R09 mediante un índice temporal de revisión:
  sólo cinco documentos cambiaron; los dos archivos nuevos también se revisaron.
  El hash del índice Git original se conservó.
- Verificación Python de los cinco documentos UTF-8: 39 enlaces relativos con
  destino existente, cinco criterios R10 marcados, sin caracteres de reemplazo,
  encabezado de criterios duplicado ni espacios finales. Resultado: OK.

No se ejecutaron Maven, contextos Spring, pruebas PostgreSQL ni Compose: sólo se
modificó documentación. No se accedió a bases ni servicios, ni se agregaron
dependencias, migraciones o cambios de código. No se alteró el índice Git del
usuario para revisar los documentos nuevos.

## Límites pendientes

La aprobación de contratos no significa que el endpoint ya acepte Idempotency-Key
o devuelva los nuevos errores. Tampoco existe aún garantía de entrega durable.
R11 limita y clasifica la RPC; R12 aprueba e implementa el mecanismo durable y
su recuperación; R13 verifica la deduplicación antes de efectos en analytics.
No se promete ausencia de mensajes repetidos, rollback remoto ni adopción de
pacientes históricos. Los criterios de ejecución se cerrarán con evidencia
nueva en sus respectivas tareas.
