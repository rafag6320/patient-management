# Evidencia de R09 — 30/10/2026

## Alcance aprobado

El usuario aprobó PostgreSQL propio de billing, JPA/Flyway, una cuenta por
patientId y reintentos que recuperan la cuenta sin sobrescribir su snapshot.
No se aprobaron facturas, importes, pagos ni cambios del contrato Protobuf.
La decisión se registra en D17.

## Implementación y archivos

- BillingAccount y BillingAccountRepository: ID UUID, patientId único, nombre
  y email en billing_account. No hay FK ni consultas a otras bases de servicios.
- BillingAccountService: valida los tres campos y sus longitudes; primero busca
  por patientId, guarda con flush y, ante una carrera SQL 23505, recupera la cuenta
  ganadora después de revertir la transacción perdedora. No se actualizan datos
  al repetir la RPC. Una violación sin cuenta coincidente no se oculta como éxito.
- BillingGrpcService: sólo devuelve success=true y el ID real tras el resultado
  persistido. INVALID_ARGUMENT para validación; UNAVAILABLE para recursos y
  transacciones transitorios reconocidos; INTERNAL para errores restantes.
  Las respuestas de error no incluyen mensajes SQL ni datos de conexión.
- POM billing: JPA, validación, Flyway y soporte PostgreSQL/driver gestionados por
  Boot 4.1.1; H2 sólo test. Sin actualización indiscriminada de versiones.
- V1__create_billing_account.sql, JPA validate y las mismas restricciones de
  Flyway de R07. No se modificaron las V1 existentes de patient/auth.
- Configuración billing local/docker/externa y guard de datasource, extendiendo
  R08. Local: localhost:5435/billing_db; Docker: billing-db:5432/billing_db.
- Compose: billing-db, volumen propio, puerto host configurable y healthcheck;
  billing espera su base saludable. Los valores/credenciales de los otros servicios
  se conservaron. Los scripts de prueba usan 15435/25435, no el puerto habitual.
- Nuevas pruebas unitarias y PostgreSQL; BillingServiceApplicationIT ahora prueba
  la persistencia real con H2. El smoke comprueba además el registro de billing.
- Arquitectura, decisiones, roadmap, entornos, migraciones y guía local actualizados.

## Verificaciones

```powershell
.\scripts\verify.ps1 -WithContexts
.\scripts\verify-billing-postgres.ps1
.\scripts\compose-smoke.ps1 -ProjectName pm-r09-full-test
```

La suite normal usa H2 explícito para billing. El helper PostgreSQL crea un
contenedor efímero de nombre aleatorio, puerto loopback dinámico, tmpfs y base
billing_r09. El JVM de esa prueba usa UTC. No se usan bases del desarrollador.

| Suite normal | Pruebas | Fallos / errores / omitidas |
| --- | ---: | --- |
| patient | 40 | 0 / 0 / 0 |
| auth | 21 | 0 / 0 / 0 |
| gateway | 54 | 0 / 0 / 0 |
| analytics | 8 | 0 / 0 / 0 |
| billing: guard de configuración | 7 | 0 / 0 / 0 |
| billing: servicio y adaptador gRPC unitarios | 8 | 0 / 0 / 0 |
| billing: contexto y RPC H2 real | 10 | 0 / 0 / 0 |
| **Total** | **148** | **0 / 0 / 0** |

La suite PostgreSQL ejecuta **13 escenarios**: los diez de contexto/RPC,
verificación del historial V1, reinicio de aplicación y fallo de datasource.
El comando repite los 15 unitarios de billing; no se cuentan nuevamente como
escenarios PostgreSQL. La ejecución final terminó con BUILD SUCCESS, sin fallos,
errores ni omitidos. El contenedor efímero fue eliminado al terminar.

Comprobaciones:

- La RPC devuelve UUID que corresponde a una fila almacenada.
- La repetición con nombre/email distintos devuelve la misma cuenta y conserva
  su nombre/email iniciales.
- Ocho RPC simultáneas comparten un solo ID y una sola fila para ese patientId.
- Campos vacíos, email inválido y longitudes excesivas: INVALID_ARGUMENT y
  ninguna fila nueva, por transporte gRPC real.
- Unitarios fuerzan carrera tras preconsulta sin resultados; se recupera al
  ganador. SQL 23502 y 23505 sin cuenta coincidente se propagan, sin falso éxito.
- Dos contextos de aplicación sucesivos sobre la misma base PostgreSQL usan
  puertos gRPC distintos y recuperan el mismo ID/snapshot con una sola fila.
- Pool destruido mientras gRPC continúa activo: INTERNAL genérico sin success.
  Los unitarios prueban UNAVAILABLE para fallos de recurso reconocidos y que
  ningún fallo emita onNext/onCompleted ni filtre detalles de almacenamiento.

## Compose real

El proyecto aislado pm-r09-full-test construyó e inició nueve servicios: cinco
aplicaciones, tres PostgreSQL y Kafka. El alta HTTP pasó por patient, billing
gRPC y Kafka, y analytics registró el evento. Se consultó exclusivamente desde
el harness de pruebas la base propia de billing para comprobar el UUID real de
la cuenta asociada al paciente sintético.

Después de restart de todos los servicios y de down/up, el mismo UUID de billing
seguía presente; también se conservaron patient, auth y offsets Kafka. El script
emitió OK y terminó con código 0. Se eliminaron sólo contenedores/red del proyecto,
conservando sus cuatro volúmenes. No se adoptaron ni borraron volúmenes anteriores.

## Incidencias corregidas en los fixtures

- La subclase PostgreSQL no heredó automáticamente la configuración anidada que
  captura el puerto gRPC. Se añadió un Import explícito antes de repetir la suite.
- El test de pool destruido esperaba UNAVAILABLE. Hibernate representa ese fallo
  de ciclo de vida como excepción genérica, por lo que el contrato previsto es
  INTERNAL. Se corrigió la expectativa y se comprueba su descripción sanitizada;
  no se relajó la garantía de que un error nunca devuelva éxito.

## Límites y evidencia

patientId es el string recibido, no se convierte ni normaliza a UUID. El cliente
actual de patient envía su UUID habitual. Las cuentas no sincronizan cambios de
nombre/email ni se eliminan automáticamente al borrar pacientes. Los pacientes
históricos no reciben cuentas retroactivamente y los IDs simulados 12345 no se
importan como registros. La coordinación/reconciliación y los deadlines/reintentos
del cliente de patient siguen pendientes de R10–R12.

La idempotencia local de billing no proporciona atomicidad entre PostgreSQL,
gRPC y Kafka. No se acredita aquí recuperación de todo el alta ante resultados
inciertos. No se desplegó AWS ni se modificaron secretos existentes.

Logs locales ignorados: .verification-r09-suite.log, .verification-r09-postgres.log
y .verification-r09-compose.log. XML actuales en .verification/<módulo> y en
.verification/billing-service/{surefire,failsafe}-reports/.

Se revisó el diff completo con archivos nuevos mediante índice Git temporal,
sin modificar el índice del usuario y sin errores de whitespace. Los Protobuf
de billing y patient se comprobaron idénticos y sin cambios; los hashes de las
migraciones previas de patient/auth coinciden con los de R08. El JAR de billing
incluye V1 y excluye H2. La configuración Compose pasó validación sintáctica.
