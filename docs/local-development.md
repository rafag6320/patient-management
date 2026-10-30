# Desarrollo y verificación local

## Checkout y herramientas

Desde R08, los valores de desarrollo requieren `local` (host) o `docker`
(Compose). Sin perfil y en `prod` las variables de conexión y JWT son explícitas,
H2 no se empaqueta y no se ejecuta el seed de auth. Consultar
[environments.md](environments.md) para la matriz de perfiles y validaciones.

Desde R07, las bases PostgreSQL usan Flyway y Hibernate validate. Antes de
reutilizar una base anterior, seguir [migrations.md](migrations.md); no borrar
volúmenes para resolver un rechazo de adopción. El comando
`scripts/verify-migrations-postgres.ps1` prueba creación, adopción de copias,
conservación de datos, rollback y arranque de ambos servicios en PostgreSQL tmpfs.

Repositorio: <https://github.com/rafag6320/patient-management>.
La carpeta recibida no tenía `.git`; se inicializó `main` y se vinculó `origin`
al repositorio vacío indicado por el usuario. El commit local `b44e478` conserva
las fuentes previas a R01–R04. No se publicó ningún commit.
Para identificar el estado: `git remote -v`, `git rev-parse HEAD` y `git status --short`.
No hay POM agregador: cada aplicación conserva su POM y su versión Spring Boot.

Se requieren JDK **21**, PowerShell, Docker Engine con contenedores Linux y
Docker Compose con soporte de `up --wait`. Git identifica el checkout.
Maven no requiere instalación global: los cinco wrappers 3.3.4 descargan
**Maven 3.9.16**. La primera ejecución requiere acceso a Maven Central.

En este equipo `java` resolvía Oracle 21.0.12.1 y Maven global utilizaba Temurin
25.0.3 por `JAVA_HOME`. `scripts/maven.ps1` selecciona un JDK 21 para su proceso
y restaura `JAVA_HOME` y `PATH` al terminar; no cambia la configuración del sistema.
Para seleccionar una instalación explícita:

```powershell
$env:JDK21_HOME = 'C:/Program Files/Java/jdk-21.0.12.1'
.\scripts\maven.ps1 -Module patient-service -version
```

Sin `JDK21_HOME`, se usa `JAVA_HOME` si apunta a 21, o una instalación 21 bajo
Program Files. En otros sistemas definir `JDK21_HOME` y utilizar PowerShell 7.
Los POM de las cinco aplicaciones rechazan Java diferente de 21 o Maven
diferente de 3.9.16 mediante Enforcer 3.6.2. Los Dockerfiles utilizan
`maven:3.9.16-eclipse-temurin-21` y un runtime `eclipse-temurin:21-jre`.
Los tags no fijan el parche JDK ni el digest; la reproducción bit a bit no está garantizada.

## Compilación y comandos por módulo

Desde la raíz, reemplazar `patient-service` por cualquiera de los módulos:

```powershell
.\scripts\maven.ps1 -Module patient-service clean package
.\scripts\maven.ps1 -Module patient-service clean verify -Pisolated-tests
.\scripts\maven.ps1 -Module patient-service dependency:tree
```

| Módulo | Compilación/tests | Arranque local recomendado |
| --- | --- | --- |
| patient-service | `scripts/maven.ps1 -Module patient-service clean verify` | `docker compose up -d --wait patient-service` |
| auth-service | `scripts/maven.ps1 -Module auth-service clean verify` | `docker compose up -d --wait auth-service` |
| billing-service | `scripts/maven.ps1 -Module billing-service clean verify` | `docker compose up -d --wait billing-service` |
| analytics-service | `scripts/maven.ps1 -Module analytics-service clean verify` | `docker compose up -d --wait analytics-service` |
| api-gateway | `scripts/maven.ps1 -Module api-gateway clean verify` | `docker compose up -d --wait api-gateway` |
| integration-tests | `scripts/maven.ps1 -Module integration-tests clean verify` | Sin aplicación; tests HTTP externos requieren perfil explícito |
| infrastructure | `scripts/maven.ps1 -Module infrastructure compile` | Fuera de R01–R04; no ejecutar el script de despliegue |

Para las cinco aplicaciones, `scripts/verify.ps1` compila desde cero y ejecuta
Surefire. `-WithContexts` añade Failsafe con `isolated-tests`.
Los Dockerfiles se construyen **desde la raíz**:
`docker build -f patient-service/Dockerfile .`.

## Grupos de pruebas y aislamiento

Desde R09, billing usa PostgreSQL propio y crea cuentas idempotentes por patientId.
`scripts/verify-billing-postgres.ps1` prueba la RPC, concurrencia, reinicio del
servicio y errores de almacenamiento contra PostgreSQL efímero, sin bases del
desarrollador. `scripts/compose-smoke.ps1 -ProjectName pm-r09-full-test` verifica
el alta real, el registro billing y su ID conservado tras restart/down/up. Publica
la base de pruebas en 15435; la base habitual está en 5435. El flujo de auth
aislado utiliza 25435 para esa nueva base, evitando colisiones con desarrollo.

Para R06, `scripts/maven.ps1 -Module patient-service clean verify -Pisolated-tests`
comprueba validación, CRUD y concurrencia con H2. El comando
`scripts/verify-patient-postgres.ps1` repite el CRUD con PostgreSQL 16 real en un
contenedor de nombre aleatorio, puerto loopback dinámico y almacenamiento tmpfs.
Sólo elimina ese contenedor efímero; no monta ni borra volúmenes del desarrollador.
Billing y Kafka se sustituyen por mocks en estas pruebas para observar exactamente
qué operaciones inválidas o duplicadas intentan ejecutar efectos remotos.
Los tests PostgreSQL se seleccionan explícitamente y no arrancan con la suite
normal. Requieren Docker disponible y la imagen postgres:16.

| Grupo | Comando | Dependencias y datos |
| --- | --- | --- |
| Unitarios `*Test` | `scripts/verify.ps1` | Mapper y filtros gateway; sin contexto Spring ni servicios externos, con tiempo virtual para timeouts |
| Contextos `*IT` | `scripts/verify.ps1 -WithContexts` | Patient/auth usan H2 en memoria y perfil test; billing usa puerto gRPC aleatorio; analytics no inicia listeners; gateway usa upstreams HTTP locales y puertos aleatorios |
| Sistema Compose | `scripts/compose-smoke.ps1` | Dos PostgreSQL y Kafka reales, cinco imágenes recién construidas, proyecto `pm-roadmap-test`, puertos y volúmenes propios |
| HTTP heredados | Perfil `external-tests` de integration-tests | Requieren `-Dauth.baseUri=...` y `-Dgateway.baseUri=...`; no tienen destino implícito. Reparación funcional pendiente en R15 |

Ejemplo de invocación explícita de los tests HTTP heredados, sólo contra un
entorno de prueba preparado:

```powershell
.\scripts\maven.ps1 -Module integration-tests verify -Pexternal-tests -Dauth.baseUri=http://localhost:14005 -Dgateway.baseUri=http://localhost:14004
```

Ese grupo conserva rutas y aserciones pendientes de R15; no forma parte de la
aceptación del smoke de R04. `clean verify` de integration-tests no lo ejecuta.
Los tests H2 verifican contextos y HTTP de auth, no compatibilidad del esquema con
PostgreSQL. El smoke Compose cubre PostgreSQL real, gRPC y Kafka por el alta de
un paciente sintético, y observa su evento en los logs de analytics.
No demuestra recuperación distribuida ni persistencia de billing.

### Pruebas de gateway y autenticación (R05)

```powershell
.\scripts\maven.ps1 -Module api-gateway clean verify -Pisolated-tests
.\scripts\maven.ps1 -Module auth-service clean verify -Pisolated-tests
# Requiere PowerShell 7; construye imágenes y usa sólo el proyecto pm-r05-test.
.\scripts\verify-auth-compose.ps1
```

El gateway se prueba por HTTP real en puertos aleatorios, con upstreams locales
controlados, tanto en el perfil principal como en prod. Auth utiliza el H2
explícito de sus tests y verifica JWT válidos, expirados y firmas inválidas.
El script Compose usa PostgreSQL propio y prueba login y lectura de pacientes
por gateway, rechazo de tokens y auth pausado/detenido en ambos perfiles.
No crea pacientes ni modifica credenciales. Al finalizar elimina contenedores
y conserva sus volúmenes. `-SkipBuild` permite repetir con las imágenes ya construidas.
Usa gateway 24004, auth 24005, bases 25433/25434 y Kafka 29094, separados del
entorno habitual y del smoke de R04.

Para Postman, con el Compose habitual:

1. `POST http://localhost:4004/api/auth/login`, cuerpo JSON con `email` y `password`.
2. Copiar `token` de la respuesta a Authorization → Bearer Token.
3. `GET http://localhost:4004/api/patients` devuelve la lista.

`AUTH_SERVICE_ADDRESS` configura a la vez login y validación JWT.
`PATIENT_SERVICE_ADDRESS` configura el destino de pacientes. El perfil principal
usa DNS Docker en el perfil docker; prod hereda las rutas y exige destinos
explícitos. Para procesos del host, local usa localhost. También se pueden
establecer ambas variables con URLs accesibles desde el proceso gateway.

`AUTH_VALIDATION_TIMEOUT` y `AUTH_PROXY_TIMEOUT` admiten duraciones positivas
como `2s`, valor predeterminado. A falta de token o ante su rechazo se responde
401; un fallo de conexión o timeout de auth devuelve 502. El proxy conserva los
códigos recibidos de auth (por ejemplo, 401 ante login inválido). Si ya se envió
la respuesta, no es posible cambiar su código ante un fallo posterior.
El límite de validación no se aplica al procesamiento de patient.
Estos tiempos requieren recrear/reiniciar gateway tras cambiar su configuración.

## Direcciones y puertos

| Servicio | Desde otros contenedores | Host, Compose habitual | Host, smoke aislado |
| --- | --- | --- | --- |
| Patient HTTP | `http://patient-service:4000` | A través del gateway | `http://localhost:14000` |
| Auth HTTP | `http://auth-service:4005` | `http://localhost:4005` | `http://localhost:14005` |
| Billing HTTP / gRPC | `billing-service:4001` / `billing-service:9001` | Sin publicación | gRPC `localhost:19001` |
| Analytics HTTP | `http://analytics-service:4002` | `http://localhost:4002` | `http://localhost:14002` |
| Gateway HTTP | `http://api-gateway:4004` | `http://localhost:4004` | `http://localhost:14004` |
| Patient PostgreSQL | `patient-db:5432/patient_db` | `localhost:5433/patient_db` | `localhost:15433/patient_db` |
| Auth PostgreSQL | `auth-db:5432/auth_db` | `localhost:5434/auth_db` | `localhost:15434/auth_db` |
| Kafka | `kafka:9092` | `localhost:9094` | `localhost:19094` |

Cada HTTP de aplicación dispone de `/actuator/health`; sólo se utiliza la
exposición por defecto de Actuator. Billing comprueba además su puerto gRPC en
el healthcheck del contenedor. El test de billing verifica la RPC, y el smoke
verifica el cliente real de patient contra billing real.
OpenAPI de auth se consulta directamente en `/v3/api-docs`.

Las variables `AUTH_HOST_PORT`, `ANALYTICS_HOST_PORT`, `GATEWAY_HOST_PORT`,
`PATIENT_DB_HOST_PORT`, `AUTH_DB_HOST_PORT` y `KAFKA_HOST_PORT` permiten evitar
colisiones. Kafka anuncia el puerto externo configurado, conservando `kafka:9092`
para la red Docker. Desde el host no se resuelven los nombres de servicios Docker.
Para ejecutar patient fuera de Compose hay que configurar explícitamente
`SPRING_DATASOURCE_URL`, `BILLING_SERVICE_ADDRESS`, `BILLING_SERVICE_PORT` y
`SPRING_KAFKA_BOOTSTRAP_SERVERS` con las direcciones del host.

## Compose: disponibilidad y conservación de datos

```powershell
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 300
docker compose ps
docker compose logs --tail 100 patient-service
docker compose stop
docker compose start --wait --wait-timeout 300
docker compose down
```

`stop` conserva contenedores y volúmenes. `down` elimina contenedores y red,
conservando volúmenes nombrados. No utilizar `down -v`, `volume prune` ni borrar
volúmenes con datos. Conservar el nombre del proyecto Compose al reutilizar datos.
Se eliminaron `container_name` fijos para permitir proyectos independientes.
Esto cambia los nombres de contenedores, no los nombres DNS de servicios ni
los nombres de volúmenes del mismo proyecto.

Patient espera PostgreSQL, Kafka y billing saludables; auth espera su PostgreSQL;
analytics espera Kafka; gateway espera patient y auth. Estos controles ordenan
el arranque; no implementan reintentos ni recuperación ante caídas posteriores.

Kafka ahora utiliza `KAFKA_LOG_DIRS=/var/lib/kafka/data`, dentro del volumen ya
declarado. La configuración previa no establecía este directorio: si un contenedor
antiguo tiene eventos fuera del volumen, preservarlo y copiar esos datos antes de
recrearlo. La prueba de R04 utiliza volúmenes nuevos; no migra datos antiguos.
Los volúmenes PostgreSQL mantienen sus rutas originales.

El smoke fija proyecto `pm-roadmap-test` y puertos 14xxx/19xxx/15xxx. Crea datos
sintéticos, comprueba alta, consumo, `restart` y `down/up`, y finalmente ejecuta
`down` sin eliminar volúmenes, también ante errores. No usa ni borra los volúmenes
del proyecto habitual ni toca LocalStack. `-SkipBuild` sirve para repetir las
comprobaciones con las imágenes de prueba ya construidas.

## Dependencias y límites

Se conservan Spring Boot 4.1.1, Spring Cloud 2025.1.3, gRPC 1.83.1,
net.devh 3.1.0.RELEASE y springdoc 2.6.0 después de verificaciones locales.
Se alineó `protoc` de patient/billing de 3.25.5 a 4.29.1, igual que su runtime
explícito y que analytics. No se modificaron los `.proto`.
La propiedad `protobuf-java.version` fija también el BOM transitivo: se comprobó
que `protobuf-java-util` de billing resolvía 4.35.1 antes y ahora resuelve 4.29.1,
igual que el runtime y el generador.
Se eliminó de analytics el generador gRPC 1.68.1 y su goal `compile-custom`: el
contrato sólo define mensajes y no necesita stubs ni runtime gRPC propio.

Nuevas dependencias: H2 con scope test en patient para aislamiento;
Actuator para healthchecks de las aplicaciones; starter-webclient en gateway
porque su contexto falló por ausencia de `WebClient.Builder` en Boot 4.
Failsafe 3.5.6 separa contextos y tests externos de Surefire 3.5.6.

La [matriz oficial de springdoc](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)
recomienda la serie 3.x para Boot 4. Aquí se conserva 2.6.0 porque el endpoint
existente pasó la prueba concreta; no se afirma compatibilidad con todas sus
funciones. Una actualización más amplia debe evaluarse por separado.
La [documentación de net.devh](https://grpc-ecosystem.github.io/grpc-spring/en/versions.html)
también requiere comprobar las combinaciones de dependencias; el alcance local
es arranque y RPC básicos. No se adoptaron cambios arquitectónicos de R07–R19.

## Verificación R11–R19

Usar `scripts/verify-system.ps1` para la aceptación integral actual: crea un proyecto
nuevo con diez servicios y cinco volúmenes propios, verifica el flujo, fallos y
reinicios y detiene contenedores sin borrar volúmenes. Los antiguos reportes R04–R09
conservan sus alcances históricos; el resultado de analytics ya no se verifica
buscando datos personales en logs.

`verify-recovery-postgres.ps1` usa PostgreSQL efímero para el registro durable y
analytics. `synth.ps1`, `verify-localstack.ps1` y `build-release.ps1 -Version ...`
preparan infraestructura e imágenes locales sin publicar/desplegar AWS. Detalles
y límites en recovery-operations.md y aws-deployment.md.
