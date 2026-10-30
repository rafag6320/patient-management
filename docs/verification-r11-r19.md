# Evidencia R11–R19

Fecha: 30/10/2026. Implementación y verificación **locales** completadas.
El usuario autorizó después publicar el checkout y verificar GitHub Actions:
commit de código `d197f7b139a4fbd907aa117d447ffb688fe87f6b`, rama `codex/roadmap-r11-r19`.
[CI aprobada](https://github.com/rafag6320/patient-management/actions/runs/41115973456)
para ese commit, con todos sus pasos y el artefacto verification-reports correctos.
La revisión anterior d682412 también pasó CI. R11–R19 se cierran dentro del alcance
acordado; R19 prepara el despliegue, que no se ejecutó. El cierre documental posterior
no modifica el código verificado.

## Autorizaciones y alcance

Se leyeron AGENTS.md, arquitectura, decisiones, roadmap y D18 antes de modificar.
Se preparó remaining-roadmap-plan.md y el usuario aprobó expresamente:

- R12: registro durable y outbox en PostgreSQL de patient, con lease/token.
- R14: contador histórico de altas, PostgreSQL propio de analytics y endpoint.
- R17: ADMIN con CRUD/analytics, USER sólo lectura, roles verificados por auth.

No se cambiaron credenciales/cuentas existentes, contratos Protobuf, migraciones
V1, bases del desarrollador ni su contenedor LocalStack. El código se publicó con autorización posterior del usuario. No se publicaron
imágenes ni se ejecutaron operaciones en una cuenta AWS. Las nuevas bases/fixtures son de
prueba/desarrollo o declaraciones de un candidato todavía no desplegado.

## Resultado por tarea

| Tarea | Implementado y comprobado localmente | Pendiente |
| --- | --- | --- |
| R11 | Deadline 2s configurable, cierre del canal, estados gRPC, success/UUID, sin retries HTTP ocultos. Servidores reales normal/caído/sin respuesta y correlación. | Validación operativa en AWS, fuera de esta tarea local. |
| R12 | Transacción paciente+registro, clave opcional, snapshot, lease/fencing, backoff/revisión y protección de cambios pendientes. Cuenta real creada con respuesta descartada, parada abrupta y recuperación sin duplicar. | Purga de snapshots, reconciliación histórica y operación productiva. |
| R13 | Contratos comparados automáticamente, clave/identidad estable, redelivery y deduplicación. Quarentena durable sin payload; SQL fallido no se descarta. | Evoluciones Protobuf futuras requieren aprobación; no se introdujo ninguna. |
| R14 | PostgreSQL/JDBC/Flyway propios, total histórico y ruta protegida, duplicados/concurrencia/reinicio. | No calcula pacientes activos ni sincroniza PUT/DELETE, según alcance aprobado. |
| R15 | Tests HTTP reparados y ampliados, destino/confirmación explícitos, proyecto aislado y reportes. Nueve pruebas externas completas aprobadas. | Pruebas del despliegue real después de autorizar AWS. |
| R16 | Workflow fijado por SHA, compilación/tests, PostgreSQL, imágenes, aceptación, CDK y LocalStack. Reportes incluso ante fallo. | CI aprobada para d197f7b; nuevas modificaciones deberán volver a verificarse. |
| R17 | Roles, encabezados no confiables eliminados, correlación HTTP/gRPC/Kafka, cantidades durables, métricas y logs sin payloads personales. | TLS interno y políticas de retención operativa futuras. |
| R18 | Cinco tests de template, síntesis, cuatro bases privadas, red/puertos/JDBC/secretos/digests corregidos. LocalStack 4.14.0 acotado verificado. | Permisos/conectividad efectivos y disponibilidad por región AWS, antes de desplegar. |
| R19 | Imágenes locales versionadas/manifiesto, templates y procedimientos de diff/publicación/migración/recuperación. | Valores/aprobación del entorno para ejecutar publicación/despliegue externo. |

## Pruebas y comandos

| Grupo | Comando/evidencia | Resultado |
| --- | --- | --- |
| Suite aislada | scripts/verify.ps1 -WithContexts; módulos afectados repetidos al añadir escenarios de borde | 207 pruebas únicas unitarias/de contexto, sin fallos. |
| PostgreSQL | verify-migrations-postgres.ps1, verify-billing-postgres.ps1, verify-recovery-postgres.ps1 | 75 escenarios únicos, sin fallos: 45 migraciones/perfiles/CRUD, 13 billing, 12 recuperación y 5 analytics. |
| Infraestructura | scripts/synth.ps1 | 5 tests aprobados y tres templates sintetizados. |
| Sistema completo | scripts/verify-system.ps1 | 9 pruebas HTTP externas, diez servicios y conservación del resultado tras down/up. |
| LocalStack | scripts/verify-localstack.ps1 | CloudFormation creó un secreto ficticio y Secrets Manager devolvió su valor esperado; contenedor nuevo retirado. |
| Contratos | scripts/check-contracts.ps1 | Ambas copias de billing y eventos coinciden. |
| Release | scripts/build-release.ps1 -Version r11-r19-d197f7b | Cinco imágenes locales y manifiesto revisable; ninguna publicación AWS. |

Total de pruebas JUnit únicas: **296** (207 + 75 + 5 + 9). Los grupos PostgreSQL
repiten unitarios durante Maven; esos unitarios no se cuentan dos veces.
Se conservaron los XML por grupo en `.verification/evidence`, evitando perder la
evidencia de los checks optativos cuando una suite posterior limpia el build.

Logs finales de esta ejecución: `.verification-final-suite.log`,
`.verification-final-edge-checks.log`, `.verification-final-postgres.log`,
`.verification-aws-guards.log`, `.verification-kafka-producer.log`,
`.verification-gateway-final.log`, `.verification-system-final.log`,
`.verification-infrastructure.log`, `.verification-localstack.log` y
`.verification-release-final.log`, `.verification-auth-logging.log`,
`.verification-producer-binding-final.log` y `.verification-recovery-producer-final.log`. Logs de servicios, estado de contenedores,
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
- La revisión detectó que la fábrica Kafka manual ignoraba las propiedades del
  productor, incluidos límites/acks y TLS/SCRAM. Se retiró esa fábrica y se utilizó
  spring-boot-starter-kafka (versiones gestionadas), consistente con analytics.
  Dos tests verifican la configuración efectiva y el contexto completo comprueba
  los límites reales; se repitieron PostgreSQL y aceptación con Kafka real.
- El plazo breve del test de servidor sin respuesta se separó de los tests de
  respuesta normal: estos usan los 2s productivos para evitar fallos de arranque
  de Netty bajo carga, manteniendo el caso de timeout en 400ms.
- Se detectó y suprimió la impresión de la credencial temporal autogenerada de
  Spring Security. Auth repitió sus 22 pruebas con éxito y el log nuevo contiene
  cero salidas de ese tipo. No se modificaron cuentas, hashes ni secretos.
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

## CI real

El usuario autorizó commit y publicación inicial del checkout en el repositorio
público. Se publicó codex/roadmap-r11-r19 sin merges ni despliegues AWS.
La ejecución 41115973456 del código d197f7b aprobó suite, PostgreSQL, síntesis,
LocalStack, construcción de imágenes, aceptación y conservación de reportes.
Artefacto final: verification-reports, ID 13007320357, 355411 bytes, conservado
hasta el 13/11/2026. SHA-256 del archivo publicado:
`87c0c8b5a41bd33c0d390e530d53b40533d55af52d102bfb3a88b9216498fcca`.

El primer run fue cancelado por una revisión posterior; no se lo cuenta como
verificación. La revisión d682412 aprobó y la corrección efectiva Kafka se volvió
a comprobar en el run final. Sólo se declararon aprobados resultados completos.

El cierre del roadmap se publica como cambio documental con [skip ci], manteniendo
intacto el código de la ejecución enlazada y evitando un nuevo ciclo de pruebas
por actualizar su propio informe. La rama conserva el código verificado más esta
documentación de cierre.

## Verificación adicional de artefactos

Se extrajeron los JAR de las cinco imágenes finales mediante contenedores que no
se arrancan y se retiran después de la inspección. Ninguno incluye h2-*.jar ni
application-test.properties; las clases de aplicación tienen major version 65
(Java 21). Patient incluye la migración V3. Se comprobó el hash de la CA pública,
las tres V1 anteriores y el seed contra el snapshot Git previo: intactos.
El workflow se parseó con SnakeYAML y se validaron SHAs; POM XML, scripts PowerShell
y enlaces locales pasaron sus comprobaciones de sintaxis/destino.

## Revisión y límites

Se revisa el diff completo incluyendo archivos nuevos con un índice Git temporal,
sin alterar el índice del usuario. Se comprueban espacios, enlaces Markdown,
contratos, ausencia de H2 en imágenes/JAR y conservación de migraciones/seed.
El bundle RDS es público y su hash/origen se documentan en docker/certs/README.md.

GitHub Actions aprobó el commit d197f7b. No hay un despliegue, un digest publicado en ECR, un diff contra una cuenta AWS ni
pruebas de TLS/RDS/MSK/Fargate reales. LocalStack no demuestra esos comportamientos.
La preparación mantiene explícitos los requisitos y pasos que faltan para obtener
esa aprobación; no se reemplaza evidencia externa por síntesis o clases existentes.
