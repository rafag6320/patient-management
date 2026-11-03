# Migraciones PostgreSQL — R07

Estrategia Flyway aprobada por el usuario el 30/10/2026. Cada servicio mantiene
su propio `db/migration` y su historial `flyway_schema_history` en su base.
Flyway 12.4.0 está gestionado por Spring Boot 4.1.1; se añaden el starter Flyway
y su soporte PostgreSQL. Hibernate usa `validate`, no altera el esquema.

Desde R09, billing también dispone de V1 propia en su base nueva billing_db.
La versión anterior no persistía cuentas, por lo que no existe un esquema antiguo
de billing que adoptar automáticamente ni se importan IDs simulados `12345`.
Una billing_db desconocida y no vacía sin historial se rechaza; requiere revisión
antes de cualquier adopción. No se modificaron las V1 de patient/auth.
El plugin Maven de mantenimiento descrito abajo está configurado para patient y
auth; billing aplica sus migraciones al arrancar mediante el starter de Flyway.

## Bases nuevas

Al arrancar patient/auth, Flyway aplica V1 y Hibernate valida después. Las
migraciones sólo crean las tablas actuales y sus restricciones, sin nuevos
campos ni cambios de credenciales. Desde R08, el seed condicional de auth se
conserva en `db/dev/data.sql` y sólo se habilita en local/docker y fixtures de
test. No se modificó su contenido. Sin perfil y en prod no se carga.

Se mantienen deshabilitados el baseline automático y `clean`. Si una base ya
tiene tablas pero no historial, la aplicación se niega a adoptarla automáticamente.
Por eso un `docker compose up --build` sobre volúmenes antiguos puede detener
patient/auth hasta completar el procedimiento siguiente. No borrar los volúmenes.

## Adopción de bases existentes

La verificación inicial se realizó con datos sintéticos y copias de esquemas
generados por Hibernate en PostgreSQL 16 efímero. El 31/10/2026 se adoptaron
también las bases anteriores de esta instalación Compose, tras backup,
restauración y validación en copias; evidencia en
[compose-troubleshooting.md](compose-troubleshooting.md).
Antes de aplicarlo a otra base real:

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

- Una migración aplicada es inmutable. Añadir V2, V3, etc.; no editar V1 ni cambiar
  checksums con `repair` para ocultar una discrepancia.
- Las migraciones SQL de esta entrega se ejecutan transaccionalmente en PostgreSQL.
  Si fallan, inspeccionar el log y el historial. Corregir un script únicamente si
  se confirmó que nunca quedó aplicado; volver a ejecutar migrate/validate en la
  copia antes del destino. Se probó el rollback de DDL y preservación de filas.
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

## Evolución R12–R17

Patient añade V2__registration_work.sql y V3__registration_observability.sql.
V1 permanece intacta: una base nueva recibe las tres; una base adoptada en 1
aplica V2/V3 después del preflight/baseline manual original. Ese preflight es
para adoptar el esquema V1, no para validar el esquema extendido después de V2.
No se cambia el estado de pacientes históricos ni se los encola retroactivamente.

Analytics incorpora su propia V1__analytics_result.sql en analytics_db, sin datos
persistentes anteriores que adoptar automáticamente. Tablas de resultado y
cuarentena son propias; no se accede al esquema de patient/billing. Una base
no vacía sin historial se rechaza, igual que las demás.

Se probaron convergencia/adopción y rollback con el esquema extendido. El test
transaccional usa una V99 temporal nunca aplicada, para no reutilizar versiones
reales. Ninguna migración aplicada del repositorio se modifica para esos tests.
La política de snapshots, retención y reanudación del trabajo está documentada
en recovery-operations.md. Esos ensayos no ejecutaron mantenimiento sobre bases
personales; la adopción posterior de esta instalación se registra arriba.
