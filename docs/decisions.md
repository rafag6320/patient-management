# Decisiones técnicas

Este documento resume las decisiones vigentes y sus consecuencias. La
[arquitectura](architecture.md) describe el sistema actual; las mejoras aún no
implementadas están en el [roadmap](roadmap.md).

## Servicios y comunicaciones

Se mantienen cinco aplicaciones independientes, cada una con una responsabilidad:
patient, auth, billing, analytics y gateway. Es una elección educativa para
explorar límites de datos y fallos de red. Para este alcance, un monolito modular
sería una alternativa con menor coste operativo.

REST es la interfaz de clientes; gRPC define el contrato tipado de creación de
cuentas; Kafka desacopla el procesamiento de analytics del alta. El coste es
mantener contratos, deadlines y recuperación entre procesos. Los Protobuf se
conservan en los módulos que los usan y se comparan automáticamente.

## Persistencia y migraciones

Cada servicio con datos tiene PostgreSQL propio. Billing no consulta patient ni
analytics consulta bases ajenas. La unicidad de email/DNI en patient y de
patientId en billing se garantiza también mediante restricciones SQL: una
preconsulta por sí sola no resuelve solicitudes concurrentes.

Flyway administra las migraciones por servicio; Hibernate valida el esquema.
Frente a la actualización implícita de Hibernate, esto permite revisar cambios
y comprobar el historial. Las migraciones aplicadas son inmutables. La adopción
de bases anteriores exige backup restaurable, preflight y baseline manual;
no se habilitan baseline automático ni clean. Ver [migraciones](migrations.md).

## Alta con confirmación de billing e intención durable

Un HTTP 200 de alta confirma paciente, cuenta billing válida e intención durable
del evento. Kafka y analytics se procesan después. Se eligió esta garantía frente
a responder 202 antes de crear la cuenta o exigir que Kafka esté disponible para
completar la solicitud.

Paciente y registration_work se guardan en una transacción local. Las llamadas de
red se ejecutan fuera de ella. Un dispatcher reclama trabajo mediante lease/token
y confirma avances sólo si conserva la propiedad. Tras un reinicio puede repetir
billing con el mismo patientId y publicar el evento pendiente.

No se usa una transacción distribuida ni se borra el paciente como compensación:
una respuesta perdida puede ocultar un efecto remoto ya confirmado. Billing es
idempotente por patientId; Kafka admite redelivery. La clave HTTP opcional permite
recuperar la operación original sin crear otro paciente. Reglas y códigos en
[semántica del alta](patient-registration-semantics.md).

Los snapshots iniciales y las claves consumidas se retienen; no hay purga ni
reconciliación histórica automática. El procedimiento de revisión y reanudación
está en [operación](recovery-operations.md).

## Analytics histórico y deduplicación

patientsCreated cuenta identidades PATIENT_CREATED distintas, no pacientes activos.
La fila única por (eventType, patientId) representa el efecto de negocio; no hay
un contador separado que pueda incrementarse dos veces. PostgreSQL se confirma
antes del offset Kafka. Una caída entre ambos puede repetir el mensaje sin sumar
otro efecto.

Los mensajes inválidos se conservan en cuarentena con metadatos y hash, sin
payload personal. Un fallo SQL provoca reintento, no descarte. No se guardan
nombre/email ni se generan eventos de actualización o eliminación: modificar o
borrar un paciente no sincroniza billing ni decrementa analytics.

## Autenticación, roles y límites internos

Auth firma y valida JWT; el gateway consulta esa validación por HTTP. ADMIN puede
escribir y leer; USER sólo leer. El gateway utiliza roles devueltos por auth y
descarta encabezados de rol aportados por el cliente.

La validación y el proxy a auth tienen tiempos acotados, sin reintentos de POST.
Un token inválido devuelve 401; permisos insuficientes, 403; auth inaccesible, 502.
Los servicios internos requieren red privada: no incorporan una segunda política
JWT ni mTLS HTTP/gRPC.

X-Correlation-Id se valida o genera y se propaga por HTTP, gRPC y Kafka.
Los endpoints operativos exponen cantidades agregadas, sin tokens ni snapshots.

## Configuración y pruebas aisladas

Java 21 y Maven Wrapper 3.9.16 se seleccionan por proceso. La salida CLI usa
.verification para separarse del IDE. Los perfiles local/docker contienen valores
de desarrollo y seed condicional; fuera de ellos se exige configuración explícita.
H2 sólo se incluye en tests.

Los unitarios no requieren infraestructura. Los contextos usan configuración
explícita y dependencias controladas. PostgreSQL, Kafka y el sistema completo se
verifican en proyectos propios con datos sintéticos. H2 no sustituye esas pruebas.
Comandos en [desarrollo local](local-development.md).

## Disponibilidad y demo local

Compose usa healthchecks en aplicaciones, bases y broker. La demo reutiliza las
imágenes con un override, recursos etiquetados y estado propio. Sólo publica el
gateway en loopback; Stop conserva volúmenes. Se evita así mezclar datos de la
demo con una instalación habitual.

Antes de los escenarios se comprueba login y lectura autenticada: Actuator UP no
garantiza que el primer recorrido de negocio termine dentro de sus límites. Esta
preparación admite sólo 502/503 transitorios durante un plazo acotado. No amplía
los timeouts de los servicios ni reintenta tests fallidos.

## OpenAPI central

El gateway sirve Swagger y catálogos OpenAPI explícitos. Sus rutas son filtros,
por lo que escanear sus controladores no descubriría los contratos de negocio.
Los JSON permiten revisar esos contratos sin agregar anotaciones a cada servicio.
A cambio, deben actualizarse con el código; los tests comprueban rutas, referencias
y DTO compartidos. Ver [Swagger](swagger.md).

## Infraestructura preparada

CDK Java separa registro ECR, candidato AWS y emulación. El candidato define RDS,
MSK, ECS/Fargate, descubrimiento privado, secretos y ALB HTTPS. Usa digests de
imágenes como entradas explícitas.

La síntesis y los tests de templates no prueban un despliegue real. LocalStack se
limita a CloudFormation y un secreto ficticio; no emula aquí RDS/MSK/ECS.
AWS permanece sin desplegar y requiere revisión de entorno, permisos, costes y
operación. Ver [preparación AWS](aws-deployment.md).
