# R11–R19 — Alcance de trabajo y decisiones a revisar

Fecha: 30/10/2026. Las elecciones R12/R14/R17 siguientes fueron **aprobadas
expresamente por el usuario** en esta sesión. R10/D18 se mantiene.

## Orden y verificación

1. R11: deadline gRPC configurable (2s por defecto), canal administrado y cerrado,
   validación de respuesta y clasificación de errores. Sin reintentos ocultos.
2. R12: recuperación SQL durable, claves y protección de operaciones pendientes.
3. R13–R14: publicación/consumo tolerantes a repetición y resultado persistente.
4. R15–R17: pruebas integrales aisladas, CI, correlación, métricas y roles.
5. R18–R19: CDK probado y sintetizado, emulación local acotada y preparación AWS.

Las pruebas mutantes usarán H2 explícito o contenedores/proyectos PostgreSQL y
Kafka nuevos, con datos sintéticos. Se conservarán reportes actuales. No se
modificarán migraciones aplicadas, bases del desarrollador, secretos existentes
ni infraestructura externa. No se publicarán imágenes ni se desplegará AWS.

## Propuesta R12: registro durable y outbox en patient

- Nueva migración V2 en patient, sin modificar V1 ni pacientes históricos.
- Registro de operación con snapshot inicial, clave opcional almacenada como
  hash, confirmación de billing, intención de evento, intentos y próxima ejecución.
  Paciente y registro se confirman en una transacción SQL local.
- Dispatcher en la propia aplicación: reclama trabajo con arrendamiento temporal
  y token de propietario; RPC/envío fuera de la transacción; avances condicionados
  al token. Un reinicio o worker obsoleto no puede descartar trabajo pendiente.
- Primero billing idempotente por patientId; sólo tras confirmarlo se habilita el
  evento. Reintentos con espera creciente, límite de concurrencia y revisión de
  errores permanentes. SQL y redes no se presentan como una transacción común.
- Idempotency-Key, códigos HTTP, snapshot, claves consumidas y PUT/DELETE
  pendientes siguen las reglas completas de D18. No se introduce otra librería
  ni un broker distinto. No se borran automáticamente los registros de operación;
  acceso restringido y sin snapshots personales en métricas/logs. La política
  de purga requerirá un alcance posterior aprobado antes de eliminar datos.

## Propuesta R14: total histórico de altas procesadas

- Resultado mínimo: contar pacientes distintos cuyo PATIENT_CREATED se procesó.
  Es un total histórico de altas; eliminar un paciente no decrementa el total.
- PostgreSQL propio analytics_db, Flyway y JDBC: una identidad por
  (eventType, patientId), sin guardar nombre/email. Inserción única y confirmación
  de la base antes de confirmar el offset; repetir o reiniciar no aumenta el total.
- No se incorporan eventos de actualización/eliminación en esta etapa.
- Endpoint GET /analytics/summary con {"patientsCreated": N}, y ruta protegida
  GET /api/analytics/summary en el gateway. No cambiar Protobuf.
- Mensajes inválidos: cuarentena durable propia con topic/partition/offset,
  código de causa y hash, sin payload personal; métricas y logs sanitizados.
  Un fallo SQL no confirma el offset y se vuelve a intentar con pausa acotada.
- Añadir analytics-db y volumen de desarrollo independientes a Compose, con
  configuración de producción obligatoria y H2 exclusivamente en tests.

## Propuesta R17: permisos y observabilidad

- ADMIN: listar, crear, actualizar y eliminar pacientes, consultar analytics.
- USER: sólo listar pacientes y consultar analytics. Otro rol: 403.
- Auth verifica la firma/expiración y comunica los roles mediante un encabezado
  de respuesta de /validate. El gateway utiliza sólo esa respuesta verificada;
  no confía en roles que envíe el cliente ni en un JWT sin verificar.
- 401 para token ausente/inválido, 403 para permisos insuficientes, 502 ante fallo
  de auth. Login y DTO del JWT permanecen; no se cambian cuentas ni roles existentes.
- La política protege el punto de entrada gateway. Servicios HTTP internos deben
  quedar en red privada en AWS; puertos directos locales sólo para desarrollo.
- Correlation ID validado/generado por petición, propagado HTTP/gRPC y asociado
  al trabajo durable. Métricas agregadas de pendientes, fallidos, recuperados y
  deduplicación; sin tokens, nombres, email, DNI ni payloads personales en logs.

## R18–R19: límites de la preparación autorizada

Corregir y probar CDK para cuatro bases privadas, ECS/Fargate, descubrimiento
DNS, ALB con TLS, MSK con conectividad privada, secretos por referencias, permisos
acotados, backups y retención. Imágenes por versión/digest y entradas externas
explícitas para cuenta, región, certificado y brokers; sin valores Docker locales.
Producir templates, tests y procedimiento de revisión/migración/recuperación.

LocalStack se usará sólo en un contenedor y stack nuevos: comprobar CloudFormation
y Secrets Manager con datos ficticios. No se afirmará emulación de RDS, MSK,
Fargate ni TLS productivo. El soporte/versiones se comprobarán antes de fijar la
imagen; cualquier límite de edición/licencia se registrará. El LocalStack que ya
existe en la máquina no se tocará. Todo despliegue/publicación AWS seguirá pendiente
de aprobación explícita con cuenta, región, imágenes y costes revisados.
