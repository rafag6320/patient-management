# Migraciones PostgreSQL

Cada servicio mantiene su propio db/migration y su historial flyway_schema_history
en su base. Flyway 12.4.0 está gestionado por Spring Boot 4.1.1. Hibernate usa
validate; no altera el esquema. Analytics utiliza JDBC.

## Esquemas actuales

| Servicio | Migraciones | Contenido |
| --- | --- | --- |
| patient | V1, V2 y V3 | Pacientes, trabajo durable y observabilidad |
| auth | V1 | Usuarios |
| billing | V1 | Cuentas con patientId único |
| analytics | V1 | Identidades procesadas y cuarentena |

En bases nuevas se aplica todo el historial al arrancar. El seed condicional de
auth se conserva en db/dev/data.sql, habilitado sólo con local/docker y fixtures
de test. Sin perfil y en prod no se carga.

Se mantienen deshabilitados el baseline automático y clean. Una base poblada sin
historial se rechaza. No borrar volúmenes para saltar esa protección.

## Adopción de bases existentes

El procedimiento de adopción contempla esquemas V1 anteriores de patient y auth.
Ambos tienen preflight SQL y plugin Maven de mantenimiento. Billing y analytics
no tienen un procedimiento automático de importación de esquemas anteriores;
una base desconocida necesita revisión específica.

Antes de adoptar patient o auth:

1. Detener las escrituras del servicio propietario y confirmar host, puerto,
   nombre de base, usuario y esquema. Patient y auth se adoptan por separado.
2. Crear un backup completo con `pg_dump -Fc`, almacenarlo fuera de los volúmenes
   y probar su restauración en una base aislada. Nunca trabajar sobre la única copia.
3. Inspeccionar columnas, tipos, nulabilidad, PK, unicidad e índices de la copia.
   Ejecutar el preflight del servicio con `psql -v ON_ERROR_STOP=1` y el
   `search_path` del esquema revisado:
   `src/main/resources/db/adoption/verify-schema.sql`.
   El preflight no modifica nada. Rechaza columnas/restricciones incompatibles y
   tablas de otro servicio; compara semántica de constraints, no sus nombres.
   Los índices independientes, triggers, permisos, extensiones y otras dependencias
   requieren revisión del operador; el preflight no es una auditoría completa.
4. Si hay diferencias, detener la adopción. No marcar baseline, eliminar tablas
   ni modificar V1 para disimularlas. Preparar una reconciliación específica sobre
   una copia y obtener la aprobación que corresponda a cambios de datos/esquema.
5. Sólo con esquema compatible y backup restaurable, configurar temporalmente
   `PM_MIGRATION_URL`, `PM_MIGRATION_USER` y `PM_MIGRATION_PASSWORD` con el destino
   revisado. No guardarlos en archivos versionados ni imprimirlos.
6. Revisar `flyway:info`, registrar baseline **1** explícitamente y ejecutar
   validación/migración con el módulo propietario:

```powershell
# Sustituir patient-service por auth-service para su base independiente.
.\scripts\maven.ps1 -Module patient-service flyway:info
.\scripts\maven.ps1 -Module patient-service flyway:baseline
.\scripts\maven.ps1 -Module patient-service flyway:migrate flyway:validate
```

El plugin Maven también utiliza Flyway 12.4.0 y soporte PostgreSQL gestionado.
No usa por defecto las credenciales de la aplicación: exige las variables de
mantenimiento anteriores. Verificar que la URL y el esquema sean los mismos
del backup y del preflight. El baseline no comprueba por sí solo que el esquema
sea correcto: marca V1 como preexistente y la omite, por eso la comparación es
obligatoria. No establecer `baseline-on-migrate=true`.

7. Arrancar el servicio con `ddl-auto=validate`, comprobar su historial y probar
   sus endpoints. Comparar conteos y datos —incluidos hashes de auth— con la copia
   previa. Restablecer las variables temporales y habilitar escrituras sólo tras
   la verificación. Usar el perfil del entorno previsto: el seed sólo está
   habilitado en los perfiles de desarrollo indicados en environments.md.

## Evolución y recuperación

- Una migración aplicada es inmutable. Añadir una versión posterior; no editar
  migraciones anteriores ni cambiar checksums con `repair` para ocultar una discrepancia.
- Las migraciones SQL actuales se ejecutan transaccionalmente en PostgreSQL.
  Si fallan, inspeccionar el log y el historial. Corregir un script únicamente si
  se confirmó que nunca quedó aplicado; volver a ejecutar migrate/validate en la
  copia antes del destino. Los tests de migración comprueban rollback de DDL y
  preservación de filas.
- Si una versión ya se aplicó, corregir hacia adelante con una nueva migración.
  Si la recuperación exige restaurar un backup, hacerlo en una base nueva,
  verificarla y planificar el cambio de destino; no sobrescribir la base actual
  ni eliminar sus volúmenes sin aprobación explícita.
- SQL no transaccional (por ejemplo, ciertos índices concurrentes) necesita una
  estrategia específica; las pruebas actuales no acreditan esos casos.
- Los tests H2 mantienen `create-drop` y Flyway desactivado explícitamente. Las
  migraciones PostgreSQL se prueban con Docker, no se declaran compatibles con H2.

## Verificación reproducible

```powershell
.\scripts\verify-migrations-postgres.ps1
.\scripts\maven.ps1 -Module patient-service verify -Pisolated-tests
.\scripts\maven.ps1 -Module auth-service verify -Pisolated-tests
```

El primer comando usa un contenedor aleatorio, loopback/puerto dinámico y tmpfs;
no monta volúmenes existentes. Prueba esquema nuevo, copia de esquema Hibernate
con datos, rechazo de drift y de adopción automática, baseline explícito, rollback
y recuperación; también arranca ambas aplicaciones con Flyway y JPA validate.
El JVM de esa prueba usa UTC para evitar el alias Windows rechazado por PostgreSQL.

Referencias: [inicialización en Spring Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html),
[baseline de Flyway](https://documentation.red-gate.com/flyway/reference/commands/baseline),
[baseline automático](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting).

## Trabajo durable y datos históricos

Patient V2__registration_work.sql y V3__registration_observability.sql se aplican
después de V1 o del baseline manual 1. El preflight es para adoptar V1, no para
validar el esquema extendido. Las migraciones no cambian pacientes históricos ni
los encolan retroactivamente.

La política actual de snapshots y la reanudación están en
[recuperación y operación](recovery-operations.md). Los ensayos de migraciones
usan bases efímeras y datos sintéticos, no instalaciones del desarrollador.
