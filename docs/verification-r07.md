# Evidencia de R07 — 30/10/2026

## Alcance aprobado e implementación

El usuario aprobó expresamente Flyway y la adopción manual sin baseline automático,
borrados ni aplicación sobre sus bases durante esta tarea. La decisión es D15;
el procedimiento de mantenimiento está en [migrations.md](migrations.md).

Cambios:

- POM de patient/auth: starter Flyway y soporte PostgreSQL; plugin Maven para
  mantenimiento explícito. Flyway 12.4.0 y driver PostgreSQL gestionados por Boot
  4.1.1, sin actualizar la versión de Spring ni el resto de dependencias.
- application.properties de ambos servicios: Flyway habilitado, baseline
  automático desactivado, clean deshabilitado, validación de checksums habilitada,
  Hibernate validate y defer-datasource-initialization false para inicialización
  ordenada antes de JPA. No se modificaron credenciales ni data.sql de auth.
- V1 SQL por servicio: columnas, PK, restricciones únicas y nulabilidad actuales.
- SQL de preflight de adopción, sin escrituras, por servicio.
- Perfil test: Flyway desactivado explícitamente para H2 create-drop.
- SchemaMigrationChecks en ambos módulos, tests de aplicación sobre PostgreSQL
  migrado y scripts/verify-migrations-postgres.ps1.
- README, arquitectura, decisiones, roadmap y guía local actualizados.

## Comandos ejecutados

```powershell
.\scripts\verify-migrations-postgres.ps1
.\scripts\maven.ps1 -Module patient-service verify -Pisolated-tests
.\scripts\maven.ps1 -Module auth-service verify -Pisolated-tests
```

El script de migraciones crea exclusivamente un contenedor PostgreSQL 16.15
de nombre aleatorio, puerto loopback dinámico y tmpfs; ejecuta las pruebas con
Java 21/Maven 3.9.16, UTC en ese proceso, y elimina sólo ese contenedor.
Patient/auth utilizan esquemas de prueba diferentes. No se montaron volúmenes
existentes ni se consultaron datos del desarrollador.

## Resultados

| Suite | Pruebas |
| --- | ---: |
| Patient: creación/adopción/rollback | 2 |
| Patient: CRUD y concurrencia con Flyway + Hibernate validate en PostgreSQL | 24 |
| Auth: creación/adopción/rollback | 2 |
| Auth: contexto, login, OpenAPI y JWT con Flyway + validate en PostgreSQL | 12 |
| Regresión patient: unitarios y H2 | 31 |
| Regresión auth: H2 | 12 |

Resultados finales: **40 pruebas sobre PostgreSQL y 43 de regresión unitarias/H2**
aprobadas, sin fallos, errores ni omitidas. Los tres comandos terminaron con
BUILD SUCCESS. Los unitarios de patient se ejecutan también durante la suite
PostgreSQL; no se cuentan dos veces en estos 83 resultados por clase/contexto.

Además, el script ejecutó `flyway:info flyway:validate` del plugin Maven real
para cada servicio, contra sus esquemas de aplicación aislados. Ambos terminaron
con BUILD SUCCESS y mostraron V1 aplicada. La marca de creación de esquema de
Flyway es una entrada de historial adicional, no una segunda migración SQL.

Los casos de migración verificaron:

- Base nueva: V1 aplicada y preflight de columnas/restricciones aprobado.
- Esquema antiguo generado por Hibernate con la misma estrategia snake_case que
  Spring Boot, cargado con fila sintética y copiado con estructura y datos a otro
  esquema. Preflight antes y después del baseline y snapshot de la fila idéntico,
  incluidos ID, fechas/DNI de patient y password/role de auth.
- La copia poblada sin historial rechazó migrate automático. Sólo después del
  preflight se invocó baseline explícito 1 y migrate/validate.
- Copia sin garantías de unicidad: preflight rechazado antes de crear historial.
- V2 temporal que crea una tabla y luego falla: rollback sin tabla parcial ni
  pérdida de filas, V1 conservada en historial. Se corrigió únicamente el script
  temporal nunca aplicado, se reintentó y validó. Las V1 del repositorio no se
  modificaron después de su aplicación.
- Aplicaciones reales dentro de Spring Test sobre PostgreSQL: Flyway crea las
  tablas y JPA validate acepta el esquema; pasan el CRUD/concurrencia de patient
  y login/JWT de auth. Se conservó la ejecución del seed condicional existente.

Los endpoints se verifican mediante MockMvc; las integraciones billing/Kafka de
patient se sustituyen por mocks. Esta tarea no vuelve a acreditar el sistema
completo de Compose ni la recuperación distribuida.

## Incidencias corregidas

- El fixture Hibernate inicial no tenía la estrategia snake_case de Boot y no
  generó birth_date. Se corrigió la configuración del fixture, no las entidades.
- La prueba de contexto heredada de auth exigía H2 al ejecutarse en PostgreSQL.
  Se especializó para verificar su conexión PostgreSQL e historial V1.
- Esa comprobación inicialmente contó también la entrada de creación de esquema
  de Flyway. Se corrigió para consultar la migración SQL versión 1 explícitamente.

## Evidencia y límites

Logs locales ignorados: `.verification-r07-migrations.log`,
`.verification-r07-patient.log` y `.verification-r07-auth.log`.
XML por clase en `.verification/<servicio>/{surefire,failsafe}-reports/`.

No se adoptaron ni migraron los volúmenes del usuario. Las pruebas acreditan
copias sintéticas de los esquemas generados por el código actual; una base real
con drift necesita inspección y reconciliación específica antes del baseline.
La revisión manual de triggers, permisos e índices independientes sigue siendo
responsabilidad de la adopción descrita en migrations.md.

Las V1 están listas para versionarse y deben tratarse como inmutables. No hubo
push, despliegue externo, reparación de checksums ni limpieza de bases existentes.

Se revisó el diff completo, incluidos archivos nuevos, con un índice Git temporal
sin alterar el índice del usuario. Se preservaron los cambios previos R01–R06.
