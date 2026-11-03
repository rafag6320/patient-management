# Semántica del alta de pacientes

## Garantía de éxito

POST /api/patients, a través del gateway, devuelve 200 con PatientResponseDTO
cuando se confirman tres condiciones:

1. El paciente está guardado en PostgreSQL.
2. Billing confirmó una cuenta persistida con ID UUID válido.
3. La intención de publicar PATIENT_CREATED está guardada en registration_work.

El 200 no espera la entrega Kafka ni el procesamiento de analytics. Antes de
contactar servicios remotos, paciente y trabajo se confirman en una transacción
local. La RPC y la publicación no mantienen abierta esa transacción.

No hay atomicidad entre PostgreSQL, gRPC y Kafka. Un timeout o un 503 pueden dejar
un paciente visible y una cuenta ya creada. No se aplica borrado compensatorio.

## Resultados HTTP

| Situación | Respuesta | Recuperación |
| --- | --- | --- |
| DTO inválido o email/DNI duplicados en otra operación | 400 | Corregir datos; un duplicado no demuestra que sea el mismo intento. |
| PostgreSQL no disponible o confirmación SQL incierta | 503 si todavía se puede responder | Conservar clave y payload; comprobar el resultado al recuperar la base. |
| Paciente, billing e intención durable confirmados | 200 | Kafka y analytics pueden seguir pendientes. |
| Billing inaccesible, deadline agotado o resultado incierto | 503 | Conservar el mismo patientId y trabajo; repetir con la misma clave. |
| Rechazo no recuperable o respuesta billing inválida | 502 | Conservar la operación para revisión; no publicar el evento ni declarar éxito. |
| Billing confirmado, pero no se confirma su avance local | 503 | Recuperar repitiendo la RPC idempotente. |
| Kafka caído o ack incierto después de confirmar el alta | 200 | El dispatcher conserva y reenvía el evento pendiente. |
| Clave incompatible/consumida o PUT/DELETE con billing pendiente | 409 | No crear otra operación ni modificar la original para eludir el conflicto. |
| Defecto inesperado | 500 | Diagnosticar sin asumir que no hubo efectos previos. |

Los errores de patient usan el mapa existente de error o de validación por campo.
Si se pierde la conexión HTTP, el cliente puede no recibir ningún código.
El gateway puede rechazar la solicitud antes de que llegue a patient: 401 por
JWT inválido/ausente, 403 por permisos y 502 por fallo de validación remota de auth.

## Idempotency-Key

El encabezado es opcional y se preserva en el gateway. Debe tener 16–128 caracteres
ASCII del conjunto [A-Za-z0-9._:-]; un UUID aleatorio es una opción válida.
Una clave mal formada devuelve 400 antes de crear trabajo.

La clave identifica una intención de alta y no reemplaza la autenticación.
Se guarda como hash junto con el fingerprint de la entrada. Se comparan todos los
campos: DNI convertido a Integer, fechas a LocalDate y strings exactos de nombre,
email y dirección. El orden de campos JSON no importa; no se normaliza el email.

- Misma clave y entrada lógica: mismo patientId y snapshot original, incluso
  después de una actualización o reinicio.
- Misma clave y entrada diferente: 409.
- Clave diferente con email/DNI duplicado: 400.
- Operación lista: 200; pendiente/incierta: 503; en revisión: 502. La respuesta
  observa el avance durable, no congela el primer 503.
- Paciente eliminado: la clave queda consumida; repetir devuelve 409 y no recrea
  paciente, cuenta ni evento. No hay expiración ni purga automática de claves.

Sin clave se conserva el 400 de duplicados. Después de perder una respuesta, el
cliente debe consultar y reconciliar; no se infiere identidad de operación por
email/DNI ni se crea otro paciente para sortear un fallo.

## Recuperación

registration_work conserva estados de billing y evento por separado:

| Estado de billing | Significado |
| --- | --- |
| PENDING | Paciente y trabajo guardados; falta confirmar billing. |
| UNCERTAIN | La RPC o el registro de su resultado se interrumpieron. |
| CONFIRMED | Respuesta válida e ID de cuenta guardados localmente. |
| REVIEW | Rechazo definitivo, respuesta inválida o presupuesto de resultados inciertos agotado. |

El evento sólo se publica con billing confirmado. El dispatcher usa un lease de
45 segundos y un token para impedir que un trabajador obsoleto sobrescriba avances.
Repite la RPC con el mismo patientId y snapshot inicial, con espera creciente.
Cada intento tiene deadline; los casos inciertos tienen presupuesto acotado antes
de revisión. Detalles y reanudación en [operación](recovery-operations.md).

Kafka admite redelivery cuando el ack es incierto. El evento conserva clave
patientId y snapshot inmutable; analytics deduplica por (eventType, patientId).
Se evita duplicar el efecto de conteo sin prometer procesamiento exactamente una
vez ni orden global.

## Actualizaciones, eliminaciones y datos anteriores

PUT/DELETE devuelven 409 hasta confirmar billing. Después mantienen su contrato,
pero no sincronizan el snapshot de billing ni cancelan eventos pendientes.
Analytics cuenta altas históricas, no pacientes activos.

GET puede mostrar pacientes con alta pendiente. La existencia de una fila no
confirma billing. Los pacientes anteriores al flujo durable no se reconcilian
automáticamente. La retención de snapshots requiere una política propia antes
de implementar purgas.

## Verificación

Los tests de recuperación cubren fallos SQL, RPC con respuesta perdida, eventos
repetidos, claves concurrentes y reinicios. Se ejecutan con datos sintéticos y
bases propias; los comandos están en [desarrollo local](local-development.md).
