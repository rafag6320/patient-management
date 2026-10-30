# R10 — Semántica acordada del alta de pacientes

Estado: **aprobada expresamente por el usuario**, 30/10/2026, decisión D18.
La aprobación incluye las extensiones HTTP descritas y su semántica completa.
Este documento registra la definición aprobada en R10. Posteriormente R11–R12
implementaron la recuperación; su evidencia se registra separadamente en
[verification-r11-r19.md](verification-r11-r19.md). R10 no contenía implementación.

## Problema comprobado en el código

`PatientService.createPatient` confirma `saveAndFlush`, llama a billing y luego
a Kafka. No conserva el avance de la operación. El cliente gRPC no configura
deadline y el servicio ignora `success` y `accountId` de su respuesta.
`KafkaProducer` espera hasta 10 segundos, pero una excepción no demuestra que el
broker haya rechazado el evento. Un segundo POST encuentra el email/DNI y
devuelve el 400 de duplicado, aunque el primero haya fallado después del commit.

Billing sí conserva una única cuenta por patientId y devuelve su ID real (D17).
Esto permite repetir esa llamada con el mismo ID; no permite crear un paciente
nuevo para recuperar una operación anterior.

## Decisión aprobada

Mantener el alta HTTP síncrona respecto de **billing**. Un 200 significará:

1. El paciente está confirmado en la base de patient.
2. Billing confirmó `success=true` y un `accountId` UUID válido.
3. La intención de publicar el evento está guardada de forma durable en patient.

El 200 conservará el cuerpo `PatientResponseDTO` actual. No garantizará que
Kafka ya recibió el evento ni que analytics lo procesó. Kafka podrá estar caído:
el trabajo de publicación quedará pendiente y se recuperará al volver el broker.
Esta separación es un cambio semántico explícito respecto de la espera actual.

Antes de cualquier efecto remoto se deberán guardar, en **una transacción local**,
el paciente, el snapshot inicial, el seguimiento del alta y, si existe, su clave
de idempotencia. La intención del evento deberá quedar representada desde ese
commit, inicialmente inhabilitada hasta confirmar billing. No se mantendrá una
transacción SQL abierta durante llamadas de red.

R10 acuerda estas garantías, no adopta automáticamente outbox, saga, un broker
adicional ni una librería. La representación y el mecanismo durable concretos
se aprobaron después en R12/D19 y se verifican por separado. No habrá transacción distribuida,
borrado compensatorio automático de pacientes ni acceso a la base de billing.

## Resultados HTTP acordados para la implementación

Se conservan `/patients`, `/api/patients`, los DTO y los Protobuf.
Los errores nuevos usarán el mapa existente `{"error":"..."}`, con un mensaje
que indique si el resultado puede estar pendiente, sin detalles internos ni PII.

| Situación | Respuesta acordada | Efectos y recuperación |
| --- | --- | --- |
| DTO inválido o email/DNI duplicados en una operación distinta | 400 existente | Sin efectos remotos nuevos; no se utiliza un duplicado como prueba de reintento. |
| PostgreSQL no disponible antes de confirmar la operación | 503 | No se llama a billing ni Kafka. El cliente puede repetir con la misma clave. |
| Se pierde la confirmación del commit SQL | 503 si aún puede responder | Resultado incierto: no ejecutar efectos remotos desde esa solicitud. Recuperar por la clave y el registro durable al volver la base. |
| Billing confirmado y seguimiento/evento durable | 200 existente | Alta confirmada; Kafka y analytics pueden seguir pendientes. |
| Billing no disponible, agotó el deadline o perdió su respuesta | 503 | El paciente puede existir. Conservar trabajo pendiente/incierto y el mismo patientId; no devolver éxito ni borrar datos. |
| Billing devuelve rechazo no recuperable o respuesta inválida (`success=false`, ID vacío/no UUID) | 502 | No publicar el evento ni declarar el alta lista. Conservar diagnóstico y operación para intervención; una respuesta inválida tampoco demuestra ausencia de efectos. |
| Billing confirmó, pero falla el commit que registra esa confirmación | 503 | La cuenta puede existir. Repetir luego la RPC idempotente y confirmar el avance local. |
| Kafka cae o su confirmación es incierta | 200 si el alta ya cumple sus tres condiciones | Conservar el evento pendiente y reintentar su publicación. Nunca marcar entregado sólo por haberlo intentado. |
| Reutilización incompatible de una clave | 409 | No modificar la operación original ni crear otra. |
| Defecto interno inesperado | 500 | Sin convertirlo en éxito; preservar cualquier registro durable previo para diagnóstico/recuperación. |

Si la conexión HTTP se pierde no hay garantía de que el cliente reciba ningún
código. Un 503 tampoco significa «no se creó»: puede haber trabajo en curso.
Los 401/502 actuales del gateway al validar auth conservan su significado y
pueden impedir que el POST llegue a patient.

## Estados internos y reintentos

Son estados de la **operación**, no nuevos campos públicos del paciente:

| Seguimiento de billing | Significado y siguiente acción |
| --- | --- |
| PENDIENTE | Paciente y trabajo confirmados; aún no hay confirmación de billing. Intentar con el mismo patientId y snapshot inicial. |
| INCIERTO | La RPC o el registro local de su resultado se interrumpieron. No asumir rollback remoto: repetir CreateBillingAccount con el mismo ID. |
| CONFIRMADO | Respuesta válida y accountId guardados localmente; habilitar el evento. |
| REQUIERE_REVISION | Rechazo definitivo, respuesta inválida o fallos sin clasificación segura tras un presupuesto acotado. Conservar el trabajo; diagnóstico y reanudación operativa tras corregir la causa, sin crear otro paciente. |

La publicación tendrá estados independientes PENDIENTE, INCIERTO y CONFIRMADO,
y sólo se ejecutará con billing CONFIRMADO. Un reinicio no borrará ni reiniciará
el avance. Los trabajadores deberán coordinarse para evitar carreras y registros
de avance obsoletos; los efectos remotos seguirán tolerando repeticiones.

R11 limitará cada RPC y cerrará el canal al apagar la aplicación. No habrá una
cadena de reintentos dentro de la solicitud HTTP inicial. R12 ejecutará los
reintentos durables con espera creciente, límite por intento y concurrencia
acotada. Un reenvío HTTP con clave podrá consultar/reanudar el mismo trabajo,
sin lanzar intentos remotos paralelos para una operación ya en ejecución.

UNAVAILABLE, DEADLINE_EXCEEDED y RESOURCE_EXHAUSTED permiten reintentos acotados
por intento; CANCELLED después de enviar puede ser incierto. INVALID_ARGUMENT,
UNAUTHENTICATED, PERMISSION_DENIED y FAILED_PRECONDITION requieren revisión.
INTERNAL, UNKNOWN y respuestas inválidas no prueban que no hubo efectos:
requieren un presupuesto de recuperación acotado y luego revisión. Otros estados
se clasificarán explícitamente en R11; ninguno se interpretará como éxito.
Los intervalos y presupuestos se configurarán y probarán en R11–R12.

En Kafka se acepta entrega **al menos una vez**: si se pierde el ack, reenviar
puede producir dos mensajes. Se conservará un snapshot inmutable del mismo evento
PATIENT_CREATED, identificado lógicamente por `(eventType, patientId)`, sin cambiar
el Protobuf. R13 deberá deduplicar antes de añadir efectos de negocio en analytics.
No se promete procesamiento exactamente una vez ni orden global.

## Repetición del POST: extensión aprobada y opcional del contrato

Agregar el encabezado opcional `Idempotency-Key` en POST /patients, preservado por
el gateway. Los clientes existentes podrán seguir omitiéndolo.

- Formato: 16–128 caracteres ASCII del conjunto `[A-Za-z0-9._:-]`; se recomienda
  un UUID aleatorio. Una clave mal formada devuelve 400 antes de crear trabajo.
- La clave identifica una operación de alta dentro de patient, es global para
  ese endpoint y no es un token de autenticación. Se sigue validando el JWT en
  cada intento; no habilita acceso ni cambia la autorización existente.
- La clave se reserva atómicamente con el paciente y el seguimiento, después de
  validar el DTO. Misma clave y misma entrada lógica recuperan la operación y el
  patientId originales, incluso con concurrencia o tras reiniciar.
- Se comparan todos los campos del DTO de alta: DNI como Integer, fechas como
  LocalDate y nombre/email/dirección como strings exactos. El orden de campos
  JSON no importa; no se normaliza silenciosamente el email ni se admite que
  una modificación sea tratada como repetición.
- Misma clave y distinta entrada devuelve 409. Claves distintas con el mismo
  email/DNI conservan el conflicto 400 existente.
- Si la operación está lista, devuelve 200 con el snapshot de respuesta original;
  si sigue pendiente/incierta, 503; si requiere revisión, 502. No se congela un
  503 para siempre: un nuevo intento debe observar el progreso durable.
- Las solicitudes sin clave conservan la detección de duplicados y su 400. Ante
  una respuesta perdida no tendrán la garantía de recuperar el resultado por
  POST; el cliente deberá consultar los pacientes y reconciliar. No se infiere
  una operación a partir de email/DNI ni se crea otro ID para sortear un fallo.
- La asociación clave/operación no se reutiliza ni expira automáticamente. Si el
  paciente fue eliminado, la clave queda consumida: repetir devuelve 409 y no
  recrea paciente, cuenta ni evento. La retención física y privacidad se deberán
  resolver en R12 antes de almacenar snapshots, sin borrar datos existentes.

Para impedir que cambie o desaparezca la identidad mientras se recupera billing,
PUT/DELETE de un paciente con billing no confirmado devolverán **409**. Una vez
confirmado, conservan su comportamiento actual. Los eventos y cuentas pendientes
de entrega usan el snapshot inicial aunque después se actualice/elimine el
paciente; no habrá sincronización ni cancelación retroactiva en esta etapa.
Un GET podrá seguir mostrando pacientes con alta pendiente: encontrar una fila
no demuestra que billing esté confirmado. No se propone un nuevo endpoint de
estado en R10.

## Alternativas consideradas

| Alternativa | Evaluación |
| --- | --- |
| Mantener la secuencia actual sin seguimiento | No recupera la caída posterior al commit ni la respuesta perdida. |
| Esperar billing y también el ack Kafka antes de cada 200 | Acopla la disponibilidad del alta al broker; aun así requiere seguimiento y tolerar confirmaciones inciertas. |
| Responder 202 y procesar todo en segundo plano | Válida si el producto admite alta aceptada antes de billing; exige cambiar la respuesta y definir consulta de estado. Se eligió confirmar billing antes del 200. |
| Borrar el paciente si billing/Kafka falla | Una respuesta perdida puede ocultar una cuenta/evento ya creados; el borrado no revierte esos efectos. |
| Usar email/DNI como clave de reintento | Confunde operaciones distintas y cambios de datos; no permite verificar la intención original. |

## Validación exigida a la implementación posterior

R10 se cierra con la aprobación recibida de esta semántica y sus cambios de contrato.
La aprobación no equivale a afirmar que ya esté implementada. R11–R13 deberán
demostrar, en bases y servicios aislados:

1. Error SQL antes del commit: cero RPC/eventos; resultado incierto del commit:
   recuperación con clave sin segundo paciente.
2. Caída tras el commit y antes de la RPC: recuperación tras reiniciar.
3. Billing crea la cuenta y pierde la respuesta: misma cuenta al reintentar.
4. `success=false`, ID inválido y estados gRPC: nunca un 200 falso ni un evento.
5. Confirmación billing seguida de fallo SQL: recuperación idempotente del avance.
6. Kafka caído después de confirmar billing: 200 sólo con intención durable;
   envío posterior al volver el broker y recuperación después de reiniciar.
7. Ack Kafka perdido: posible redelivery, sin duplicar el efecto de negocio.
8. Respuesta HTTP perdida y POST concurrentes con una clave: mismo ID/snapshot;
   distinta entrada 409; sin clave o con otra clave, duplicado 400.
9. PUT/DELETE pendientes: 409; clave de un paciente eliminado: no resurrección.
10. Ventanas de caída entre efecto remoto y commit local; trabajadores concurrentes,
    backoff, límite de intentos y reanudación de operaciones que requieren revisión.

No se migrarán automáticamente pacientes históricos a este flujo: requieren una
reconciliación separada. Los límites de tiempos concretos y la implementación
durable seguirán documentándose y verificándose en sus tareas correspondientes.
