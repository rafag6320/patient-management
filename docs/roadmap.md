# Roadmap

El funcionamiento actual está descrito en [arquitectura](architecture.md) y se
verifica con los comandos de [desarrollo local](local-development.md). Esta lista
contiene trabajo pendiente; no representa funcionalidades ya implementadas.

## Retención del trabajo durable

- Objetivo: definir cuánto conservar snapshots, claves consumidas y cuarentena,
  preservando privacidad y garantías de idempotencia.
- Dependencias: acordar plazos y comportamiento de clientes después de la retención.
- Complejidad: media/alta.
- Aceptación: política documentada, migración no destructiva, procedimiento de
  archivo/purga probado en copias y tests que impidan reutilizar claves o
  republicar efectos por accidente.

## Reconciliación de datos históricos

- Objetivo: detectar pacientes anteriores al flujo durable o inconsistencias
  entre efectos confirmados, sin crear cuentas/eventos automáticamente.
- Dependencias: definir el alcance de reconciliación y la política de retención.
- Complejidad: alta.
- Aceptación: diagnóstico de sólo lectura por servicio, reporte sin datos
  personales innecesarios y reparación explícita probada con IDs estables,
  reintentos e interrupciones. Sin acceso cruzado a bases desde las aplicaciones.

## Operación y seguridad entre servicios

- Objetivo: revisar TLS interno, rotación de secretos, alertas y procedimientos
  de recuperación antes de exponer datos reales.
- Dependencias: elegir entorno operativo y responsables.
- Complejidad: alta.
- Aceptación: decisiones de seguridad documentadas, red privada verificada,
  alertas de trabajo pendiente/revisión y restauración ensayada en un entorno
  aislado. Los cambios de autenticación o contratos requieren diseño previo.

## Validación del candidato AWS

- Objetivo: comprobar en una cuenta de pruebas la infraestructura hoy sintetizada.
- Dependencias: cuenta/región, presupuesto, cuotas, certificado, secretos e
  imágenes publicadas por digest; revisión y autorización del despliegue.
- Complejidad: alta.
- Aceptación: diff revisado, migraciones, login/roles, alta idempotente,
  recuperación y analytics verificados; restauración y costes registrados.
  LocalStack no sustituye estas comprobaciones.

La sincronización de actualizaciones/eliminaciones con billing o analytics
queda fuera del alcance actual. Sólo se incorporará si existe un requisito
funcional y se define su contrato.
