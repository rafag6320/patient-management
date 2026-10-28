# Roadmap de desarrollo — Patient Management

## Alcance y estado

Plan incremental basado en la auditoría del código realizada el 28/10/2026.
Todas las tareas están **propuestas y pendientes**. La existencia de clases,
artefactos compilados o reportes antiguos no demuestra que una tarea esté completa.

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

- **Estado:** propuesta.
- **Objetivo:** disponer de un checkout identificable y herramientas consistentes.
- **Dependencias:** ninguna.
- **Complejidad:** baja.
- **Criterios de aceptación:**
  - [ ] Confirmar el checkout Git correcto; en la auditoría no había metadatos Git disponibles.
  - [ ] Documentar herramientas requeridas, puertos y comandos por módulo.
  - [ ] Resolver la diferencia entre el Java utilizado en consola y por Maven.
  - [ ] Definir una ejecución reproducible con Java 21.

### R02 — Verificar y alinear dependencias

- **Estado:** propuesta.
- **Objetivo:** comprobar compatibilidad y reproducibilidad sin actualizar versiones indiscriminadamente.
- **Dependencias:** R01.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [ ] Compilar las cinco aplicaciones con la cadena de herramientas acordada.
  - [ ] Verificar el arranque y funcionamiento mínimo del servidor y cliente gRPC.
  - [ ] Verificar la generación de OpenAPI en auth.
  - [ ] Revisar las versiones de wrappers, generadores Protobuf y runtimes.
  - [ ] Documentar versiones resueltas y justificar cualquier cambio de dependencia.

### R03 — Aislar los tests existentes

- **Estado:** propuesta.
- **Objetivo:** ejecutar verificaciones sin depender de servicios o datos personales del entorno del desarrollador.
- **Dependencias:** R02.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [ ] Separar tests unitarios de tests que necesitan infraestructura.
  - [ ] Probar el mapper de pacientes sin levantar un contexto Spring completo.
  - [ ] Configurar dependencias de prueba explícitas y aisladas.
  - [ ] Documentar y ejecutar los comandos de cada grupo de tests.
  - [ ] Reportar fallos y pruebas omitidas sin presentarlas como verificadas.

### R04 — Lograr un arranque local repetible con Compose

- **Estado:** propuesta.
- **Objetivo:** iniciar las aplicaciones y sus dependencias de forma predecible.
- **Dependencias:** R02.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [ ] Construir e iniciar las cinco aplicaciones en un entorno de prueba.
  - [ ] Comprobar disponibilidad de PostgreSQL, Kafka y billing antes de los flujos que los necesitan.
  - [ ] Documentar las direcciones utilizadas desde contenedores y desde el host.
  - [ ] Verificar reinicios y conservación de los datos en los volúmenes previstos.
  - [ ] Documentar inicio y parada sin eliminar volúmenes con datos existentes.

### R05 — Corregir y probar gateway y autenticación

- **Estado:** propuesta.
- **Objetivo:** disponer de una entrada HTTP coherente y autenticación verificable.
- **Dependencias:** R03, R04.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [ ] Verificar login a través de `/api/auth/login` y su traducción al endpoint de auth.
  - [ ] Corregir las diferencias de enrutamiento entre configuraciones preservando los contratos aprobados.
  - [ ] Verificar acceso a pacientes con un token válido.
  - [ ] Rechazar tokens ausentes, inválidos y expirados mediante tests.
  - [ ] Definir y probar una respuesta y un tiempo máximo cuando auth falla o no responde.

### R06 — Completar validación y manejo de errores de pacientes

- **Estado:** propuesta.
- **Objetivo:** controlar entradas inválidas, conflictos y errores del CRUD.
- **Dependencias:** R03.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [ ] Probar fechas inválidas y DNI no numéricos o fuera del rango admitido.
  - [ ] Manejar duplicados de email y DNI, incluyendo solicitudes concurrentes.
  - [ ] Verificar alta, listado, actualización, eliminación y paciente inexistente.
  - [ ] Evitar errores internos inesperados para entradas inválidas y conflictos conocidos.
  - [ ] Obtener aprobación antes de cambiar rutas, campos o códigos HTTP públicos.

## Etapa 2 — Persistencia y consistencia entre servicios

### R07 — Introducir migraciones preservando datos

- **Estado:** propuesta.
- **Objetivo:** evolucionar los esquemas de forma explícita y reproducible.
- **Dependencias:** R04 y aprobación de la herramienta y estrategia de migración.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [ ] Registrar la herramienta y el procedimiento de adopción para bases existentes.
  - [ ] Obtener el mismo esquema esperado desde una base nueva y una copia de una base existente.
  - [ ] Verificar que los datos previos se conservan.
  - [ ] Sustituir la dependencia de `ddl-auto=update` en los entornos acordados.
  - [ ] Probar las migraciones sobre PostgreSQL y documentar recuperación ante fallos.

### R08 — Separar configuración y datos de desarrollo

- **Estado:** propuesta.
- **Objetivo:** evitar que valores y datos de desarrollo se apliquen accidentalmente en otros entornos.
- **Dependencias:** R05, R07.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [ ] Limitar el seed administrativo al entorno previsto.
  - [ ] Evitar el uso accidental de H2 fuera de los perfiles de prueba acordados.
  - [ ] Documentar perfiles, variables obligatorias y diferencias entre host y contenedores.
  - [ ] Validar la configuración requerida al iniciar cada aplicación.
  - [ ] No modificar secretos o credenciales existentes sin autorización explícita.

### R09 — Implementar billing mínimo real

- **Estado:** propuesta.
- **Objetivo:** reemplazar la respuesta simulada por la creación persistente de una cuenta.
- **Dependencias:** R03, R07 y aprobación del alcance de persistencia de billing.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [ ] Persistir las cuentas en almacenamiento propio de billing, sin acceder a la base de patient.
  - [ ] Devolver un identificador correspondiente a una cuenta real.
  - [ ] Conservar las cuentas después de reiniciar el servicio.
  - [ ] Evitar cuentas duplicadas al repetir una solicitud para el mismo `patientId`.
  - [ ] Probar solicitudes inválidas, errores y resultados del contrato gRPC.

### R10 — Definir el comportamiento del alta ante fallos

- **Estado:** propuesta.
- **Objetivo:** acordar qué significa completar un alta y cómo recuperar sus pasos parciales.
- **Dependencias:** R05, R06, R09.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [ ] Decidir si billing debe confirmar su trabajo antes de responder al cliente.
  - [ ] Especificar el comportamiento ante fallos de PostgreSQL, billing y Kafka.
  - [ ] Definir estados pendientes, reintentos y tratamiento de resultados inciertos.
  - [ ] Definir qué ocurre si el cliente repite una solicitud después de perder la respuesta.
  - [ ] Registrar la decisión y aprobar cualquier cambio de contrato antes de implementarlo.

### R11 — Acotar y probar la llamada gRPC

- **Estado:** propuesta.
- **Objetivo:** controlar tiempos, recursos y errores de la integración con billing.
- **Dependencias:** R09, R10.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [ ] Configurar y probar un deadline de la llamada.
  - [ ] Cerrar el canal al finalizar la aplicación.
  - [ ] Interpretar los estados gRPC y el resultado `success=false`.
  - [ ] Aplicar reintentos únicamente cuando sean seguros según R10.
  - [ ] Verificar que billing caído no bloquea indefinidamente una solicitud.

### R12 — Implementar recuperación durable del alta

- **Estado:** propuesta.
- **Objetivo:** recuperar trabajo pendiente sin perder eventos ni duplicar efectos de negocio.
- **Dependencias:** R07, R10, R11 y aprobación del patrón elegido.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [ ] Recuperar el proceso después de guardar el paciente y antes de contactar a billing.
  - [ ] Recuperar el caso en que billing crea la cuenta y se pierde su respuesta.
  - [ ] Resolver la publicación Kafka cuando el resultado de la confirmación es incierto.
  - [ ] Recuperar trabajo pendiente después de detener y reiniciar la aplicación.
  - [ ] Demostrar mediante tests que la recuperación no duplica cuentas ni pierde trabajo confirmado.

**Alternativa por evaluar:** outbox transaccional en patient. No está adoptada;
por sí sola no resuelve la coordinación con billing ni evita todos los duplicados.

### R13 — Fortalecer contratos y procesamiento Kafka

- **Estado:** propuesta.
- **Objetivo:** permitir procesamiento compatible, observable y recuperable de eventos.
- **Dependencias:** R03, R10, R12.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [ ] Detectar automáticamente divergencias entre las copias de los contratos Protobuf.
  - [ ] Definir identidad del evento y clave de partición según las necesidades acordadas.
  - [ ] Aprobar y verificar compatibilidad de cualquier evolución del contrato.
  - [ ] Definir y probar el tratamiento de mensajes inválidos y fallos transitorios.
  - [ ] Verificar que repetir un evento no duplica efectos de negocio.
  - [ ] Disponer de evidencia observable de mensajes pendientes o fallidos y su recuperación.

### R14 — Implementar un resultado mínimo de analytics

- **Estado:** propuesta.
- **Objetivo:** transformar el consumidor de logs en una funcionalidad de negocio verificable.
- **Dependencias:** R13 y aprobación del resultado funcional esperado.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [ ] Acordar al menos un resultado que analytics debe producir y cómo verificarlo.
  - [ ] Decidir si necesita almacenamiento propio o reconstrucción desde eventos.
  - [ ] Implementar y probar el resultado acordado.
  - [ ] Verificar su comportamiento frente a eventos repetidos y reinicios.
  - [ ] Definir si actualizaciones y eliminaciones de pacientes requieren eventos adicionales.

## Etapa 3 — Verificación completa y operación

### R15 — Reparar y ampliar los tests de integración

- **Estado:** propuesta.
- **Objetivo:** demostrar el funcionamiento del sistema completo y su recuperación.
- **Dependencias:** R05–R14 para la aceptación completa; incorporar escenarios conforme se resuelvan sus dependencias.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [ ] Corregir rutas y aserciones para que coincidan con los contratos aprobados.
  - [ ] Preparar un entorno aislado y datos de prueba reproducibles.
  - [ ] Verificar login, CRUD de pacientes, cuenta real de billing y resultado de analytics.
  - [ ] Probar caídas de dependencias, resultados inciertos, repeticiones y recuperación.
  - [ ] Configurar explícitamente la ejecución del módulo de integración.
  - [ ] Conservar resultados y reportar los escenarios todavía no verificados.

### R16 — Incorporar integración continua

- **Estado:** propuesta.
- **Objetivo:** automatizar la evidencia necesaria para aceptar cambios.
- **Dependencias:** R02 y R03 para iniciar; R15 para completar la verificación integral.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [ ] Disponer de un comando documentado para las verificaciones del proyecto.
  - [ ] Ejecutar compilación y tests aislados en CI.
  - [ ] Incorporar los tests de integración conforme estén disponibles.
  - [ ] Construir las imágenes y conservar reportes de las verificaciones.
  - [ ] Hacer visible cualquier fallo y evitar presentar un cambio como verificado cuando sus checks fallan.

### R17 — Añadir observabilidad y autorización acordadas

- **Estado:** propuesta.
- **Objetivo:** detectar fallos y aplicar una política explícita de acceso.
- **Dependencias:** R05, R10, R14 y aprobación de la política de roles.
- **Complejidad:** media.
- **Criterios de aceptación:**
  - [ ] Exponer healthchecks útiles para las aplicaciones y su infraestructura.
  - [ ] Correlacionar solicitudes y errores entre los servicios involucrados.
  - [ ] Hacer visible el trabajo pendiente, fallido y recuperado del alta.
  - [ ] Probar permisos por rol según la política aprobada.
  - [ ] Evitar registrar tokens y datos personales innecesarios en logs.

## Etapa 4 — Infraestructura local emulada y preparación cloud

### R18 — Corregir y comprobar CDK y LocalStack

- **Estado:** propuesta.
- **Objetivo:** disponer de infraestructura sintetizable y un alcance de emulación comprobado.
- **Dependencias:** R04, R07, R09, R16, R17.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [ ] Documentar y ejecutar una síntesis reproducible con rutas de salida consistentes.
  - [ ] Corregir propiedades de datasource, URLs JDBC, puertos y direcciones de servicios.
  - [ ] Definir descubrimiento, conectividad, configuración MSK y healthchecks coherentes.
  - [ ] Comprobar disponibilidad de imágenes y el tratamiento de secretos sin modificar credenciales sin autorización.
  - [ ] Incorporar tests del template para detectar regresiones de configuración.
  - [ ] Fijar versión y procedimiento de inicio de LocalStack.
  - [ ] Documentar qué recursos se verificaron en LocalStack y qué comportamiento requiere validación en AWS.

### R19 — Preparar el primer despliegue AWS

- **Estado:** propuesta.
- **Objetivo:** dejar un despliegue concreto y revisable, pendiente de aprobación para ejecutarlo.
- **Dependencias:** R15, R16, R17, R18.
- **Complejidad:** alta.
- **Criterios de aceptación:**
  - [ ] Preparar imágenes versionadas y un procedimiento de publicación.
  - [ ] Definir conectividad, descubrimiento y configuración específica de AWS.
  - [ ] Preparar inyección de secretos y permisos necesarios.
  - [ ] Revisar TLS, backups, retención y protección de datos persistentes.
  - [ ] Documentar ejecución de migraciones, verificación posterior y recuperación ante fallos.
  - [ ] Disponer del diff de infraestructura y del procedimiento para revisión.
  - [ ] Mantener la ejecución del despliegue externo pendiente de aprobación explícita.

## Orden recomendado y decisiones pendientes

1. Completar R01–R06 para estabilizar el comportamiento local.
2. Iniciar CI después de R03 y ampliar sus verificaciones durante el desarrollo.
3. Completar migraciones, configuración y billing real con R07–R09.
4. Aprobar R10 antes de adoptar patrones de recuperación en R11–R13.
5. Definir el resultado de analytics y verificar el flujo integral con R14–R17.
6. Validar LocalStack y preparar AWS con R18–R19.

Permanecen abiertas la semántica del alta distribuida, el alcance de analytics,
los eventos de actualización y eliminación, la política de roles y el alcance
de emulación de LocalStack. Estas decisiones deben resolverse en sus tareas;
no se consideran funcionalidades ya acordadas.

## Definición de tarea completada

- [ ] Se cumplieron los criterios aplicables y se explicó cualquier excepción aprobada.
- [ ] Se ejecutaron los tests relevantes para los cambios de código y se registraron sus resultados.
- [ ] Se revisó el diff cuando Git estaba disponible.
- [ ] Se actualizó la documentación afectada y se registraron las decisiones nuevas.
- [ ] Se identificaron riesgos residuales y comportamiento no verificado.
- [ ] No se confundió evidencia histórica con verificaciones de la implementación actual.
