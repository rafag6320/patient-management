# Decisiones y propuestas

Los documentos previos estaban vacíos.
"Observada" significa verificable en el código; no implica conocer
la motivación histórica ni recomendar conservar todos sus detalles.

## Decisiones observadas

| ID | Estado | Decisión observable |
|---|---|---|
| D01 | Observada | Aplicaciones Spring Boot separadas por responsabilidad. |
| D02 | Observada | Bases independientes para patient y auth. |
| D03 | Observada | Gateway HTTP como punto de entrada previsto. |
| D04 | Observada | Validación JWT delegada por HTTP a auth. |
| D05 | Observada | Comunicación patient -> billing mediante gRPC. |
| D06 | Observada | Eventos de alta mediante Kafka y Protobuf. |
| D07 | Observada | Hibernate update y SQL inicial, sin migraciones. |
| D08 | Observada | Docker Compose para el entorno local. |
| D09 | Observada | CDK Java con intención de usar ECS, RDS y MSK. |

## Decisiones implementadas

El pedido del usuario de implementar R01–R04 autoriza estas elecciones locales
dentro de su alcance. No modifica decisiones abiertas de negocio ni autoriza
despliegues externos.

### D10 — Cadena de herramientas local (P01)

- Estado: implementada en R01–R02.
- Contexto: consola Java 21 y Maven Java 25; wrappers Maven distintos.
- Alternativas: modificar la configuración global o seleccionar el JDK por proceso.
- Elección: Java 21 y Maven Wrapper 3.9.16, selección por script y validación
  Enforcer en las cinco aplicaciones; salida CLI separada del IDE.
- Consecuencias: no cambia JAVA_HOME global; el primer uso requiere Maven Central.
  Se conservan Boot 4.1.1 y Cloud 2025.1.3. Protoc y runtimes Protobuf se alinean
  en 4.29.1; analytics elimina el generador gRPC porque sólo define mensajes.
- Compatibilidad: sin cambios HTTP ni Protobuf. Gateway añade starter-webclient
  tras detectar un fallo de arranque por ausencia de WebClient.Builder.
- Evidencia y validación: compilación limpia, tests gRPC/OpenAPI y construcción
  de imágenes; comandos y resultados en roadmap.md y local-development.md.

### D11 — Pruebas explícitas y aisladas (P08)

- Estado: implementada en R03.
- Contexto: tests usaban configuración de desarrollo; el mapper levantaba Spring;
  las pruebas HTTP podían contactar localhost sin preparación.
- Alternativas: depender de servicios del desarrollador o preparar entornos aislados.
- Elección: unitarios puros por defecto; contextos optativos con H2 en memoria,
  mocks de las integraciones de patient, listeners detenidos y puerto gRPC
  aleatorio; Compose separado para PostgreSQL/Kafka reales. HTTP heredado
  optativo, sin destino implícito.
- Consecuencias: H2 no prueba semántica PostgreSQL; el smoke no demuestra
  recuperación distribuida ni cubre todos los escenarios de R15.
- Compatibilidad: sin cambios en contratos o credenciales existentes.
- Evidencia y validación: Surefire, Failsafe y smoke registrados en roadmap.md.

### D12 — Disponibilidad de Compose (P09)

- Estado: implementada en R04.
- Contexto: dependencias sin comprobación de disponibilidad.
- Alternativas: pausas fijas o healthchecks.
- Elección: healthchecks PostgreSQL, Kafka y aplicaciones; depends_on con
  service_healthy; probe Java sin curl en las imágenes JRE. Directorio de logs
  Kafka dentro del volumen; nombres de contenedores por proyecto.
- Consecuencias: espera acotada de arranque; sin reintentos de negocio. Antes de
  recrear contenedores Kafka antiguos se deben revisar datos fuera del volumen.
  No se ejecutan migraciones ni borrados sobre los datos del desarrollador.
- Compatibilidad: conserva puertos predeterminados, DNS de servicios y rutas
  de volúmenes PostgreSQL. Los puertos host admiten configuración para pruebas.
- Evidencia y validación: smoke con volúmenes propios, reinicios y recreación;
  resultados en roadmap.md.

### D13 — Enrutamiento común y fallos acotados de auth (R05)

- Estado: implementada por el pedido del usuario de resolver R05 (30/10/2026).
- Contexto: prod enviaba `/auth/login` a un servicio que expone `/login`;
  proxy y validación podían apuntar a destinos diferentes. No había un límite
  de validación ni tratamiento controlado de errores de conexión.
- Alternativas: duplicar y sincronizar rutas por perfil, o heredar una definición
  común y variar únicamente destinos. Se elige la segunda.
- Elección: `/api/auth/**` elimina dos segmentos y `/api/patients/**` elimina uno
  en ambos perfiles. Una URL de auth sirve para proxy y validación. Se conservan
  los destinos predeterminados de prod y se permiten variables de configuración.
- Fallos: se conserva 401 para rechazo del token y 502 para fallos de validación
  remota; se extiende este último a conexión fallida y timeout. No se introduce
  un código de error adicional. Auth trata JWT vacíos como inválidos (401),
  evitando una excepción no controlada. Sus errores de formato de encabezado
  siguen devolviendo 400 directamente.
- Límites: 2s por defecto para validación JWT y para el contacto del proxy con
  auth; configurables y estrictamente positivos. No se añaden reintentos ni se
  limita la llamada posterior a patient con el timeout de auth. El proxy conserva
  respuestas HTTP de auth, incluido 401 de login. Si una respuesta ya fue enviada,
  no intenta reescribir su código ante un fallo posterior.
- Consecuencias: auth caído no habilita acceso a pacientes; se puede diagnosticar
  el fallo con un 502 en lugar de esperar indefinidamente. No se cambia la política
  de roles ni la semántica del alta de pacientes.
- Evidencia: tests unitarios y HTTP con puertos aleatorios en perfiles principal
  y prod, auth con H2 y prueba de Compose aislada. Comandos, resultados y límites
  en [verification-r05.md](verification-r05.md).

### D14 — Validación y unicidad de pacientes (R06)

- Estado: implementada y verificada el 30/10/2026.
- Contexto: las conversiones de fecha/DNI podían lanzar excepciones; sólo se
  consultaba el email antes de guardar, sin traducir carreras de unicidad.
- Elección: validadores Bean Validation en DTO y comprobaciones de DNI en el
  servicio. Se conserva el rango no negativo representable por el Integer actual,
  sin imponer longitudes nacionales, mayoría de edad ni fechas pasadas.
- Las fechas deben existir y usar yyyy-MM-dd. registeredDate conserva su grupo
  obligatorio sólo en alta; en actualización sigue sin modificar la fecha guardada.
- La base decide los conflictos concurrentes: saveAndFlush confirma la escritura
  antes de efectos remotos y sólo SQLSTATE 23505 se traduce a duplicado.
  Alternativas descartadas: confiar sólo en preconsultas o convertir cualquier
  excepción de integridad en duplicado, porque ocultan carreras u otros defectos.
- Compatibilidad: mismos endpoints, campos y códigos existentes (400 para datos
  inválidos/duplicados, 404 para inexistentes, 200 en alta/actualización y 204 al
  eliminar). No se introduce 409 ni un nuevo formato de error, por lo que no se
  requiere una modificación de contrato público.
- No se envuelve PostgreSQL, gRPC y Kafka en una supuesta transacción común;
  siguen pendientes la recuperación de altas y las migraciones de R07–R12.
- Validación: CRUD HTTP con H2 y PostgreSQL efímero, concurrencia de alta y
  actualización, y tests que fuerzan SQL 23505 tras preconsultas exitosas.
  Evidencia en [verification-r06.md](verification-r06.md).

### D15 — Migraciones por servicio y adopción manual (R07/P02)

- Aprobación explícita del usuario el 30/10/2026: Flyway y estrategia sin baseline
  automático, borrado ni intervención en bases del desarrollador durante la tarea.
- Contexto: Hibernate update no proporciona historial reproducible y debe
  reemplazarse preservando los datos de bases existentes.
- Alternativas: Flyway SQL, Liquibase con changelogs o seguir con actualización
  implícita. Se eligió Flyway 12.4.0 gestionado por Boot 4.1.1, mediante su starter
  y módulo PostgreSQL. El plugin Maven de igual versión permite mantenimiento
  explícito; es la única dependencia funcional nueva de esta entrega.
- Cada servicio conserva V1 y su historial en su propia base. Hibernate valida
  después de migrar. V1 no modifica datos ni credenciales; auth conserva su seed
  condicional actual, cuya separación por entorno queda en R08.
- Bases nuevas: V1 crea el esquema. Bases existentes: backup restaurable, revisión
  de una copia y preflight de columnas/restricciones antes del baseline manual 1.
  No se presume que un historial baseline demuestra compatibilidad del esquema.
- baseline-on-migrate permanece false; clean permanece deshabilitado. Se rechazan
  bases pobladas sin historial, evitando adoptarlas al arrancar por accidente.
- Las migraciones aplicadas son inmutables. Fallos transaccionales se inspeccionan
  y recuperan en copia; versiones aplicadas se corrigen con nuevas migraciones.
- Consecuencia operativa: los volúmenes anteriores requieren adopción antes de
  iniciar la versión nueva. No se ejecutó esa adopción sobre bases personales.
- H2 conserva create-drop y Flyway explícitamente desactivado para tests rápidos;
  las migraciones y el arranque validate se comprueban en PostgreSQL 16 efímero.
- Procedimiento y límites: [migrations.md](migrations.md). Evidencia y comandos:
  [verification-r07.md](verification-r07.md).

### D16 — Configuración explícita y seed de desarrollo (R08)

- Estado: implementada por el pedido de R08 (30/10/2026).
- Contexto: auth podía seleccionar H2 automáticamente y ejecutaba el seed en
  cualquier entorno. Los valores de conexiones locales estaban en configuración
  común y los perfiles del gateway contenían destinos de desarrollo implícitos.
- Alternativas: conservar fallbacks globales o concentrarlos en perfiles explícitos.
  Se elige `local` para procesos del host, `docker` para Compose de desarrollo y
  `prod`/sin perfil con configuración externa obligatoria. Las rutas no cambian.
- El SQL original se mueve sin modificar su contenido a db/dev/data.sql. Sólo
  local/docker y fixtures de test lo habilitan. En otros entornos se rechaza
  activar spring.sql.init. No se eliminan ni se alteran cuentas existentes.
- H2 pasa a scope test en auth; se retira la consola H2 de patient, que ya tiene
  driver H2 de test explícito. Los artefactos ejecutables no contienen H2.
- Cada aplicación valida sus propias claves mediante BeanFactoryPostProcessor,
  antes de construir datasource/Flyway/clientes. No se introduce un módulo común
  ni dependencias nuevas. Los mensajes del guard contienen sólo nombres de claves.
- Se rechaza prod mezclado con local/docker, y también con test en módulos con
  base de datos. Los tests de rutas gateway conservan test/prod con upstreams
  locales. Test mantiene configuración aislada explícita y no va empaquetada.
- Consecuencia: fuera de desarrollo el arranque requiere conexiones y secretos
  explícitos. Los valores existentes se conservan dentro del perfil adecuado;
  no hubo rotación ni modificación de contraseñas, hashes o clave JWT existentes.
- Validación: unitarios de las cinco aplicaciones, arranques PostgreSQL en
  prod/local/docker, inspección de JAR y Compose aislado. Procedimiento en
  [environments.md](environments.md); evidencia en [verification-r08.md](verification-r08.md).

### D17 — Cuenta billing persistente e idempotente (R09/P03)

- Estado: alcance aprobado expresamente por el usuario e implementación
  verificada el 30/10/2026.
- Elección: PostgreSQL propio billing_db, JPA y Flyway como en los servicios
  existentes. Alternativas no elegidas: continuar simulando, usar almacenamiento
  volátil o acceder al esquema de patient. No hay acceso cruzado ni FK a otra base.
- Cuenta mínima: ID UUID, patientId opaco de hasta 255 caracteres, nombre hasta
  100 y email válido hasta 255. Los tres campos de entrada son obligatorios.
  No se agregan importes, facturas, pagos ni cambios Protobuf.
- patientId tiene una restricción única. Repetir un request válido devuelve el
  mismo ID y no sobrescribe datos iniciales aunque cambien nombre/email. No se
  normaliza el identificador; la identidad corresponde al string recibido.
- La preconsulta permite recuperar una cuenta existente; ante una carrera, la
  transacción de inserción perdedora revierte y se vuelve a leer al ganador.
  Sólo se recupera de SQLSTATE 23505 si existe la cuenta de ese patientId; otras
  restricciones o fallos no se convierten en éxito.
- El adaptador gRPC devuelve success=true sólo tras recibir la cuenta persistida.
  Validación inválida usa INVALID_ARGUMENT; fallos de recursos/transacciones
  transitorios usan UNAVAILABLE; errores restantes usan INTERNAL, sin detalles
  del almacenamiento en la respuesta. No se modifican ambas copias del contrato.
- Compose añade billing-db con volumen propio y healthcheck. Fuera de desarrollo
  la configuración exige datasource PostgreSQL explícito; H2 sólo existe en test.
- Límites: la cuenta conserva un snapshot inicial, no sincroniza actualizaciones
  ni eliminaciones de patient y no crea cuentas retroactivamente. R10 define luego
  la semántica de coordinación (D18); la implementación sigue en R11–R12.
- Evidencia y comandos en [verification-r09.md](verification-r09.md).

## Decisiones aprobadas pendientes de implementación

### D18 — Semántica del alta y recuperación (R10/P04)

- Estado: propuesta completa aprobada expresamente por el usuario el 30/10/2026;
  definición de R10 completada e implementación local en R11–R12. La aprobación
  se distingue de la evidencia nueva de ejecución en verification-r11-r19.md.
- Contexto: el paciente se confirma antes de billing/Kafka sin conservar avance;
  perder la respuesta produce resultados inciertos y POST repetidos con error de
  duplicado. Billing ya garantiza una cuenta por patientId (D17).
- Elección: 200 con el DTO actual sólo tras confirmar paciente, cuenta billing
  válida e intención durable de evento. No esperar el ack Kafka ni analytics;
  entregar después, conservando y recuperando el trabajo. Registrar operación
  y paciente en una transacción local antes de efectos remotos, sin SQL abierto
  durante llamadas de red y sin borrados compensatorios automáticos.
- Recuperación: estados pendientes/inciertos/confirmados y revisión operativa;
  repetir billing siempre con el mismo patientId/snapshot. Reintentos con límites,
  espera creciente y recuperación tras reinicio. Kafka admite redelivery y R13
  deberá evitar duplicar efectos usando la identidad lógica del evento.
- Contrato aprobado: encabezado opcional Idempotency-Key para recuperar la misma
  operación y respuesta inicial. Errores 503 para alta pendiente/incierta o fallos
  de disponibilidad, 502 para rechazo/respuesta billing inválida, 409 para clave
  incompatible/consumida y PUT/DELETE mientras billing no esté confirmado. Se
  mantienen los DTO, rutas, Protobuf y el 400 de duplicados sin clave o con otra.
- Alternativas evaluadas: esperar además Kafka, alta asíncrona 202, conservar el
  flujo sin recuperación, borrar como compensación y deduplicar por email/DNI.
  Comparación, matriz de fallos, reglas de claves y estados detallados en
  [patient-registration-semantics.md](patient-registration-semantics.md), que forma
  parte de esta decisión aprobada.
- Consecuencias: el 200 no implica entrega Kafka; un 503 puede dejar un paciente
  pendiente visible en GET. Sin clave no se garantiza recuperar el resultado por
  POST. No hay atomicidad distribuida ni promesa de procesamiento exactamente una
  vez. Snapshots y claves requieren retención segura antes de implementarse.
- Patrón: R10 fijó las garantías; R12 aprobó después el mecanismo concreto (D19).
  No se reconcilian automáticamente pacientes históricos.
- Validación: inspección estática y revisión documental en R10, registradas en
  [verification-r10.md](verification-r10.md). Los escenarios SQL/RPC/Kafka/HTTP,
  concurrencia y reinicios definidos en la especificación se probarán en R11–R13.

### D19 — Registro de operación y outbox local (R12/P05)

- Patrón aprobado expresamente por el usuario el 30/10/2026, antes de implementarlo.
- Paciente y registration_work se confirman juntos mediante JPA/JDBC y el mismo
  datasource/transaction manager. V2 añade el registro; V3 añade observabilidad,
  preservando las migraciones anteriores. No hay accesos a otras bases.
- La intención inicial no se habilita para publicar hasta confirmar billing.
  Dispatcher SQL con lease/token, efectos fuera de SQL y avances condicionados
  al propietario. Un reinicio permite recuperar trabajo y repetir la RPC idempotente.
- Reintentos con backoff/jitter; fallos inciertos acotados antes de revisión.
  Sin dependencia de una nueva librería ni compensación por borrado.
- Claves opcionales se guardan como SHA-256 y se comparan contra fingerprint de
  la entrada lógica. Tombstones preservan claves consumidas. Snapshots se retienen,
  no hay purga automática. Reanudación en recovery-operations.md.
- Validación: transacciones/carreras/fencing con H2 y PostgreSQL, Kafka incierto,
  fallos SQL y prueba real de cuenta creada con respuesta descartada y proceso
  patient detenido. No se promete exactly-once ni reconciliación histórica.

### D20 — Total histórico y deduplicación de analytics (R13/R14)

- Resultado y PostgreSQL propio aprobados expresamente por el usuario el 30/10/2026.
- patientsCreated cuenta PATIENT_CREATED distintos por patientId. JDBC/Flyway,
  inserción única antes del commit de offset; no se guarda nombre/email.
- GET /analytics/summary y su ruta GET /api/analytics/summary; DTO {patientsCreated}.
  No se agregan eventos PUT/DELETE ni se decrementa el total histórico.
- Identidad lógica (eventType,patientId), clave Kafka patientId y Protobuf intactos.
  Comparación automática de ambas copias de contratos desde verify.ps1.
- Mensajes inválidos tienen cuarentena durable con metadatos/hash y sin payload;
  fallos SQL se propagan para redelivery. Registro y efecto de conteo corresponden
  a una misma fila: no hay contador separado susceptible a incremento duplicado.
- Patient usa spring-boot-starter-kafka para aplicar realmente propiedades del
  productor, sin la fábrica manual que descartaba límites y TLS/SCRAM. La
  configuración efectiva local/AWS y el contexto completo tienen tests específicos.
- Dependencias nuevas JDBC/Flyway/PostgreSQL gestionadas por Boot y H2 sólo test.
  Base/volumen analytics de desarrollo propios, sin tocar cuentas existentes.
- Validación: concurrencia y fallos SQL con H2/PostgreSQL, Kafka real con duplicados
  y mensaje inválido, persistencia después de recrear contenedores.

### D21 — Roles en gateway y correlación sin datos personales (R17)

- Política ADMIN/USER aprobada expresamente por el usuario el 30/10/2026.
- ADMIN: CRUD y analytics. USER: sólo lectura. Otros roles: 403. Auth verifica el
  JWT y comunica X-Validated-Roles al gateway; encabezados del cliente se eliminan.
  Se mantienen login, JWT, DTO y 401/502 de auth. No se cambian cuentas/credenciales.
- CorrelationId validado/generado por HTTP y propagado a RPC, registro durable y
  Kafka. Métricas agregadas y endpoints operativos internos; sin snapshots/tokens.
- Gateway limita DNS a 5s y transforma fallos/timeout de upstream sin respuesta
  comprometida en 503. No repite POST; un resultado puede seguir siendo incierto.
- Los servicios internos requieren red privada: no se añade una segunda política
  JWT ni mTLS en esta entrega. Tests de roles y flujo real cubren el gateway.

### D22 — Preparación reproducible de infraestructura (R18/R19)

- Estado: candidato preparado y revisado localmente por el pedido de trabajar
  R18/R19; **no adoptado ni desplegado en una cuenta AWS**.
- Se conservan Java 21/CDK 2.178.1 y se separan ECR, candidato AWS y emulación.
  Se elimina SDK AWS v1 sin uso; JUnit/exec-maven-plugin sólo para verificación.
- Candidato: cuatro RDS, MSK TLS/SCRAM, ECS privado con Cloud Map, secretos en
  ejecución, digests ECR y ALB HTTPS; backups, retención y protección persistente.
- LocalStack 4.14.0 se limita a CloudFormation/Secrets Manager ficticios en un
  contenedor propio, preservando el LocalStack del desarrollador.
- Alternativas/limitaciones: no simular RDS/MSK/ECS sin evidencia; no trasladar
  direcciones Docker ni credenciales al cloud. Preparación AWS, TLS interno,
  ACL, permisos efectivos, cuotas y presupuesto se revisan antes de aprobación.
- Comandos, artefactos y fuentes oficiales en aws-deployment.md. Un template
  sintetizado no demuestra disponibilidad ni funcionamiento real en AWS.

## Propuestas pendientes de aprobación

| ID | Propuesta | Motivo y condición |
|---|---|---|
| P10 | Preparar configuración, red y secretos para AWS. | El stack actual contiene supuestos del entorno Docker local. |

## Decisiones abiertas
- ¿Qué política de retención/purga deben tener los snapshots durables?
- ¿Actualizar y eliminar pacientes requiere eventos?
- ¿Qué cuenta/región, presupuesto y parámetros se aprobarán para desplegar AWS?

## Formato de futuras decisiones
Cada decisión aprobada debe registrar:
contexto, alternativas, elección, consecuencias,
compatibilidad, evidencia y estrategia de validación.

La presencia de una propuesta en este archivo no autoriza
cambios de contratos, credenciales o despliegues.
