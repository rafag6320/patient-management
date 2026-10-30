# Evidencia de R08 — 30/10/2026

## Implementación

- Perfiles local/docker con los valores de desarrollo anteriores; configuración
  común y prod exigen conexiones/JWT explícitos. Compose activa docker en las
  cinco aplicaciones. El gateway conserva rutas y límites de R05, compartiendo
  las mismas URLs entre validación y proxy en cada perfil.
- El SQL de auth se mueve a db/dev/data.sql sin cambiar su contenido. Se habilita
  sólo en local/docker y fixtures test. Prod/sin perfil no inicializan datos y
  rechazan un intento de habilitar spring.sql.init fuera de desarrollo.
- H2 pasa a scope test en auth; se retira spring-boot-h2console de patient, que
  conserva su dependencia H2 de test explícita. No se añaden dependencias nuevas.
- RuntimeConfiguration por aplicación: validación anterior a datasource/Flyway y
  clientes mediante BeanFactoryPostProcessor. Comprueba las claves propias,
  formatos, puertos, timeouts y mezclas de perfiles incompatibles. Sus mensajes
  sólo contienen nombres de claves; no imprimen sus valores.
- Se conservan secretos, hashes, contraseñas, migraciones V1, contratos HTTP y
  Protobuf. No se eliminan administradores preexistentes ni se rotan credenciales.

## Comandos y resultados

```powershell
.\scripts\verify.ps1 -WithContexts
.\scripts\verify-migrations-postgres.ps1
.\scripts\verify-auth-compose.ps1 -ProjectName pm-r08-test
.\scripts\compose-smoke.ps1 -ProjectName pm-r08-full-test
```

Las pruebas se ejecutan con Java 21 y Maven 3.9.16. Datasources de pruebas:
H2 en memoria explícito para suites normales y PostgreSQL 16.15 efímero, puerto
loopback aleatorio y tmpfs para perfiles/migraciones. No se utilizan bases del
desarrollador ni despliegues externos.

| Suite normal final | Pruebas | Fallos / errores / omitidas |
| --- | ---: | --- |
| patient | 40 | 0 / 0 / 0 |
| auth | 21 | 0 / 0 / 0 |
| gateway | 54 | 0 / 0 / 0 |
| billing | 8 | 0 / 0 / 0 |
| analytics | 8 | 0 / 0 / 0 |
| **Total** | **131** | **0 / 0 / 0** |

La suite PostgreSQL ejecutó además **44 pruebas aprobadas**: 40 de migraciones
y aplicaciones de R07, y cuatro escenarios nuevos de perfiles de auth. Los
unitarios que repite ese comando no se cuentan nuevamente en esos 44 resultados.

Se comprobaron claves faltantes/puertos inválidos en las cinco aplicaciones;
URLs HTTP, brokers Kafka, datasource H2 fuera de test, clave JWT corta y mezclas
de perfiles donde corresponde. Una prueba por aplicación carga el guard en un
contexto Spring mínimo y demuestra que rechaza configuración inválida antes de
instanciar una dependencia de red.

Los cuatro escenarios PostgreSQL nuevos arrancan auth con su aplicación real:

- Prod: Flyway crea el esquema y users queda vacío; sql.init permanece never.
- Local y docker: el seed crea una única cuenta administrativa. En la base
  sintética se reemplaza su hash/rol por valores de prueba y se reinicia el
  contexto: el SQL no sobrescribe esos valores ni duplica la cuenta.
- Prod con seed forzado por argumentos: el arranque falla y se verifica que ni
  siquiera se creó el esquema de esa prueba. No se abrió su datasource.

Las inspecciones de los JAR recién construidos de auth y patient comprobaron
ausencia de H2, consola y application-test.properties. Se comparó el contenido
del seed movido con la versión original del checkout, normalizando únicamente
finales de línea: idéntico.

## Compose y conservación de datos

El flujo de autenticación pasó con perfiles principal y prod del gateway en el
proyecto nuevo pm-r08-test: login, consulta de pacientes, rechazo de JWT ausentes,
inválidos, expirados y adulterados, y 502 cuando auth se pausa o detiene. Auth y
patient utilizan el perfil docker de desarrollo en esta prueba. La ausencia de
seed en auth prod se prueba de forma independiente sobre PostgreSQL efímero.

El smoke completo pm-r08-full-test también terminó con código 0: las ocho piezas
(cinco aplicaciones, dos PostgreSQL y Kafka) estuvieron saludables. Se verificaron
OpenAPI, alta real de un paciente, llamada gRPC, publicación Kafka y consumo en
analytics. Tras restart y down/up, los registros de ambas bases y los offsets
Kafka se conservaron. Ambos scripts eliminaron al finalizar sólo sus contenedores
y redes; mantuvieron sus volúmenes propios. LocalStack no se modificó.

No se adoptaron automáticamente ni se eliminaron los volúmenes anteriores. Los
scripts aceptan proyectos de prueba específicos para permitir una ejecución
nueva sin tocar la historia de los proyectos de pruebas previos.

## Incidencias de verificación

- La primera ejecución Compose reutilizó pm-r05-test y auth no arrancó. Esos
  volúmenes proceden de las pruebas previas a Flyway y no fueron adoptados en
  R07. Se conservó su contenido y se repitió en pm-r08-test nuevo, que pasó.
  No se habilitó baseline automático ni se borraron volúmenes para continuar.
- Una inspección de JAR coincidió con el clean de la suite final y no encontró
  temporalmente el artefacto de patient. Se repitió al terminar la construcción;
  ambos JAR pasaron la inspección. No se presentó ese intento como aprobado.

## Archivos y límites

Configuraciones comunes y de perfiles de auth/patient/analytics/gateway, Compose,
RuntimeConfiguration y sus tests en las cinco aplicaciones; POM auth/patient;
reubicación del seed; AuthProfilesMigrationChecks; propiedades test; parámetros
de proyecto y lectura del fixture JWT en scripts Compose; documentación afectada.

Los tests HTTP heredados de integration-tests siguen optativos y pendientes de
R15. Prod se verificó localmente; no se acredita un despliegue AWS. La validación
de configuración no demuestra conectividad ni credenciales correctas. La consola
H2 se retira y no se reemplaza por otro endpoint.
La seguridad frente a configuración accidental no pretende impedir que un
operador reemplace deliberadamente código o artefactos. Compose sigue siendo
desarrollo y no debe utilizar sus valores de desarrollo como configuración
productiva. Las cuentas creadas por versiones previas se preservan.

Logs ignorados por Git: `.verification-r08-suite.log`,
`.verification-r08-profiles.log`, `.verification-r08-compose-fresh.log` y
`.verification-r08-full-compose.log`. Reportes actuales de cada módulo en
`.verification/<módulo>/{surefire,failsafe}-reports/`; XML PostgreSQL preservados
antes del clean en `.verification/r08-evidence/`.

Se revisó el diff completo con archivos nuevos mediante un índice Git temporal,
sin alterar el índice del usuario; sin errores de whitespace ni cambios Protobuf.
La configuración Compose pasó validación sintáctica y las fuentes/documentos
modificados se comprobaron como UTF-8. No se realizó push ni despliegue externo.
