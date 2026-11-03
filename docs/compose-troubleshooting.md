# Arranque de Compose con bases anteriores a Flyway

## Síntoma y causa

El 31/10/2026, `docker compose up -d --build api-gateway` construyó la imagen,
pero patient-service y auth-service finalizaron con código 1. El gateway quedó
en estado Created porque Compose exige que ambas dependencias estén saludables.
Los dos logs mostraron:

```text
Found non-empty schema(s) "public" but no schema history table.
```

Las bases conservaban el esquema anterior a Flyway. No había historial que
acreditara la adopción de V1; el rechazo es la protección prevista en
[migrations.md](migrations.md), no un fallo de compilación de Swagger.

## Recuperación aplicada en esta instalación

1. Se inspeccionaron los logs y el catálogo SQL. Los preflights de patient y auth
   se ejecutaron dentro de transacciones de sólo lectura y pasaron. Columnas y
   restricciones coincidían con V1; no había triggers de usuario ni índices
   ajenos a sus PK/unicidades.
2. Se respaldaron ambas bases completas mediante `pg_dump -Fc`, fuera de sus
   volúmenes y del repositorio. Las copias se restauraron con `pg_restore` en
   PostgreSQL 16 efímero, con almacenamiento tmpfs y puerto sólo loopback.
3. Sobre las copias se repitieron preflight, baseline manual 1, migración y
   validación. Se compararon conteos y huellas de todas las columnas de los
   registros existentes antes/después, sin imprimir los datos. Auth incluye en
   esa comparación los hashes de contraseña y roles.
4. Con patient/auth detenidos, se verificó de nuevo la identidad de las bases,
   sus huellas y los preflights. Se aplicó el mismo procedimiento mediante el
   plugin Maven de Flyway del servicio propietario:
   patient registra baseline 1 y aplica V2/V3; auth registra baseline 1.
   Las validaciones pasaron y los registros originales permanecieron idénticos.
5. No se editaron migraciones, credenciales ni datos existentes. No se habilitó
   baseline automático ni se borraron volúmenes. El PostgreSQL temporal de
   restauración se eliminó; los backups se conservaron.

Los backups de esta operación se conservaron en una carpeta externa al repositorio.
Cada instalación debe registrar privadamente su ubicación y comprobar restauración.
No incorporar dumps ni datos personales a Git. Los logs de mantenimiento quedan
ignorados bajo .verification; la ubicación de una copia no configura la aplicación.

Este procedimiento no debe repetirse automáticamente en otras instalaciones.
Una base con diferencias de esquema requiere su propia reconciliación; el
baseline no sustituye la comparación ni el backup restaurable.

## Comandos para el uso diario

Para toda la aplicación, incluidos billing, Kafka y analytics:

```powershell
docker compose up -d --build
docker compose ps
```

Seleccionar sólo `api-gateway` inicia sus dependencias declaradas, pero no todo
el sistema. Para servir únicamente Swagger, sin iniciar esas dependencias:

```powershell
docker compose up -d --build --no-deps api-gateway
```

Swagger se abre en [http://localhost:4004/swagger-ui.html](http://localhost:4004/swagger-ui.html).
El gateway puede servir la documentación sin los otros servicios; sus llamadas
de negocio necesitan que estén disponibles. Cambiar 4004 si se configuró
`GATEWAY_HOST_PORT`.

## Verificación posterior

- `docker compose config --quiet`: correcto.
- `docker compose up -d --build --wait --wait-timeout 300`: terminó con código 0.
- `docker compose ps`: los diez servicios running/healthy.
- GET de health del gateway, Swagger UI, JavaScript, OpenAPI JSON/YAML y los
  cinco catálogos: HTTP 200.
- GET `/api/patients` sin JWT: HTTP 401, conservando la protección del gateway.
- Las huellas de los registros originales de patient y auth siguieron idénticas
  después de arrancar las aplicaciones, incluido el seed condicional de auth.
- `git diff --check`: sin errores. No se hicieron pruebas POST/PUT/DELETE sobre
  estos datos; esta revisión no certifica todo el flujo de negocio ni AWS.
