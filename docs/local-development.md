# Desarrollo y verificación local

## Herramientas y checkout

Repositorio público: <https://github.com/rafag6320/patient-management>.
Las notas de cada release enlazan su commit y ejecución de CI.
Para ver el estado actual: `git remote -v`, `git rev-parse HEAD`, `git status --short`.

Para la demo Docker seguir [demo.md](demo.md): no necesita JDK en el host. Para los
comandos Maven se requieren PowerShell 7 y JDK **21**. No hay POM agregador; cada
módulo conserva su configuración. Los scripts usan Maven Wrapper **3.9.16** y
separan la salida CLI bajo `.verification` de los builds del IDE.

```powershell
# Si JAVA_HOME no apunta a un JDK 21, seleccionar su ruta local:
$env:JDK21_HOME = '<ruta-absoluta-a-jdk-21>'
./scripts/maven.ps1 -Module patient-service -version
./scripts/maven.ps1 -Module patient-service clean verify -Pisolated-tests
```

maven.ps1 selecciona Java sólo para su proceso y restaura JAVA_HOME/PATH.
Enforcer verifica Java 21 y Maven 3.9.16. En Linux definir JDK21_HOME o JAVA_HOME.
Los Dockerfiles usan Maven 3.9.16/Temurin 21 y runtime Temurin 21 JRE. Los tags
no fijan el parche/digest, por lo que no se garantiza reproducción bit a bit.

## Matriz de verificación vigente

| Grupo | Comando desde la raíz | Alcance |
| --- | --- | --- |
| Unitarios | `./scripts/verify.ps1` | Surefire; sin infraestructura externa |
| Contextos aislados | `./scripts/verify.ps1 -WithContexts` | Failsafe isolated-tests; H2 explícito, mocks/listeners detenidos y upstreams controlados |
| Migraciones/CRUD PostgreSQL | `./scripts/verify-migrations-postgres.ps1` | Bases efímeras; creación/adopción de copias, constraints y datos conservados |
| CRUD PostgreSQL específico | `./scripts/verify-patient-postgres.ps1` | Checks específicos de patient contra PostgreSQL real |
| Billing PostgreSQL | `./scripts/verify-billing-postgres.ps1` | RPC real, concurrencia, errores e idempotencia persistente |
| Recuperación PostgreSQL | `./scripts/verify-recovery-postgres.ps1` | Registro durable, fallos SQL y analytics |
| Sistema completo | `./scripts/verify-system.ps1` | Proyecto Compose aleatorio; HTTP, roles, RPC, Kafka, respuesta perdida y reinicios |
| Contratos | `./scripts/check-contracts.ps1` | Copias Protobuf idénticas |
| Documentación y scripts | `./scripts/check-docs.ps1` | Enlaces locales, JSON OpenAPI y sintaxis PowerShell |
| Preparación funcional | `./scripts/verify-readiness-probe.ps1` | Fixture Node local: 502 inicial recuperable, 401 rechazado y destino externo bloqueado |
| Infraestructura | `./scripts/synth.ps1` | Tests de templates y síntesis; no despliega |
| Emulación acotada | `./scripts/verify-localstack.ps1` | CloudFormation/Secrets Manager ficticios; ejecutar synth antes |
| Demo | `./scripts/demo.ps1 -Action Start`, luego Run y Stop | Entorno propio y sólo gateway publicado |

Los tests externos requieren external-tests y destinos explícitos; verify-system
los prepara con fixtures/puertos aislados. No invocar esos tests sobre instalaciones
habituales. H2 no demuestra compatibilidad PostgreSQL; las pruebas reales complementan
los contextos. El workflow de GitHub ejecuta los grupos completos y conserva reportes.

Antes de JUnit, verify-system comprueba login y lectura autenticada por gateway:
Actuator UP no garantiza que el primer recorrido BCrypt/JPA/proxy termine dentro
de sus límites. Esta preparación es acotada y sólo admite 502/503 transitorios;
los tests de negocio conservan sus aserciones estrictas. La demo utiliza la misma
preparación. No se alteran timeouts productivos para estabilizar fixtures.

El smoke anterior (`compose-smoke.ps1`) y verify-auth-compose siguen disponibles
para checks específicos, pero la aceptación integral actual es verify-system.ps1.

## Entorno de desarrollo habitual

Para una demo nueva usar el script dedicado. Los siguientes comandos corresponden
al Compose habitual y pueden reutilizar sus datos. Antes de arrancar bases anteriores
a Flyway seguir [migrations.md](migrations.md). No borrar datos para saltar ese paso.

```powershell
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 300
docker compose ps
docker compose stop
docker compose start --wait --wait-timeout 300
docker compose down
```

stop conserva contenedores y volúmenes; down retira contenedores/red conservando
volúmenes. No usar down -v, prune ni borrar volúmenes. Conservar el nombre del
proyecto para reutilizar los datos. Un contenedor Kafka antiguo puede tener datos
fuera del volumen si no configuraba KAFKA_LOG_DIRS: preservarlos antes de recrearlo.

| Servicio | Red Docker | Host habitual |
| --- | --- | --- |
| Gateway | api-gateway:4004 | localhost:4004 |
| Auth | auth-service:4005 | localhost:4005 |
| Patient | patient-service:4000 | Sólo a través del gateway |
| Billing | billing-service:9001 (gRPC), :4001 (health) | No publicado |
| Analytics | analytics-service:4002 | localhost:4002 |
| Patient DB | patient-db:5432/patient_db | localhost:5433 |
| Auth DB | auth-db:5432/auth_db | localhost:5434 |
| Billing DB | billing-db:5432/billing_db | localhost:5435 |
| Analytics DB | analytics-db:5432/analytics_db | localhost:5436 |
| Kafka | kafka:9092 | localhost:9094 |

Las variables de puertos del Compose permiten evitar colisiones. Desde el host
no se resuelven los DNS Docker. local presupone servicios accesibles por el host;
el Compose principal no publica patient ni billing. Para ejecutar esos clientes
en el host se necesita una configuración explícita compatible.

Patient arranca con su base y recupera trabajo aunque billing/Kafka estén caídos.
Auth/billing esperan sus bases; analytics espera su base y Kafka; gateway espera
auth/patient. Healthchecks de arranque no sustituyen recuperación de negocio.

## Perfiles, límites y versiones

Ver [environments.md](environments.md), [arquitectura](architecture.md) y los POM.
local/docker contienen valores de desarrollo; prod exige configuración externa.
H2 es test-only; Flyway administra los cuatro esquemas y JPA usa validate.

La documentación central de gateway usa springdoc WebFlux UI 3.2.0. Auth conserva
su dependencia existente 2.6.0, con verificación específica de sus endpoints;
no se afirma soporte de todas sus funciones. gRPC net.devh 3.1.0.RELEASE se conserva
con tests de transporte y de los escenarios usados. Consultar los POM de cada módulo antes de actualizar dependencias.

La preparación AWS y sus límites están en [aws-deployment.md](aws-deployment.md).
No ejecutar comandos de despliegue para verificar el entorno local.
