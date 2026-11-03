# Arquitectura actual

## Alcance y evidencia
Este documento describe la inspección del 28/10/2026 y los cambios de R05–R19
verificados localmente el 30/10/2026.
No certifica el funcionamiento del sistema desplegado.

## Organización
Existen cinco aplicaciones Spring Boot independientes:
patient-service, auth-service, billing-service,
analytics-service y api-gateway.

integration-tests e infrastructure son módulos auxiliares.
No existe un POM agregador raíz. Los scripts de la raíz ejecutan los módulos
con Maven Wrapper 3.9.16 y Java 21; `.verification/<módulo>` separa la salida
CLI de los builds del IDE.

## Plataforma declarada
Java 21 y Spring Boot 4.1.1.
Gateway utiliza Spring Cloud 2025.1.3 y WebFlux.
Los servicios HTTP utilizan Spring MVC donde está configurado.
Patient, auth y billing utilizan JPA y bases PostgreSQL propias; analytics tiene
JDBC y PostgreSQL propio para deduplicación y su resultado histórico.

## Responsabilidades
- Patient: listar, crear, actualizar y eliminar pacientes.
- Auth: autenticar usuarios y emitir/validar JWT.
- Billing: crear o recuperar una cuenta persistida mediante CreateBillingAccount
  gRPC. patientId es único y los reintentos devuelven el mismo ID UUID, sin
  sobrescribir el snapshot inicial de nombre/email.
- Analytics: consumir PatientEvent, deduplicar PATIENT_CREATED, conservar un total
  histórico de altas y poner mensajes inválidos en cuarentena sin payload personal.
- Gateway: enrutar HTTP y consultar a auth para validar JWT.

## Contratos HTTP actuales

El inventario completo, ejemplos, seguridad y errores están en
[swagger.md](swagger.md). El gateway sirve Swagger UI en `/swagger-ui.html`,
el contrato público en `/v3/api-docs` y cinco catálogos en `/openapi/*.json`.
La documentación no agrega proxies ni exposición de puertos internos.

Patient:
- GET /patients: 200 con una lista.
- POST /patients: 200 con el paciente creado.
- PUT /patients/{id}: 200 con el paciente actualizado.
- DELETE /patients/{id}: 204.

Analytics:
- GET /analytics/summary: 200 con patientsCreated (total histórico).
- Gateway expone GET /api/analytics/summary con validación JWT y rol.

Auth:
- POST /login: 200 con token o 401.
- GET /validate: valida el encabezado Authorization.

Gateway, configuración principal y perfil prod:
- /api/patients/** elimina el segmento /api.
- /api/auth/** elimina los segmentos /api/auth.

Ambos perfiles heredan las mismas rutas. La dirección de auth se comparte entre
el proxy y `/validate`; los destinos se configuran mediante AUTH_SERVICE_ADDRESS,
PATIENT_SERVICE_ADDRESS y ANALYTICS_SERVICE_ADDRESS. Prod requiere destinos explícitos; local/docker
proporcionan únicamente los valores de desarrollo de sus entornos.
La validación JWT y el contacto del proxy con auth tienen límites configurables
de 2 segundos. Un token rechazado devuelve 401; un fallo de transporte o timeout
de auth devuelve 502 antes de enviar la respuesta. El timeout de validación no
incluye el procesamiento posterior de patient.

## Comunicación
- Gateway -> auth: HTTP GET /validate.
- Gateway -> patient y auth: proxy HTTP.
- Patient -> billing: gRPC CreateBillingAccount.
- Patient -> Kafka: PatientEvent Protobuf, topic patient-events.
- Kafka -> analytics: consumer group analytics-service.

Los contratos Protobuf están duplicados entre módulos y se comparan automáticamente.
Patient utiliza la fábrica Kafka autoconfigurada por Spring Boot para aplicar
realmente los límites, acks y TLS/SCRAM declarados en sus propiedades.

## Validación y conflictos de pacientes (R06)

El DTO valida fechas de calendario con formato yyyy-MM-dd y DNI compuesto por
dígitos dentro del rango no negativo de Integer (0 a 2147483647), conservando
su almacenamiento actual. registeredDate sigue siendo obligatorio sólo en alta.
Los errores de validación usan el mapa por campo existente y HTTP 400.
El servicio comprueba email y DNI, y la base conserva la garantía de unicidad
ante concurrencia. Una violación SQL 23505 se traduce al formato de duplicado
existente (`error`, HTTP 400). Las demás violaciones de integridad no se ocultan
como duplicados. La coordinación y recuperación del alta se describen abajo;
las validaciones y constraints por sí solas no crean atomicidad distribuida.

## Persistencia
- Patient es propietario de patient_db en Compose.
- Auth es propietario de auth_db en Compose.
- Billing es propietario de billing_db; analytics de analytics_db. Ambos tienen
  volúmenes propios en Compose.
- No existe acceso cruzado entre bases en el código inspeccionado.
- Patient, auth, billing y analytics utilizan Flyway 12.4.0 por servicio. JPA
  permanece en validate; analytics usa JDBC y no genera esquemas implícitamente.
- Auth ejecuta el seed SQL condicional sólo con local/docker; test lo habilita
  como fixture. Sin perfil y en prod no se ejecuta. El SQL se conserva sin cambios
  de contenido en db/dev/data.sql y no modifica cuentas existentes.
- Cada servicio tiene V1 SQL con su esquema actual. El baseline de bases
  existentes es manual, después de backup y preflight; no se habilita baseline
  automático ni clean. Procedimiento en [migrations.md](migrations.md).

## Consistencia del alta

R11–R12 implementan D18/D19: una transacción local guarda paciente y registro
registration_work con snapshot inicial, hashes de idempotencia, estado billing e
intención de evento. La RPC se ejecuta fuera de SQL, con deadline configurable de
2s y sin reintentos dentro de la solicitud. Se comprueban success e ID UUID.

El 200 conserva el DTO y requiere cuenta billing confirmada y trabajo durable,
no entrega Kafka. Errores temporales/inciertos usan 503 y revisión 502; el paciente
puede quedar visible. Idempotency-Key opcional recupera el mismo ID/snapshot;
incompatibilidad, clave eliminada o PUT/DELETE con billing pendiente usan 409.
Sin clave se conserva el 400 de duplicados. Los pacientes históricos no se
reconcilian automáticamente y no se modificaron sus datos ni V1.

Un dispatcher reclama trabajo con lease de 45s y token, limita concurrencia por
instancia y confirma avances condicionados al propietario. Tras fallos/reinicio,
repite la RPC con el mismo patientId y publica sólo tras confirmar billing.
Kafka usa clave patientId y snapshot inmutable; ack incierto permite redelivery.
R13–R14 deduplican el efecto en PostgreSQL de analytics por (eventType,patientId)
antes de confirmar el offset. No existe atomicidad distribuida ni procesamiento
exactamente una vez. Revisión operativa y límites en [recovery-operations.md](recovery-operations.md).

## Seguridad
Auth verifica passwords con BCrypt y firma JWT HS256.
Gateway delega su validación a auth.
Auth rechaza JWT vacíos, malformados, expirados o con firma inválida. El gateway
rechaza encabezados Bearer ausentes o vacíos sin contactar al backend.
D21 aplica permisos en gateway: ADMIN tiene CRUD y analytics; USER sólo lectura.
Auth comunica roles verificados en la respuesta de /validate; el gateway ignora
encabezados de rol del cliente. Los servicios internos deben permanecer privados.
Los valores de desarrollo están en perfiles local/docker. H2 es dependencia
exclusiva de test en patient/auth/billing/analytics y no se incluye en los JAR ejecutables.
Las cinco aplicaciones validan su configuración antes de crear conexiones o
clientes. Detalles y variables en [environments.md](environments.md).

## Infraestructura local
Compose define cinco aplicaciones, cuatro PostgreSQL y Kafka KRaft.
Existen cinco volúmenes independientes para las cuatro bases y Kafka.
Las cinco aplicaciones tienen healthchecks HTTP de Actuator. Billing comprueba
también su puerto TCP gRPC; las pruebas verifican la RPC.
Las cuatro bases utilizan pg_isready y Kafka utiliza kafka-topics.
Patient sólo requiere su propia base para arrancar y recuperar trabajo aunque
billing/Kafka estén caídos. Auth espera auth-db; analytics espera analytics-db y
Kafka; gateway espera patient y auth. Las apps limitan RAM/CPU en Compose.
Billing espera a billing-db saludable antes de inicializar Flyway/JPA y gRPC.
Los nombres de contenedores dependen del proyecto Compose.
Kafka escribe en /var/lib/kafka/data dentro de su volumen nombrado.
La guía de ejecución y las precauciones para contenedores antiguos están en
[local-development.md](local-development.md).

## Infraestructura CDK

R18 genera tres templates: ECR, candidato AWS y emulación acotada. El candidato
prepara VPC, cuatro RDS privadas, MSK con TLS/SCRAM, ECS/Fargate/Cloud Map, secretos
por referencias y ALB HTTPS. Usa digests obligatorios, backups y protección de
bases. Tests de template y síntesis local no verifican despliegue efectivo.

LocalStack 4.14.0 se prueba en un contenedor nuevo sólo con CloudFormation y un
secreto ficticio. No se afirma emulación de RDS/MSK/ECS. R19 prepara imágenes y
procedimiento de revisión/publicación/migración; todo despliegue externo queda
pendiente de aprobación y de completar entradas del entorno.
Ver [aws-deployment.md](aws-deployment.md).

## Observabilidad y límites conocidos

Se propaga correlationId por HTTP, gRPC y Kafka. Patient expone cantidades de
trabajo pendiente, confirmado, recuperado y en revisión; analytics expone total,
cuarentena y contadores. No se registran tokens, snapshots ni payloads personales.
La autorización sólo protege gateway; no hay mTLS HTTP/gRPC interno. No se
sincronizan actualizaciones/eliminaciones con billing ni analytics. La retención
física de snapshots requiere una política posterior antes de cualquier purga.

El workflow CI se ejecutó y aprobó en GitHub para d197f7b; sus reportes se conservan
como artefacto. AWS continúa siendo un candidato sin desplegar. Evidencia en
[verification-r11-r19.md](verification-r11-r19.md).

## Pruebas locales

Surefire ejecuta tests unitarios sin contexto Spring. El perfil Maven
`isolated-tests` utiliza Failsafe para los contextos `*IT`: patient y auth tienen
H2 en memoria y configuración de prueba explícita; patient sustituye billing y
el productor Kafka; analytics no inicia listeners; billing usa un puerto gRPC
aleatorio; analytics tiene H2 explícito y listeners detenidos. Gateway carga su contexto y prueba HTTP contra upstreams controlados
en loopback y puertos aleatorios, en perfiles principal y prod.
El módulo integration-tests excluye los tests HTTP por defecto y requiere el
perfil `external-tests` y destinos explícitos para ejecutarlos.
La aceptación integral actual es verify-system.ps1 y complementa los contextos
con PostgreSQL, Kafka y reinicios reales. La demo de portfolio usa un proyecto propio;
ver [demo.md](demo.md). La preparación funcional de demo/aceptación comprueba login
y lectura por gateway antes de iniciar aserciones estrictas, además de los healthchecks.
Los reportes R01–R19 son evidencia histórica fechada.
