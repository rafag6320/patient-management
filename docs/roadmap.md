# Roadmap de desarrollo — Patient Management

## Alcance y estado

Plan incremental basado en la auditoría del código realizada el 28/10/2026.
R01–R04 están **completadas**, con evidencia de esta
ejecución en [verification-r01-r04.md](verification-r01-r04.md).
R05 está **completada**, con evidencia del 30/10/2026 en
[verification-r05.md](verification-r05.md).
R06 está **completada**, con evidencia en [verification-r06.md](verification-r06.md).
R07 está **completada**, con evidencia en [verification-r07.md](verification-r07.md).
R08 está **completada**, con evidencia en [verification-r08.md](verification-r08.md).
R09 está **completada**, con evidencia en [verification-r09.md](verification-r09.md).
R10 está **completada**, con semántica aprobada por el usuario en
[patient-registration-semantics.md](patient-registration-semantics.md).
R11–R15 y R17 tienen implementación y evidencia local; R16 tiene workflow
preparado y su ejecución en GitHub pendiente. R18–R19 tienen infraestructura
revisable y verificación local, pendientes del cierre integral de CI y de los
pasos externos explícitos. Evidencia en [verification-r11-r19.md](verification-r11-r19.md).
La existencia de clases, artefactos
compilados o reportes antiguos no demuestra que una tarea esté completa.

Este documento planifica trabajo; no autoriza cambios de contratos públicos,
secretos, credenciales, operaciones destructivas ni despliegues externos.
Las decisiones arquitectónicas nuevas deben aprobarse y registrarse en
[decisions.md](decisions.md). La arquitectura comprobada debe describirse en
[architecture.md](architecture.md).

## Cómo utilizar este roadmap

- Mantener los identificadores R01–R19 al actualizar o dividir tareas.
- Respetar las dependencias antes de implementar cada tarea.
- Usar los estados: propuesta, lista para implementar, en curso, bloqueada y completada.
- Marcar un criterio únicamente cuando exista evidencia verificable.
- Registrar al cerrar cada tarea los archivos modificados, comandos ejecutados,
  resultados y limitaciones pendientes.
- Ejecutar pruebas de persistencia e integración en entornos aislados de los datos
  del desarrollador.
- No ampliar el alcance funcional sin acordarlo previamente.

### Complejidad aproximada

| Nivel | Referencia |
| --- | --- |
| Baja | Cambio acotado en documentación, configuración o un componente. |
| Media | Varios componentes, configuración o pruebas de integración. |
| Alta | Datos existentes, recuperación distribuida o infraestructura. |

La complejidad no representa una estimación de calendario.

## Punto de partida de la auditoría

- Existen cinco aplicaciones: patient-service, auth-service, billing-service,
  analytics-service y api-gateway.
- Patient guarda el paciente antes de invocar billing y publicar en Kafka,
  sin recuperación persistente del proceso completo.
- Billing devuelve una cuenta fija sin persistencia.
- Analytics deserializa eventos y los registra en logs.
- Patient y auth utilizan JPA con `ddl-auto=update`, sin migraciones versionadas.
- Compose define las aplicaciones, PostgreSQL y Kafka. Su configuración pasó
  validación sintáctica; el arranque completo no se verificó en la auditoría.
- El módulo CDK y el script de LocalStack contienen configuración pendiente de corregir.
- Los tests existentes no cubren el flujo completo ni su recuperación ante fallos.
- No se ejecutaron tests ni despliegues durante la auditoría.

## Etapa 1 — Funcionamiento local y verificación básica

### R01 — Establecer una línea base reproducible

- **Estado:** completada.
- **Objetivo:** disponer de un checkout identificable y herramientas consistentes.
- **Dependencias:** ninguna.
- **Complejidad:** baja.
- **Criterios de aceptación:**
  - [x] Confirmar el checkout Git correcto; en la auditoría no había metadatos Git disponibles.
  - [x] Documentar herramientas requeridas, puertos y comandos por módulo.
  - [x] Resolver la diferencia entre el Java utilizado en consola y por Maven.
  - [x] Definir una ejecución reproducible con Java 21.
- **Cierre:** repositorio vacío indicado por el usuario, `main` y `origin`
  configurados; referencia local inicial `b44e478`, sin push. `scripts/maven.ps1`
  selecciona Java 21 y Wrapper Maven 3.9.16. Enforcer rechaza Java 25.
  Herramientas, puertos y comandos en [local-development.md](local-development.md).
  Archivos y evidencia en [verification-r01-r04.md](verification-r01-r04.md).

### R02 — Verificar y alinear dependencias

- **Estado:** completada.
- **Objetivo:** comprobar compatibilidad y reproducibilidad sin actualizar versiones indiscriminadamente.
- **Dependencias:** R01.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [x] Compilar las cinco aplicaciones con la cadena de herramientas acordada.
  - [x] Verificar el arranque y funcionamiento mínimo del servidor y cliente gRPC.
  - [x] Verificar la generación de OpenAPI en auth.
  - [x] Revisar las versiones de wrappers, generadores Protobuf y runtimes.
  - [x] Documentar versiones resueltas y justificar cualquier cambio de dependencia.
- **Evidencia:** `scripts/verify.ps1 -WithContexts` compila las cinco aplicaciones;
  test gRPC con puerto aleatorio y OpenAPI de auth aprobados. Versiones resueltas,
  cambios justificados y límites en [verification-r01-r04.md](verification-r01-r04.md).
  La llamada del cliente real de patient contra billing respondió durante el alta
  de un paciente sintético en el entorno Compose de R04.

### R03 — Aislar los tests existentes

- **Estado:** completada.
- **Objetivo:** ejecutar verificaciones sin depender de servicios o datos personales del entorno del desarrollador.
- **Dependencias:** R02.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [x] Separar tests unitarios de tests que necesitan infraestructura.
  - [x] Probar el mapper de pacientes sin levantar un contexto Spring completo.
  - [x] Configurar dependencias de prueba explícitas y aisladas.
  - [x] Documentar y ejecutar los comandos de cada grupo de tests.
  - [x] Reportar fallos y pruebas omitidas sin presentarlas como verificadas.
- **Cierre:** Surefire para unitarios; Failsafe optativo para contextos `*IT`,
  H2 en memoria y dependencias controladas. 11 pruebas aprobadas con
  `scripts/verify.ps1 -WithContexts`. integration-tests compila y excluye el grupo
  HTTP heredado por defecto; ese grupo sigue pendiente de reparación en R15.
  Comandos, archivos, fallos corregidos y límites en
  [verification-r01-r04.md](verification-r01-r04.md).

### R04 — Lograr un arranque local repetible con Compose

- **Estado:** completada.
- **Objetivo:** iniciar las aplicaciones y sus dependencias de forma predecible.
- **Dependencias:** R02.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [x] Construir e iniciar las cinco aplicaciones en un entorno de prueba.
  - [x] Comprobar disponibilidad de PostgreSQL, Kafka y billing antes de los flujos que los necesitan.
  - [x] Documentar las direcciones utilizadas desde contenedores y desde el host.
  - [x] Verificar reinicios y conservación de los datos en los volúmenes previstos.
  - [x] Documentar inicio y parada sin eliminar volúmenes con datos existentes.
- **Cierre:** las cinco imágenes se construyeron con Java 21/Maven 3.9.16.
  `scripts/compose-smoke.ps1 -SkipBuild` aprobó las comprobaciones completas con
  esas imágenes: ocho servicios saludables, alta con gRPC/Kafka, consumo de
  analytics, persistencia de registros en ambas bases y offsets Kafka después de
  `restart` y `down/up`. Sólo se utilizó el proyecto aislado `pm-roadmap-test`.
  Los contenedores de prueba se eliminaron sin borrar sus volúmenes. Guía en
  [local-development.md](local-development.md); archivos, comandos, fallos
  corregidos y límites en [verification-r01-r04.md](verification-r01-r04.md).

### R05 — Corregir y probar gateway y autenticación

- **Estado:** completada.
- **Objetivo:** disponer de una entrada HTTP coherente y autenticación verificable.
- **Dependencias:** R03, R04.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [x] Verificar login a través de `/api/auth/login` y su traducción al endpoint de auth.
  - [x] Corregir las diferencias de enrutamiento entre configuraciones preservando los contratos aprobados.
  - [x] Verificar acceso a pacientes con un token válido.
  - [x] Rechazar tokens ausentes, inválidos y expirados mediante tests.
  - [x] Definir y probar una respuesta y un tiempo máximo cuando auth falla o no responde.
- **Cierre (30/10/2026):** rutas heredadas por ambos perfiles, destino de auth
  compartido por proxy y validación, límites configurables de 2s y 502 ante
  fallos de conexión o timeout. 59 tests aprobados en gateway/auth, más prueba
  real de Compose en perfiles principal y prod con auth pausado y detenido.
  Se preservan endpoints y formatos; el caso JWT vacío ahora devuelve el 401
  previsto en vez de una excepción no controlada. No se agregaron dependencias.
  Archivos, comandos, resultados, fallos corregidos y límites en
  [verification-r05.md](verification-r05.md); decisión D13 en
  [decisions.md](decisions.md).

### R06 — Completar validación y manejo de errores de pacientes

- **Estado:** completada.
- **Objetivo:** controlar entradas inválidas, conflictos y errores del CRUD.
- **Dependencias:** R03.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [x] Probar fechas inválidas y DNI no numéricos o fuera del rango admitido.
  - [x] Manejar duplicados de email y DNI, incluyendo solicitudes concurrentes.
  - [x] Verificar alta, listado, actualización, eliminación y paciente inexistente.
  - [x] Evitar errores internos inesperados para entradas inválidas y conflictos conocidos.
  - [x] Obtener aprobación antes de cambiar rutas, campos o códigos HTTP públicos: no se modificaron esos contratos.
- **Cierre (30/10/2026):** validación DTO, preconsulta de DNI y traducción de
  violaciones de unicidad SQL 23505 al error 400 existente. 31 pruebas en la
  suite aislada y 24 escenarios CRUD con PostgreSQL efímero, sin fallos.
  No se cambiaron dependencias ni esquemas; billing/Kafka se simulan en esta
  verificación. Comandos, archivos, resultados y límites en
  [verification-r06.md](verification-r06.md).

## Etapa 2 — Persistencia y consistencia entre servicios

### R07 — Introducir migraciones preservando datos

- **Estado:** completada (herramienta y estrategia aprobadas por el usuario).
- **Objetivo:** evolucionar los esquemas de forma explícita y reproducible.
- **Dependencias:** R04 y aprobación de la herramienta y estrategia de migración.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [x] Registrar la herramienta y el procedimiento de adopción para bases existentes.
  - [x] Obtener el mismo esquema esperado desde una base nueva y una copia de una base existente.
  - [x] Verificar que los datos previos se conservan.
  - [x] Sustituir la dependencia de `ddl-auto=update` en los entornos acordados.
  - [x] Probar las migraciones sobre PostgreSQL y documentar recuperación ante fallos.
- **Cierre (30/10/2026):** Flyway 12.4.0 por servicio, V1 SQL, preflight y baseline
  manual. Hibernate validate en PostgreSQL; H2 conserva create-drop explícito.
  Pasaron 40 pruebas PostgreSQL de migración/aplicaciones y 43 de regresión
  unitarias/H2, además de info/validate del plugin Maven. Las copias probadas
  proceden de esquemas Hibernate con datos sintéticos; no se tocaron bases del
  usuario. Antes de reutilizar volúmenes antiguos se debe seguir
  [migrations.md](migrations.md). Archivos, comandos, resultados y límites en
  [verification-r07.md](verification-r07.md).

### R08 — Separar configuración y datos de desarrollo

- **Estado:** completada.
- **Objetivo:** evitar que valores y datos de desarrollo se apliquen accidentalmente en otros entornos.
- **Dependencias:** R05, R07.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [x] Limitar el seed administrativo al entorno previsto.
  - [x] Evitar el uso accidental de H2 fuera de los perfiles de prueba acordados.
  - [x] Documentar perfiles, variables obligatorias y diferencias entre host y contenedores.
  - [x] Validar la configuración requerida al iniciar cada aplicación.
  - [x] No modificar secretos o credenciales existentes sin autorización explícita.
- **Cierre (30/10/2026):** local/docker contienen los valores de desarrollo;
  prod/sin perfil requieren configuración explícita y no cargan el seed. H2 sólo
  existe en test. Las cinco aplicaciones validan claves antes de crear clientes
  o conexiones. Pasaron 131 pruebas de suite, 44 PostgreSQL y dos flujos Compose
  aislados. Contenido del seed y credenciales existentes preservados. Perfiles en
  [environments.md](environments.md); comandos, resultados y límites en
  [verification-r08.md](verification-r08.md).

### R09 — Implementar billing mínimo real

- **Estado:** completada (persistencia PostgreSQL propia aprobada por el usuario).
- **Objetivo:** reemplazar la respuesta simulada por la creación persistente de una cuenta.
- **Dependencias:** R03, R07 y aprobación del alcance de persistencia de billing.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [x] Persistir las cuentas en almacenamiento propio de billing, sin acceder a la base de patient.
  - [x] Devolver un identificador correspondiente a una cuenta real.
  - [x] Conservar las cuentas después de reiniciar el servicio.
  - [x] Evitar cuentas duplicadas al repetir una solicitud para el mismo `patientId`.
  - [x] Probar solicitudes inválidas, errores y resultados del contrato gRPC.
- **Cierre (30/10/2026):** PostgreSQL/Flyway propio, ID UUID persistido y unicidad
  de patientId con recuperación del ganador ante carreras. Reintentos válidos
  conservan el ID y snapshot iniciales. Pasaron 148 pruebas de suite, 13 escenarios
  PostgreSQL y Compose con nueve servicios y cuentas conservadas tras restart y
  down/up. Los dos Protobuf permanecen sin cambios. Alcance D17 y evidencia en
  [verification-r09.md](verification-r09.md). R10 define luego la semántica (D18);
  R11–R12 mantienen pendientes su implementación, reintentos y deadlines.

### R10 — Definir el comportamiento del alta ante fallos

- **Estado:** completada; semántica y cambios de contrato aprobados por el usuario.
- **Objetivo:** acordar qué significa completar un alta y cómo recuperar sus pasos parciales.
- **Dependencias:** R05, R06, R09.
- **Complejidad:** media.
- **Criterios de aceptación aprobados:**
  - [x] Decidir si billing debe confirmar su trabajo antes de responder al cliente.
  - [x] Especificar el comportamiento ante fallos de PostgreSQL, billing y Kafka.
  - [x] Definir estados pendientes, reintentos y tratamiento de resultados inciertos.
  - [x] Definir qué ocurre si el cliente repite una solicitud después de perder la respuesta.
  - [x] Registrar la decisión y aprobar cualquier cambio de contrato antes de implementarlo.
- **Cierre (30/10/2026):** inspeccionados el servicio de alta, los adaptadores
  gRPC/Kafka, el controlador, los DTO y el manejo de errores. Decisión D18 y matriz
  de fallos en [patient-registration-semantics.md](patient-registration-semantics.md):
  billing confirmado antes del 200, evento durable con entrega posterior,
  estados internos recuperables y clave de idempotencia opcional. Incluye
  explícitamente las extensiones HTTP 502/503/409 y sus condiciones. El usuario
  aprobó la propuesta completa. R10 cierra la definición: no se modificó código
  ni bases, y las garantías todavía no están implementadas. R11–R12 conservan
  su alcance de implementación y R12 requiere aprobar el patrón durable.
  Evidencia y límites en [verification-r10.md](verification-r10.md).

### R11 — Acotar y probar la llamada gRPC

- **Estado:** completada.
- **Objetivo:** controlar tiempos, recursos y errores de la integración con billing.
- **Dependencias:** R09, R10.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [x] Configurar y probar un deadline de la llamada.
  - [x] Cerrar el canal al finalizar la aplicación.
  - [x] Interpretar los estados gRPC y el resultado `success=false`.
  - [x] Aplicar reintentos únicamente cuando sean seguros según R10.
  - [x] Verificar que billing caído no bloquea indefinidamente una solicitud.

- **Evidencia local (30/10/2026):** Deadline configurable de 2s, canal administrado y cierre, clasificación de todos los estados gRPC, validación success/UUID y cero reintentos de negocio dentro de la solicitud. Tests con servidor real, caído y sin respuesta; propagación de correlationId. Los reintentos durables están en R12.
  Comandos, archivos, resultados y límites en [verification-r11-r19.md](verification-r11-r19.md).

### R12 — Implementar recuperación durable del alta

- **Estado:** completada.
- **Objetivo:** recuperar trabajo pendiente sin perder eventos ni duplicar efectos de negocio.
- **Dependencias:** R07, R10, R11 y aprobación del patrón elegido.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [x] Recuperar el proceso después de guardar el paciente y antes de contactar a billing.
  - [x] Recuperar el caso en que billing crea la cuenta y se pierde su respuesta.
  - [x] Resolver la publicación Kafka cuando el resultado de la confirmación es incierto.
  - [x] Recuperar trabajo pendiente después de detener y reiniciar la aplicación.
  - [x] Demostrar mediante tests que la recuperación no duplica cuentas ni pierde trabajo confirmado.

**Patrón aprobado:** registro durable y outbox local, decisión D19. La coordinación
con billing utiliza su idempotencia por patientId; Kafka admite reenvíos.

- **Evidencia local (30/10/2026):** D19 aprobado por el usuario. V2/V3 nuevas, paciente y trabajo en una transacción local, claves opcionales y snapshot, lease/token con fencing, recuperación/revisión y protección PUT/DELETE. Tests H2/PostgreSQL y caída real después de crear la cuenta y perder la respuesta, sin duplicarla.
  Comandos, archivos, resultados y límites en [verification-r11-r19.md](verification-r11-r19.md).

### R13 — Fortalecer contratos y procesamiento Kafka

- **Estado:** completada.
- **Objetivo:** permitir procesamiento compatible, observable y recuperable de eventos.
- **Dependencias:** R03, R10, R12.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [x] Detectar automáticamente divergencias entre las copias de los contratos Protobuf.
  - [x] Definir identidad del evento y clave de partición según las necesidades acordadas.
  - [x] Aprobar y verificar compatibilidad de cualquier evolución del contrato.
  - [x] Definir y probar el tratamiento de mensajes inválidos y fallos transitorios.
  - [x] Verificar que repetir un evento no duplica efectos de negocio.
  - [x] Disponer de evidencia observable de mensajes pendientes o fallidos y su recuperación.

- **Evidencia local (30/10/2026):** Protobuf intactos y comparación automática en verify.ps1; clave Kafka patientId e identidad (eventType,patientId). Quarentena durable sin payload, reintento SQL sin confirmar offset y deduplicación PostgreSQL. Tests con Kafka real, duplicados y mensajes inválidos; métricas/endpoints internos de seguimiento.
  Comandos, archivos, resultados y límites en [verification-r11-r19.md](verification-r11-r19.md).

### R14 — Implementar un resultado mínimo de analytics

- **Estado:** completada.
- **Objetivo:** transformar el consumidor de logs en una funcionalidad de negocio verificable.
- **Dependencias:** R13 y aprobación del resultado funcional esperado.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [x] Acordar al menos un resultado que analytics debe producir y cómo verificarlo.
  - [x] Decidir si necesita almacenamiento propio o reconstrucción desde eventos.
  - [x] Implementar y probar el resultado acordado.
  - [x] Verificar su comportamiento frente a eventos repetidos y reinicios.
  - [x] Definir si actualizaciones y eliminaciones de pacientes requieren eventos adicionales.

- **Evidencia local (30/10/2026):** D20 aprobado por el usuario. Total histórico patientsCreated, PostgreSQL/Flyway propios y GET /api/analytics/summary protegido. No se guardan nombre/email; PUT/DELETE no generan eventos ni decrementan el histórico. Concurrencia, SQL fallido, redelivery y persistencia tras recrear contenedores verificados.
  Comandos, archivos, resultados y límites en [verification-r11-r19.md](verification-r11-r19.md).

## Etapa 3 — Verificación completa y operación

### R15 — Reparar y ampliar los tests de integración

- **Estado:** completada.
- **Objetivo:** demostrar el funcionamiento del sistema completo y su recuperación.
- **Dependencias:** R05–R14 para la aceptación completa; incorporar escenarios conforme se resuelvan sus dependencias.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [x] Corregir rutas y aserciones para que coincidan con los contratos aprobados.
  - [x] Preparar un entorno aislado y datos de prueba reproducibles.
  - [x] Verificar login, CRUD de pacientes, cuenta real de billing y resultado de analytics.
  - [x] Probar caídas de dependencias, resultados inciertos, repeticiones y recuperación.
  - [x] Configurar explícitamente la ejecución del módulo de integración.
  - [x] Conservar resultados y reportar los escenarios todavía no verificados.

- **Evidencia local (30/10/2026):** Rutas y aserciones reparadas, sin impresión de JWT ni dependencia implícita de localhost. verify-system.ps1 crea proyecto/fixtures/volúmenes aislados. Nueve pruebas externas verifican auth/roles, CRUD, cuenta real, Kafka/analytics, respuesta RPC perdida y recuperación tras reinicios. Reportes conservados; no sustituyen pruebas en AWS.
  Comandos, archivos, resultados y límites en [verification-r11-r19.md](verification-r11-r19.md).

### R16 — Incorporar integración continua

- **Estado:** en curso; implementación/preparación local verificada, cierre externo pendiente.
- **Objetivo:** automatizar la evidencia necesaria para aceptar cambios.
- **Dependencias:** R02 y R03 para iniciar; R15 para completar la verificación integral.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [x] Disponer de un comando documentado para las verificaciones del proyecto.
  - [ ] Ejecutar compilación y tests aislados en CI (workflow preparado; job GitHub pendiente).
  - [x] Incorporar los tests de integración conforme estén disponibles.
  - [x] Construir las imágenes y conservar reportes de las verificaciones.
  - [x] Hacer visible cualquier fallo y evitar presentar un cambio como verificado cuando sus checks fallan.

- **Evidencia local (30/10/2026):** Workflow con acciones fijadas por SHA, Java 21/Node 22, suite aislada, PostgreSQL, síntesis, LocalStack acotado y construcción/aceptación Compose. Comandos equivalentes ejecutados localmente; reportes y fallos conservados. El repositorio remoto sigue vacío y público: falta publicar la rama y verificar el job real en GitHub, sin atribuirle resultados locales.
  Comandos, archivos, resultados y límites en [verification-r11-r19.md](verification-r11-r19.md).

### R17 — Añadir observabilidad y autorización acordadas

- **Estado:** completada.
- **Objetivo:** detectar fallos y aplicar una política explícita de acceso.
- **Dependencias:** R05, R10, R14 y aprobación de la política de roles.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [x] Exponer healthchecks útiles para las aplicaciones y su infraestructura.
  - [x] Correlacionar solicitudes y errores entre los servicios involucrados.
  - [x] Hacer visible el trabajo pendiente, fallido y recuperado del alta.
  - [x] Probar permisos por rol según la política aprobada.
  - [x] Evitar registrar tokens y datos personales innecesarios en logs.

- **Evidencia local (30/10/2026):** D21 aprobado por el usuario. ADMIN CRUD/analytics, USER sólo lectura; roles desde JWT verificado por auth, nunca del encabezado del cliente. Correlación HTTP/gRPC/Kafka, cantidades durables y métricas, cuarentena y logs sanitizados. Tests de permisos y endpoints operativos, sin cambios de cuentas/credenciales.
  Comandos, archivos, resultados y límites en [verification-r11-r19.md](verification-r11-r19.md).

## Etapa 4 — Infraestructura local emulada y preparación cloud

### R18 — Corregir y comprobar CDK y LocalStack

- **Estado:** en curso; implementación/preparación local verificada, cierre externo pendiente.
- **Objetivo:** disponer de infraestructura sintetizable y un alcance de emulación comprobado.
- **Dependencias:** R04, R07, R09, R16, R17.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [x] Documentar y ejecutar una síntesis reproducible con rutas de salida consistentes.
  - [x] Corregir propiedades de datasource, URLs JDBC, puertos y direcciones de servicios.
  - [x] Definir descubrimiento, conectividad, configuración MSK y healthchecks coherentes.
  - [x] Comprobar disponibilidad de imágenes y el tratamiento de secretos sin modificar credenciales sin autorización.
  - [x] Incorporar tests del template para detectar regresiones de configuración.
  - [x] Fijar versión y procedimiento de inicio de LocalStack.
  - [x] Documentar qué recursos se verificaron en LocalStack y qué comportamiento requiere validación en AWS.

- **Evidencia local (30/10/2026):** Java/CDK reproducibles, cinco tests de template y síntesis de registro ECR, candidato AWS y emulación. Cuatro bases privadas, DNS/puertos/JDBC corregidos, TLS/SCRAM, secretos por referencias y digests. LocalStack 4.14.0 probó sólo CloudFormation/Secrets Manager ficticios en contenedor propio. Dependencia formal R16 pendiente de ejecución remota; no se afirma despliegue AWS.
  Comandos, archivos, resultados y límites en [verification-r11-r19.md](verification-r11-r19.md).

### R19 — Preparar el primer despliegue AWS

- **Estado:** en curso; implementación/preparación local verificada, cierre externo pendiente.
- **Objetivo:** dejar un despliegue concreto y revisable, pendiente de aprobación para ejecutarlo.
- **Dependencias:** R15, R16, R17, R18.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [x] Preparar imágenes versionadas y un procedimiento de publicación.
  - [x] Definir conectividad, descubrimiento y configuración específica de AWS.
  - [x] Preparar inyección de secretos y permisos necesarios.
  - [x] Revisar TLS, backups, retención y protección de datos persistentes.
  - [x] Documentar ejecución de migraciones, verificación posterior y recuperación ante fallos.
  - [x] Disponer del diff de infraestructura y del procedimiento para revisión.
  - [x] Mantener la ejecución del despliegue externo pendiente de aprobación explícita.

- **Evidencia local (30/10/2026):** Preparadas cinco imágenes locales versionadas, manifiesto, templates, revisión/diff local y procedimientos de publicación, TLS, backups, migración y recuperación en aws-deployment.md. No se publicó ni desplegó AWS. Faltan cierre remoto R16 y selección/aprobación de cuenta, región, certificado, secretos, digests publicados y presupuesto para ejecutar el despliegue.
  Comandos, archivos, resultados y límites en [verification-r11-r19.md](verification-r11-r19.md).

## Orden recomendado y decisiones pendientes

1. Completar R01–R06 para estabilizar el comportamiento local.
2. Iniciar CI después de R03 y ampliar sus verificaciones durante el desarrollo.
3. Completar migraciones, configuración y billing real con R07–R09.
4. Aprobar R10 antes de adoptar patrones de recuperación en R11–R13.
5. Definir el resultado de analytics y verificar el flujo integral con R14–R17.
6. Validar LocalStack y preparar AWS con R18–R19.

D18–D21 resolvieron semántica del alta, recuperación, resultado de analytics y
roles. Se conserva el alcance aprobado sin eventos PUT/DELETE. Permanecen abiertos
la política de purga de snapshots, la publicación/ejecución de CI y la selección
y aprobación del entorno AWS. La emulación se limita a lo efectivamente probado.

## Definición de tarea completada

- [ ] Se cumplieron los criterios aplicables y se explicó cualquier excepción aprobada.
- [ ] Se ejecutaron los tests relevantes para los cambios de código y se registraron sus resultados.
- [ ] Se revisó el diff cuando Git estaba disponible.
- [ ] Se actualizó la documentación afectada y se registraron las decisiones nuevas.
- [ ] Se identificaron riesgos residuales y comportamiento no verificado.
- [ ] No se confundió evidencia histórica con verificaciones de la implementación actual.
