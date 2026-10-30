# Patient Management — Instrucciones para agentes

## Objetivo y comunicación
- Proyecto backend con Java, Spring Boot, PostgreSQL, gRPC,
  Kafka, Docker y AWS CDK.
- Responder en español.
- Inspeccionar el código antes de modificarlo.
- Diferenciar código implementado, comportamiento verificado
  y recomendaciones pendientes.

## Módulos y límites
- patient-service: pacientes y coordinación actual del alta.
- auth-service: usuarios, login y JWT.
- billing-service: cuentas persistentes propias por gRPC, idempotentes por patientId.
- analytics-service: consumidor Kafka, deduplicación, total histórico y cuarentena propios.
- api-gateway: enrutamiento y validación remota del JWT.
- integration-tests: pruebas HTTP externas.
- infrastructure: AWS CDK Java y script para LocalStack.
- No acceder directamente a bases de otros servicios.

## Documentación
- Leer docs/architecture.md antes de cambios arquitectónicos.
- Leer docs/decisions.md antes de introducir patrones.
- Consultar docs/roadmap.md para dependencias y aceptación.
- No presentar propuestas como decisiones ya adoptadas.
- Actualizar la documentación afectada junto con la implementación.

## Java y contratos
- Respetar Java 21 y las versiones declaradas por módulo.
- Usar inyección por constructor.
- Mantener negocio fuera de controladores.
- Seguir DTO, validación y manejo de errores existentes.
- No cambiar rutas, campos, códigos HTTP ni contratos Protobuf
  sin aprobación.
- Revisar ambas copias de los contratos Protobuf cuando corresponda.
- Justificar dependencias nuevas y evitar refactorizaciones ajenas.

## Persistencia y consistencia
- Preservar los datos existentes.
- No modificar migraciones aplicadas cuando se incorporen.
- No ejecutar operaciones destructivas sin aprobación explícita.
- No asumir atomicidad entre PostgreSQL, gRPC y Kafka.
- Evaluar reintentos, duplicados y recuperación al modificar integraciones.

## Verificación
- Proponer un plan antes de tareas complejas.
- Agregar tests para nueva lógica de negocio.
- Ejecutar los tests pertinentes después de implementar.
- Verificar qué datasource y dependencias utilizarán antes de arrancar
  contextos Spring o tests de integración.
- No usar bases con datos del desarrollador para pruebas mutantes.
- Reportar comandos, resultados, fallos y comportamiento no verificado.
- Los reportes antiguos y los JAR existentes no prueban el estado actual.

## Restricciones
- No modificar secretos ni credenciales.
- No desplegar infraestructura externa sin aprobación.
- No ejecutar comandos Git destructivos sin aprobación.

## Cierre de una tarea
- Revisar el diff cuando Git esté disponible.
- Resumir archivos modificados, pruebas y riesgos pendientes.
- No declarar una funcionalidad terminada sólo por existir sus clases.