# Evidencia R11–R19

Fecha: 30/10/2026. Implementación y verificación **locales**, sin publicar el
repositorio ni desplegar infraestructura AWS. R16 conserva pendiente la ejecución
real de GitHub Actions; R18/R19 conservan esa dependencia para su cierre integral.

## Autorizaciones y alcance

Se leyeron AGENTS.md, arquitectura, decisiones, roadmap y D18 antes de modificar.
Se preparó remaining-roadmap-plan.md y el usuario aprobó expresamente:

- R12: registro durable y outbox en PostgreSQL de patient, con lease/token.
- R14: contador histórico de altas, PostgreSQL propio de analytics y endpoint.
- R17: ADMIN con CRUD/analytics, USER sólo lectura, roles verificados por auth.

No se cambiaron credenciales/cuentas existentes, contratos Protobuf, migraciones
V1, bases del desarrollador ni su contenedor LocalStack. No se publicaron imágenes
ni se ejecutaron operaciones en una cuenta AWS. Las nuevas bases/fixtures son de
prueba/desarrollo o declaraciones de un candidato todavía no desplegado.

## Resultado por tarea

| Tarea | Implementado y comprobado localmente | Pendiente |
| --- | --- | --- |
| R11 | Deadline 2s configurable, cierre del canal, estados gRPC, success/UUID, sin retries HTTP ocultos. Servidores reales normal/caído/sin respuesta y correlación. | Validación operativa en AWS, fuera de esta tarea local. |
| R12 | Transacción paciente+registro, clave opcional, snapshot, lease/fencing, backoff/revisión y protección de cambios pendientes. Cuenta real creada con respuesta descartada, parada abrupta y recuperación sin duplicar. | Purga de snapshots, reconciliación histórica y operación productiva. |
| R13 | Contratos comparados automáticamente, clave/identidad estable, redelivery y deduplicación. Quarentena durable sin payload; SQL fallido no se descarta. | Evoluciones Protobuf futuras requieren aprobación; no se introdujo ninguna. |
| R14 | PostgreSQL/JDBC/Flyway propios, total histórico y ruta protegida, duplicados/concurrencia/reinicio. | No calcula pacientes activos ni sincroniza PUT/DELETE, según alcance aprobado. |
| R15 | Tests HTTP reparados y ampliados, destino/confirmación explícitos, proyecto aislado y reportes. Nueve pruebas externas completas aprobadas. | Pruebas del despliegue real después de autorizar AWS. |
| R16 | Workflow fijado por SHA, compilación/tests, PostgreSQL, imágenes, aceptación, CDK y LocalStack. Reportes incluso ante fallo. | Publicación autorizada y enlace/resultado de un job GitHub real. |
| R17 | Roles, encabezados no confiables eliminados, correlación HTTP/gRPC/Kafka, cantidades durables, métricas y logs sin payloads personales. | TLS interno y políticas de retención operativa futuras. |
| R18 | Cinco tests de template, síntesis, cuatro bases privadas, red/puertos/JDBC/secretos/digests corregidos. LocalStack 4.14.0 acotado verificado. | Cierre formal de CI, permisos/conectividad efectivos y disponibilidad por región AWS. |
| R19 | Imágenes locales versionadas/manifiesto, templates y procedimientos de diff/publicación/migración/recuperación. | CI y valores/aprobación del entorno para ejecutar publicación/despliegue externo. |

## Pruebas y comandos

| Grupo | Comando/evidencia | Resultado |
| --- | --- | --- |
| Suite aislada | scripts/verify.ps1 -WithContexts; módulos afectados repetidos al añadir escenarios de borde | 205 pruebas únicas unitarias/de contexto, sin fallos. |
| PostgreSQL | verify-migrations-postgres.ps1, verify-billing-postgres.ps1, verify-recovery-postgres.ps1 | 75 escenarios únicos, sin fallos: 45 migraciones/perfiles/CRUD, 13 billing, 12 recuperación y 5 analytics. |
| Infraestructura | scripts/synth.ps1 | 5 tests aprobados y tres templates sintetizados. |
| Sistema completo | scripts/verify-system.ps1 | 9 pruebas HTTP externas, diez servicios y conservación del resultado tras down/up. |
| LocalStack | scripts/verify-localstack.ps1 | CloudFormation creó un secreto ficticio y Secrets Manager devolvió su valor esperado; contenedor nuevo retirado. |
| Contratos | scripts/check-contracts.ps1 | Ambas copias de billing y eventos coinciden. |
| Release | scripts/build-release.ps1 -Version roadmap-r11-r19-20261030-final | Cinco imágenes locales y manifiesto revisable; ninguna publicación AWS. |

Total de pruebas JUnit únicas: **294** (205 + 75 + 5 + 9). Los grupos PostgreSQL
repiten unitarios durante Maven; esos unitarios no se cuentan dos veces.
Se conservaron los XML por grupo en `.verification/evidence`, evitando perder la
evidencia de los checks optativos cuando una suite posterior limpia el build.

Logs finales de esta ejecución: `.verification-final-suite.log`,
`.verification-final-edge-checks.log`, `.verification-final-postgres.log`,
`.verification-aws-guards.log`, `.verification-kafka-producer.log`,
`.verification-gateway-final.log`, `.verification-system.log`,
`.verification-infrastructure.log`, `.verification-localstack.log` y
`.verification-release-final.log`. Logs de servicios, estado de contenedores,
recursos y comandos de prueba en `.verification/acceptance-*.log`.

Las pruebas PostgreSQL sólo usan contenedores con nombres aleatorios y tmpfs;
las URLs/puertos se pasan explícitamente y sus JVM usan UTC. Los contextos normales
usan H2 explícito, mocks de remotos en patient y listeners detenidos en analytics.
Los tests externos usan puertos 34xxx/35xxx/39xxx y proyectos pm-acceptance-UUID
nuevos. Los fixtures USER copian el hash del seed de pruebas a una fila nueva;
no cambian ninguna cuenta, contraseña o rol anterior.

## Fallos encontrados y corregidos

- Primeras compilaciones: imports Timestamp/Stack ambiguos y diferencia del nombre
  redirectHttp de CDK. Se corrigieron y se repitieron sus checks actuales.
- Tests de recuperación: al volver a configurar mocks con when se ejecutaba el
  comportamiento anterior. Se corrigió el fixture con doReturn/doAnswer.
- El primer intento de fixture Compose consumió -v como parámetro PowerShell.
  El wrapper de aceptación ahora pasa argumentos nativos sin esa interferencia.
- La recuperación integral detectó cache DNS y conexiones obsoletas del gateway
  al recrear patient. Se limitó DNS a 5s y se agregó 503 ante error/timeout no
  comprometido, sin repetir POST. Se comprobaron todas las rutas y se repitió la
  aceptación completa. Compose ahora limita RAM/CPU, con evidencia de estado;
  no se atribuyen los fallos anteriores a OOM sin pruebas.
- Un deadline puede devolver 503 aun con billing disponible si el primer contacto
  excede el presupuesto. El cliente de aceptación repite la misma clave y sólo
  admite 200/503 mientras espera confirmación; no acepta 500 ni claves nuevas.
- La revisión del template detectó un digest parametrizado con separador de tag
  incorrecto y un provider Lambda Node 18/20. Se corrigieron a repo@digest y Node 22
  y se agregaron aserciones antes de repetir la síntesis.
- Headers de correlación Kafka nulos/largos ya no bloquean un evento válido; test
  específico prueba que tampoco queda MDC de otra petición.

Los reportes fallidos intermedios no se presentan como evidencia de aprobación.
Los resultados citados corresponden a repeticiones actuales aprobadas.

## Archivos principales

- patient: cliente gRPC/errores, registration/*, V2/V3, controlador/servicio y
  productor Kafka; tests de RPC, publicación, CRUD y recuperación.
- analytics: JDBC/Flyway, analytics/*, consumidor/error handler, V1, perfiles y tests.
- auth/gateway: roles verificados, autorización, correlación, disponibilidad/DNS y tests.
- billing: correlación gRPC/HTTP; persiste con el contrato de R09 sin modificaciones.
- Compose: analytics-db, volumen propio, arranque recuperable y límites locales.
- Scripts: checks de contratos, recuperación PostgreSQL, sistema, síntesis,
  emulación, release y revisión; helpers anteriores adaptados al nuevo resultado.
- infrastructure: candidato, registry/emulación, tests y POM reproducible.
- .github/workflows/verify.yml: pipeline sin secretos ni despliegue.
- Documentación: decisiones D19–D22, arquitectura/roadmap, perfiles/migraciones,
  recovery-operations.md, aws-deployment.md y el alcance aprobado.

## Revisión y límites

Se revisa el diff completo incluyendo archivos nuevos con un índice Git temporal,
sin alterar el índice del usuario. Se comprueban espacios, enlaces Markdown,
contratos, ausencia de H2 en imágenes/JAR y conservación de migraciones/seed.
El bundle RDS es público y su hash/origen se documentan en docker/certs/README.md.

No hay prueba real de GitHub Actions mientras el repositorio siga vacío. Tampoco
hay un despliegue, un digest publicado en ECR, un diff contra una cuenta AWS ni
pruebas de TLS/RDS/MSK/Fargate reales. LocalStack no demuestra esos comportamientos.
La preparación mantiene explícitos los requisitos y pasos que faltan para obtener
esa aprobación; no se reemplaza evidencia externa por síntesis o clases existentes.
