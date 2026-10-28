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

## Propuestas pendientes de aprobación

| ID | Propuesta | Motivo y condición |
|---|---|---|
| P01 | Fijar una cadena de herramientas y dependencias compatible. | Reproducibilidad; verificar antes de cambiar versiones. |
| P02 | Incorporar migraciones por servicio. | Evolucionar esquemas preservando datos existentes. |
| P03 | Persistir cuentas de billing e identificar duplicados por patientId. | Reemplazar el éxito simulado y soportar reintentos. |
| P04 | Definir la semántica del alta distribuida. | Resolver estados parciales y resultados inciertos. |
| P05 | Evaluar outbox transaccional en patient. | Guardar paciente y trabajo pendiente en una transacción local. No resuelve por sí solo billing. |
| P06 | Hacer consumidores y operaciones remotas idempotentes. | Permitir recuperación sin duplicar efectos de negocio. |
| P07 | Definir un resultado mínimo de analytics. | Evitar implementar estadísticas sin requisitos. |
| P08 | Incorporar tests con dependencias aisladas. | Verificar PostgreSQL, Kafka y gRPC sin afectar datos personales. |
| P09 | Estabilizar primero Compose y después LocalStack/CDK. | Reducir variables durante la validación funcional. |
| P10 | Preparar configuración, red y secretos para AWS. | El stack actual contiene supuestos del entorno Docker local. |

## Decisiones abiertas
- ¿El alta debe confirmar billing antes de responder?
- ¿Cómo se representa y recupera una cuenta pendiente?
- ¿Qué debe calcular analytics?
- ¿Actualizar y eliminar pacientes requiere eventos?
- ¿Qué operaciones requieren cada rol?
- ¿Qué servicios de AWS se comprobarán mediante LocalStack?

## Formato de futuras decisiones
Cada decisión aprobada debe registrar:
contexto, alternativas, elección, consecuencias,
compatibilidad, evidencia y estrategia de validación.

La presencia de una propuesta en este archivo no autoriza
cambios de contratos, credenciales o despliegues.