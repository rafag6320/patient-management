# Evidencia de R06 — 30/10/2026

## Cambios

- DTO: validadores de fecha estricta yyyy-MM-dd y DNI de dígitos entre 0 y
  2147483647, límite del almacenamiento Integer actual. El email se limita a
  255 caracteres, igual que la columna existente. No se impone edad mínima,
  fechas pasadas ni una longitud nacional de DNI no acordada.
- registeredDate sigue siendo obligatorio sólo en alta; la actualización puede
  omitirlo y no altera la fecha registrada. Se conserva `DNI` en el JSON de entrada.
- Servicio/repositorio: preconsulta de unicidad de DNI además del email. La
  actualización permite conservar los identificadores del propio paciente.
- saveAndFlush resuelve la escritura antes de gRPC/Kafka. Se traduce únicamente
  SQLSTATE 23505 a un error de duplicado, incluso después de preconsultas sin
  conflictos. Otros fallos de integridad se propagan sin falsear su causa.
- Handler: los duplicados de DNI y las carreras usan el formato existente
  `{"error":"..."}` y 400, como los duplicados de email. No se introduce 409.
  Las fechas y DNI inválidos usan el mapa de errores por campo existente.
- No cambian endpoints, formatos de respuesta, esquema, dependencias, versiones,
  credenciales ni contratos Protobuf. La coordinación distribuida sigue igual.

## Pruebas

```powershell
.\scripts\maven.ps1 -Module patient-service clean verify -Pisolated-tests
.\scripts\verify-patient-postgres.ps1
```

La suite normal usa H2 en memoria explícito, datasource comprobado y mocks de
billing y Kafka. El segundo comando crea PostgreSQL 16 en loopback con puerto
dinámico, nombre aleatorio y tmpfs. Sólo el test seleccionado explícitamente
puede usar esa conexión; no utiliza bases del desarrollador.

Escenarios cubiertos:

- Alta 200, listado 200, modificación 200 y eliminación 204; no hay nuevos efectos
  remotos en actualización/eliminación.
- Paciente inexistente 404 en PUT/DELETE y UUID malformado 400.
- Fechas imposibles, años no bisiestos y formatos incorrectos: 400 en POST/PUT.
- DNI no numérico, signos, decimales, dígitos Unicode, desborde y longitud
  excesiva: 400 sin guardar ni contactar integraciones. Se aceptan 0 y
  Integer.MAX_VALUE; se verifica un día bisiesto válido.
- Campos ausentes, JSON malformado y email inválido/demasiado largo: 400.
- Email/DNI duplicados en alta y actualización: 400, registros preservados.
- Dos altas simultáneas con mismo email o DNI: sólo una responde 200; la otra
  responde 400. Sólo el ganador ejecuta billing y Kafka.
- Dos actualizaciones simultáneas que intentan compartir email o DNI: una 200
  y otra 400, sin pérdida de registros.
- Tests unitarios fuerzan la carrera tras preconsultas exitosas y comprueban
  que 23505 se traduce y 23502 no se oculta como duplicado.

Resultados finales: ambas ejecuciones terminaron con BUILD SUCCESS, sin fallos,
errores ni pruebas omitidas:

| Grupo | Pruebas |
| --- | ---: |
| Unitarios: mapper (2), conflictos forzados (4) | 6 |
| CRUD con H2, MockMvc y mocks de integraciones | 24 |
| Contexto y comprobación de datasource aislado | 1 |
| CRUD con PostgreSQL real y MockMvc | 24 |

La suite H2 suma 31 pruebas; el segundo comando repite también los seis unitarios
antes de los 24 escenarios PostgreSQL. Estos últimos registraron cuatro
violaciones 23505 esperadas de patient_email_key/patient_dni_key, correspondientes
a las carreras de alta y actualización. En cada caso hubo un 200 y un 400.
El contenedor PostgreSQL efímero fue eliminado al terminar.

La primera ejecución PostgreSQL falló al iniciar el contexto porque rechazó el
alias TimeZone America/Buenos_Aires enviado por la JVM de Windows. El script fija
UTC exclusivamente en ese proceso de pruebas. La repetición final pasó; no se
modificó la zona horaria del sistema ni la configuración de producción.

Logs locales: `.verification-r06-h2.log` y `.verification-r06-postgres.log`.
Reportes: `.verification/patient-service/surefire-reports/` y `failsafe-reports/`.

## Límites

Las integraciones remotas de esta tarea son mocks: se comprueba cuándo se invocan,
no se vuelve a acreditar el flujo gRPC/Kafka con estas pruebas. Las pruebas reales
de R04/R05 son evidencia histórica separada. No se añadió una transacción global:
un fallo después de guardar puede dejar un alta parcial, pendiente de R10–R12.
No se incorporan migraciones ni se corrigen datos existentes (R07).
La concurrencia comprobada corresponde a unicidad de email/DNI en alta/PUT;
no se añadió control de versiones para evitar actualizaciones perdidas.

## Archivos y revisión

DTO y cuatro clases de validación; PatientRepository, PatientService,
PatientAlreadyExistsException y GlobalExceptionHandler; PatientConflictTest,
PatientCrudChecks/PatientCrudIT/PatientPostgresChecks; script
verify-patient-postgres.ps1; arquitectura, decisiones D14, roadmap, guía local
y este informe. Los cambios previos de R01–R05 se conservaron.

Se revisó el diff incluyendo archivos nuevos con un índice temporal, sin alterar
el índice del usuario. No se publicaron commits ni se desplegó infraestructura.
