# Perfiles y configuración actuales

## Perfiles

| Perfil | Destinos predeterminados | Seed de auth | Persistencia |
| --- | --- | --- | --- |
| Sin perfil | Variables explícitas, sin valores de desarrollo | Desactivado | PostgreSQL + Flyway + validate |
| `prod` | Variables explícitas, sin host.docker.internal implícito | Desactivado y no se permite forzarlo | PostgreSQL + Flyway + validate |
| `local` | Servicios ejecutados en el host | SQL de desarrollo condicional | PostgreSQL + Flyway + validate |
| `docker` | DNS de servicios de Compose | SQL de desarrollo condicional | PostgreSQL + Flyway + validate |
| `test` | Propiedades explícitas en src/test/resources | Fixture de pruebas | H2 create-drop o PostgreSQL efímero explícito |

Compose activa `docker` en las cinco aplicaciones. Es un entorno de desarrollo,
no una configuración de despliegue productivo. No combinar `prod` con `local` o
`docker`. Auth, patient, billing y analytics tampoco permiten combinar `prod` y `test`.
Gateway conserva la combinación test/prod únicamente para sus pruebas de rutas
con upstreams en loopback; no contiene H2 ni seed.

Sin perfil y con `prod`, un valor ausente o inválido impide el arranque. El
verificador se ejecuta en la fase BeanFactoryPostProcessor, antes de crear
datasource, Flyway y clientes de red. Los errores del verificador enumeran nombres
de propiedades, no sus valores. Esto valida la forma de la configuración, no la
disponibilidad de redes ni la validez efectiva de credenciales.

## Variables requeridas fuera de desarrollo

| Aplicación | Variables / propiedades requeridas |
| --- | --- |
| auth | SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD, JWT_SECRET |
| patient | SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD, BILLING_SERVICE_ADDRESS, BILLING_SERVICE_PORT, SPRING_KAFKA_BOOTSTRAP_SERVERS (o KAFKA_BOOTSTRAP_SERVERS) |
| analytics | SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD, SPRING_KAFKA_BOOTSTRAP_SERVERS (o KAFKA_BOOTSTRAP_SERVERS) |
| gateway | AUTH_SERVICE_ADDRESS, PATIENT_SERVICE_ADDRESS, ANALYTICS_SERVICE_ADDRESS |
| billing | SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD; SERVER_PORT y GRPC_SERVER_PORT conservan valores comunes 4001 y 9001 |

Se aceptan también las propiedades equivalentes de Spring por argumentos u otras
fuentes de configuración. Las URLs de datasource deben ser PostgreSQL con host
y nombre de base explícitos; el verificador actual contempla una URL de host
único. Las credenciales deben estar presentes y el JWT debe tener al menos 32
bytes UTF-8 para HS256. Los secretos se proporcionan mediante el mecanismo del
entorno; no guardarlos en archivos versionados.

Los destinos HTTP del gateway son orígenes http/https con host, sin credenciales,
query string ni fragmento. Los brokers Kafka son una lista host:puerto separada
por comas. Se validan puertos y los timeouts positivos de auth.
SERVER_PORT acepta 0 para asignación dinámica; los puertos de conexión y el
servidor gRPC deben estar entre 1 y 65535 y billing no comparte su puerto HTTP
con el gRPC. Los tests gRPC en puerto 0 utilizan el perfil test explícito.

AUTH_VALIDATION_TIMEOUT y AUTH_PROXY_TIMEOUT conservan su valor común `2s`.
No son secretos y no cambian entre perfiles salvo configuración explícita.

## Direcciones en local y Docker

| Conexión | Perfil local, desde el host | Perfil docker, dentro de la red |
| --- | --- | --- |
| Patient PostgreSQL | localhost:5433/patient_db | patient-db:5432/patient_db |
| Auth PostgreSQL | localhost:5434/auth_db | auth-db:5432/auth_db |
| Billing PostgreSQL | localhost:5435/billing_db | billing-db:5432/billing_db |
| Analytics PostgreSQL | localhost:5436/analytics_db | analytics-db:5432/analytics_db |
| Kafka | localhost:9094 | kafka:9092 |
| Billing gRPC | localhost:9001 | billing-service:9001 |
| Gateway → auth | http://localhost:4005 | http://auth-service:4005 |
| Gateway → patient | http://localhost:4000 | http://patient-service:4000 |
| Gateway → analytics | http://localhost:4002 | http://analytics-service:4002 |

El perfil local presupone que esos servicios estén accesibles en el host. El
Compose principal no publica patient:4000 ni billing:9001: para combinar clientes
del host con esos contenedores se necesita una publicación de puertos explícita,
o ejecutar también esas aplicaciones en el host. Las variables permiten elegir
otros destinos sin modificar los archivos.

```powershell
# Compose de desarrollo (leer migrations.md antes de reutilizar bases anteriores).
docker compose up -d --build --wait --wait-timeout 300

# Ejecutar auth en el host con configuración local explícita.
.\scripts\maven.ps1 -Module auth-service spring-boot:run -Dspring-boot.run.profiles=local
```

Los perfiles local/docker contienen valores de desarrollo; no
deben usarse en producción. No se añaden archivos .env con secretos.

## Seed y H2

El SQL de desarrollo de auth está en `db/dev/data.sql`.
Sólo local/docker lo habilitan mediante spring.sql.init. Su condición sigue
evitando sobreescribir usuarios existentes. El perfil de pruebas lo carga como
fixture explícito. En prod/sin perfil se usa `spring.sql.init.mode=never`; el
verificador rechaza un intento de activarlo fuera de desarrollo.

No se eliminan administradores ni usuarios previamente creados. La separación
controla futuras ejecuciones del seed; cualquier intervención sobre cuentas
existentes necesita alcance y autorización propios.

H2 tiene scope test en auth, patient, billing y analytics.
Los JAR ejecutables no deben incluir el driver H2 ni consola. Además, los
datasources fuera de test deben tener URL PostgreSQL. Activar un nombre de perfil
no instala el driver ni incorpora los recursos de src/test al artefacto de producción.

## Pruebas

```powershell
.\scripts\verify.ps1 -WithContexts
.\scripts\verify-migrations-postgres.ps1
.\scripts\verify-auth-compose.ps1 -ProjectName pm-auth-check
.\scripts\compose-smoke.ps1 -ProjectName pm-smoke-check
```

Las pruebas de configuración comprueban claves ausentes, puertos/URLs/brokers
inválidos y que el guard se ejecute antes de instanciar una dependencia de red.
La suite PostgreSQL añade arranques reales de auth en prod/local/docker y prueba
que el seed no se ejecute en prod ni reemplace un hash/rol existente en desarrollo.
Los contenedores PostgreSQL de esa suite son efímeros y no usan volúmenes existentes.
La prueba Compose utiliza su proyecto aislado y conserva sus propios volúmenes.

## AWS, worker y recursos locales

En AWS se utiliza prod,aws. Patient/analytics requieren KAFKA_USERNAME y
KAFKA_PASSWORD seguros para el JAAS SCRAM; se inyectan desde Secrets Manager.
No combinar aws con local/docker/test. Auth/billing utilizan aws sólo para los
parámetros de conexión PostgreSQL, sin habilitar seed. El candidato valida
identidad TLS de RDS usando el bundle público incluido en la imagen.

BILLING_DEADLINE=2s (máximo 30s) acota la RPC. Variables del worker y semántica
idempotente en [recovery-operations.md](recovery-operations.md). Compose limita
cada aplicación a 640MiB y una CPU, con heap JVM al 65%; Kafka a 1GiB con heap de
512MiB. Es una configuración local, no un dimensionamiento de producción.

La aceptación integral usa puertos host 34000/34002/34004/34005, gRPC 39001,
PostgreSQL 35433–35436 y Kafka 39094, con proyecto y volúmenes propios. Comandos
vigentes: verify-system.ps1 y verify-recovery-postgres.ps1. Ninguno utiliza las
bases del desarrollador ni elimina sus volúmenes.
