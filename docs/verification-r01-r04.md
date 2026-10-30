# Evidencia de R01–R04 — 28/10/2026

## Entorno y fuentes

- Workspace: `C:/Users/Usuario/Documents/patient-management`.
- Git: rama `main`, remoto `https://github.com/rafag6320/patient-management.git`.
  Referencia inicial local `b44e478`; ningún push realizado.
- Java del script: Oracle JDK 21.0.12.1; Maven Wrapper 3.9.16, wrapper 3.3.4.
- Docker Engine 29.5.2, Docker Desktop 4.76.0, Compose 5.1.4, contenedores Linux.
- Las comprobaciones usan fuentes de esta ejecución, no JAR ni reportes antiguos.

## R01 — Herramientas

`scripts/maven.ps1 -Module patient-service -version` confirmó Maven 3.9.16 y
Java 21.0.12.1. El script restaura las variables del proceso al terminar.
La ejecución directa `mvn -B -ntp -f patient-service/pom.xml validate` con el
JAVA_HOME original (Java 25.0.3) falló como se esperaba: Enforcer rechazó una
versión fuera del rango `[21,22)`. No se arrancó una aplicación en ese control.
El checkout sin metadatos fue inicializado contra el repositorio vacío indicado
por el usuario; el commit inicial permite revisar los cambios locales.

## R02–R03 — Compilación y pruebas

Comando final: `scripts/verify.ps1 -WithContexts`.
Resultado: las cinco aplicaciones compilaron y empaquetaron desde cero con
Java 21; integration-tests compiló sin ejecutar sus pruebas HTTP externas.

| Módulo | Unitarios | Contexto/transporte/HTTP aislado | Fallos / errores / omitidos |
| --- | ---: | ---: | --- |
| patient-service | 2 | 1 | 0 / 0 / 0 |
| auth-service | 0 | 4 | 0 / 0 / 0 |
| billing-service | 0 | 2 | 0 / 0 / 0 |
| analytics-service | 0 | 1 | 0 / 0 / 0 |
| api-gateway | 0 | 1 | 0 / 0 / 0 |

Total: **11 pruebas aprobadas**. Los módulos sin tests unitarios no se presentan
como cobertura de su negocio. Surefire ejecutó el mapper sin Spring; Failsafe
ejecutó los contextos optativos. Los XML están en
`.verification/<módulo>/surefire-reports/` y `failsafe-reports/`.
La salida completa local está en `.verification-tests.log` (ignorada por Git).
Tras reforzar las propiedades de prueba explícitas en auth/billing/gateway,
se repitió `scripts/maven.ps1 -Module <módulo> verify -Pisolated-tests` para esos
tres módulos con `SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:1/must-not-be-used`
y `GRPC_SERVER_PORT=1`: los siete tests correspondientes volvieron a pasar.
Las propiedades de test prevalecieron sobre las variables de entorno; auth
comprobó explícitamente su datasource H2 en memoria y billing usó puerto aleatorio.
El helper de consola que intentaba redirigir esa repetición a un log terminó
con un error de sintaxis de redirección después de los tres `BUILD SUCCESS`.
No se generó ese log adicional; los XML Failsafe actuales conservan los siete
resultados aprobados. El comando principal de verificación sí terminó correctamente.

Auth comprobó login válido, rechazo de credenciales inválidas y `/v3/api-docs`
con `/login` y `/validate` en el esquema. Billing inició el servidor gRPC en un
puerto asignado por el sistema y respondió a una llamada real con deadline de
prueba. La cuenta sigue siendo la respuesta simulada `12345`.
Patient comprobó que el datasource era H2 en memoria. Analytics comprobó que
sus listeners no estaban ejecutándose. Gateway cargó su contexto con WebClient.

Versiones resueltas inspeccionando los JAR recién construidos:

| Dependencia | Versión |
| --- | --- |
| Spring Boot | 4.1.1 |
| Spring Cloud BOM / Gateway | 2025.1.3 / 5.0.3 |
| gRPC API, transporte y generador | 1.83.1 |
| net.devh | 3.1.0.RELEASE |
| protoc / protobuf-java / protobuf-java-util | 4.29.1 |
| protobuf-maven-plugin / os-maven-plugin | 0.6.1 / 1.7.0 |
| springdoc UI | 2.6.0 |
| Spring Kafka / cliente Kafka | 4.1.1 / 4.2.1 |
| Hibernate / driver PostgreSQL | 7.4.5.Final / 42.7.13 |
| Surefire / Failsafe / Enforcer | 3.5.6 / 3.5.6 / 3.6.2 |

Se conservaron las versiones existentes que pasaron la prueba concreta.
Se alinearon el wrapper de auth, protoc de patient/billing y el BOM Protobuf
para evitar que `protobuf-java-util` resolviera 4.35.1 junto al runtime 4.29.1.
Analytics dejó de invocar el generador gRPC: su contrato sólo define mensajes.
No cambiaron las copias de los contratos Protobuf.

## R04 — Compose

Comando de validación sintáctica: `docker compose config --quiet`, aprobado.
Construcción: `scripts/compose-smoke.ps1` construyó las cinco imágenes desde las
fuentes, con Maven 3.9.16/Java 21, y arrancó los ocho servicios saludables.
Después de corregir los datos y helpers de la prueba, el comando
`scripts/compose-smoke.ps1 -SkipBuild` ejecutó el flujo completo sobre esas
imágenes y terminó con código **0**.

Comprobaciones aprobadas:

- Ocho servicios saludables y endpoints HTTP de health en estado UP.
- OpenAPI real de auth con sus dos endpoints existentes.
- Alta HTTP de paciente sintético: guardado PostgreSQL, llamada del cliente
  gRPC real a billing y publicación Kafka confirmada antes de responder.
- Analytics registró el evento del identificador creado.
- Se insertó un usuario sintético adicional en el PostgreSQL aislado de auth,
  copiando el hash del seed sin cambiar la cuenta existente ni imprimirlo.
- Tras `restart` de todos los servicios y un nuevo `up --wait`, ambos registros
  siguieron presentes.
- Tras `down` y `up --wait`, ambos registros siguieron presentes y los offsets
  de las particiones de `patient-events` coincidieron con los anteriores.
- La parada final eliminó contenedores y red; se comprobó que los tres volúmenes
  permanecían. LocalStack siguió saludable y no se modificó.

Salida local: `.verification-compose.log` (construcción) y
`.verification-compose-smoke.log` (prueba completa aprobada), ignoradas por Git.

El script utiliza exclusivamente proyecto `pm-roadmap-test`, puertos propios y
volúmenes `pm-roadmap-test_patient-db-data`, `pm-roadmap-test_auth-db-data` y
`pm-roadmap-test_kafka-data`. La parada se realiza sin `-v`.

## Fallos encontrados y correcciones

- Gateway no arrancaba: faltaba `WebClient.Builder`. Se añadió el starter
  webclient de Boot 4 y luego pasó la prueba de contexto.
- La compilación automática del IDE interfería con `target`: hubo archivos
  Protobuf borrados/bloqueados y recursos de prueba inconsistentes. Los scripts
  usan salida `.verification` y la ejecución limpia final pasó.
- Las primeras versiones del nuevo test gRPC no compilaban por consultar una API
  de puerto inexistente. El test final captura `GrpcServerStartedEvent` y pasó.
- Se corrigió el paso de argumentos del helper PowerShell de Compose antes de
  ejecutar el ciclo completo definitivo.
- El primer POST del smoke usó `dni` y recibió 400: el contrato de entrada actual
  exige `DNI`. Se corrigió el dato del script, conservando el contrato público.
- Se corrigió la enumeración del array JSON en la aserción PowerShell del listado;
  la primera versión del helper no encontraba el paciente recién creado.

## Límites pendientes

- Los tests HTTP heredados no se ejecutaron: sus rutas y aserciones siguen
  pendientes en R15. Se excluyen por defecto y requieren destinos explícitos.
- No se verificó el perfil `prod` del gateway ni los escenarios de R05–R19.
- H2 no demuestra equivalencia de esquemas con PostgreSQL.
- Billing sigue simulado y analytics sólo registra eventos; no hay recuperación
  durable del alta. El smoke no modifica esa semántica.
- Las versiones de springdoc/net.devh se validaron en el alcance mínimo indicado,
  no en todas sus funciones. Ver referencias y límites en local-development.md.
- No hubo despliegues AWS, cambios de credenciales ni borrado de volúmenes.
- Kafka antiguo puede tener logs fuera del volumen; R04 no migra esos datos.

## Archivos afectados

- R01: `.gitignore`, `scripts/maven.ps1`, `README.md`, wrapper de auth y reglas
  Enforcer en los POM de las cinco aplicaciones.
- R02: POM de patient/billing/analytics (Protobuf), gateway (WebClient), pruebas
  gRPC/OpenAPI y Dockerfiles de las cinco aplicaciones.
- R03: `scripts/verify.ps1`, perfiles Maven, tests `*IT`, mapper unitario,
  propiedades de prueba de los cinco módulos y configuración de integration-tests.
- R04: `docker-compose.yml`, `docker-compose.test.yml`, `scripts/compose-smoke.ps1`,
  `.dockerignore`, `docker/ReadinessCheck.java` y Actuator en los cinco POM.
- Documentación: `docs/local-development.md`, `architecture.md`, `decisions.md`,
  `roadmap.md` y este informe.

Se revisó el diff completo, incluidos los archivos nuevos, mediante un índice
Git temporal. `git diff --cached --check` no detectó errores de whitespace;
el índice de trabajo del usuario no fue modificado. No hubo cambios en rutas,
campos, códigos HTTP, contratos Protobuf ni código de negocio de los servicios.
