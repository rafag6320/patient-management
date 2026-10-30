# Recuperación y operación local — R11–R17

La semántica pública acordada está en patient-registration-semantics.md (D18).
El alta guarda paciente y registration_work en una transacción local, intenta
billing una vez y responde. Kafka se publica después desde el registro durable.

## Repetir una solicitud con seguridad

Usar un Idempotency-Key aleatorio por intención de alta. Mantenerlo al repetir
por timeout/503 y enviar los mismos campos. Un 200 confirma paciente, cuenta
billing válida e intención de evento guardada; no confirma analytics.

Una clave incompatible o consumida devuelve 409. Si billing sigue pendiente,
PUT/DELETE devuelve 409. Un 503 puede dejar un paciente visible en GET. Sin clave,
POST repetido conserva el 400 de duplicado y requiere reconciliación del cliente.
No crear otra clave para sortear una operación pendiente.

## Seguimiento y reanudación

La tabla propia registration_work conserva snapshot, hashes de clave/request,
estados billing/evento, ID de cuenta, intentos, próxima ejecución, token/lease,
correlationId y contador de recuperaciones. No hay FK que borre el registro al
eliminar un paciente. No se guardan claves HTTP originales ni errores remotos.

El dispatcher procesa lotes de hasta 10, con una llamada a la vez por instancia.
Un UPDATE condicional reclama el trabajo por 45s con token nuevo. Las respuestas
sólo actualizan la fila si ese token sigue vigente; otro propietario no puede
ser sobrescrito. Las redes se invocan fuera de transacciones SQL.

- RPC: BILLING_DEADLINE=2s por defecto, válido hasta 30s; un intento por solicitud,
  sin retries de transporte configurados. El canal se cierra al apagar Spring.
- Recuperación: espera creciente de 1 a 60 segundos, jitter hasta 200ms. UNAVAILABLE
  y RESOURCE_EXHAUSTED se recuperan conservando el mismo patientId. Resultados
  inciertos tienen REGISTRATION_UNCERTAIN_BUDGET=5 antes de requerir revisión.
- Kafka: clave patientId, snapshot inicial inmutable, acks=all, idempotencia de
  productor, espera de metadatos hasta 1s y confirmación hasta 6s. Spring Boot
  configura la fábrica efectiva y aplica también TLS/SCRAM en aws. Una confirmación perdida permite
  reenvío; la deduplicación de analytics evita sumar dos veces.
- REGISTRATION_DISPATCHER_ENABLED=false pausa el worker; no borra trabajo.
  REGISTRATION_DISPATCHER_DELAY_MS controla el intervalo, 1000ms por defecto.

Consultar, sólo por el puerto/red internos de patient:

```text
GET /actuator/registrations
GET /actuator/metrics/registration.work
```

Se exponen cantidades billingPending, requiresReview, eventsPending,
eventsConfirmed y recoveriesConfirmed, sin snapshots ni identificadores personales.
Si SQL está inaccesible, un gauge devuelve NaN en vez de un cero que oculte el fallo.

REVIEW requiere corregir y diagnosticar la causa antes de reanudar. Desde una
sesión autorizada a **patient_db** y con backup apropiado, revisar el registro por
patient_id. Una reanudación explícita de un único registro puede aplicar:

```sql
UPDATE registration_work
SET billing_status='UNCERTAIN', attempts=0, next_attempt_at=CURRENT_TIMESTAMP,
    last_error=NULL, lease_token=NULL, lease_until=TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'
WHERE patient_id=CAST(:patient_id AS uuid) AND billing_status='REVIEW'
  AND lease_until < CURRENT_TIMESTAMP;
```

El parámetro se debe enlazar, no interpolar desde entrada sin validar. Comprobar
que se modificó una única fila. No regenerar patientId, borrar cuentas, forzar
CONFIRMED ni vaciar colas. Esta tarea no ejecuta mantenimiento en datos personales.
Los snapshots/claves se retienen; cualquier purga requerirá política y aprobación.

## Analytics y mensajes fallidos

GET /api/analytics/summary, con ADMIN o USER, devuelve patientsCreated: total
histórico de patientId distintos procesados como PATIENT_CREATED. No es el número
actual de pacientes activos; PUT/DELETE no lo decrementan.

El consumidor confirma SQL antes del offset. Una caída entre ambos repite el
mensaje, pero la PK (event_type,patient_id) evita otro efecto. Mensajes inválidos
se guardan en event_quarantine por topic/partition/offset con causa y hash, sin
nombre/email ni payload. Un fallo SQL se propaga y no se descarta el mensaje:
DefaultErrorHandler reintenta con pausa de un segundo.

`/actuator/analyticswork` muestra total y cuarentena; los contadores
analytics.events.processed, duplicates y quarantined permiten observar la ejecución.
La cuarentena es evidencia de rechazo, no un evento procesado ni una reparación
silenciosa del payload. Revisar su causa antes de una republicación autorizada.

## Acceso y correlación

ADMIN puede ejecutar CRUD y consultar analytics. USER sólo puede consultar las
listas y el resumen; otros roles no autorizan. Auth verifica el JWT y responde
X-Validated-Roles; el gateway elimina ese encabezado del cliente y utiliza sólo
los roles devueltos por auth. Token ausente/inválido: 401; rol insuficiente: 403;
auth no disponible: 502. No se modificaron usuarios ni credenciales existentes.

La política se aplica en el gateway. Los servicios internos no añaden una segunda
validación JWT en esta etapa: mantenerlos privados. Los puertos directos Compose
son de desarrollo. Los endpoints operativos no tienen rutas públicas en gateway.

X-Correlation-Id acepta 1–64 caracteres ASCII seguros o se reemplaza por UUID.
Se propaga a auth/patient, gRPC billing, el registro durable y headers Kafka.
Los logs no imprimen tokens, requests Protobuf ni datos personales del evento.
El gateway limita el cache DNS a 5s para seguir contenedores recreados; los fallos
HTTP de upstream antes de enviar la respuesta devuelven 503, sin repetir POST.
Un timeout de upstream no implica rollback de la operación.

## Verificación reproducible y CI

```powershell
./scripts/verify.ps1 -WithContexts
./scripts/verify-migrations-postgres.ps1
./scripts/verify-billing-postgres.ps1
./scripts/verify-recovery-postgres.ps1
./scripts/verify-system.ps1
./scripts/synth.ps1
./scripts/verify-localstack.ps1
```

Las comparaciones Protobuf corren desde verify.ps1. No se modificaron los contratos.
verify-system.ps1 usa un proyecto pm-acceptance-UUID nuevo, bases/volúmenes propios,
puertos de prueba y fixtures sintéticos. Conserva volúmenes al detener el proyecto.
El módulo integration-tests exige -Pexternal-tests y destinos/confirmación explícitos;
por defecto compila sus tests, sin contactar localhost ni usar un datasource implícito.

`.github/workflows/verify.yml` ejecuta estos grupos en Ubuntu/Java 21/Node 22,
construye las cinco imágenes y conserva XML/logs/templates incluso si hay fallos.
La ejecución real en GitHub se aprobó para d197f7b. Enlace y evidencia se registran
por separado de los tests locales en verification-r11-r19.md.
