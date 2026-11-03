# Swagger y contratos de la aplicación

La interfaz central está en **http://localhost:4004/swagger-ui.html** cuando el
gateway está ejecutándose. OpenAPI describe las peticiones y respuestas; Swagger
UI permite explorarlas y ejecutar las operaciones HTTP.

Para construir y abrir sólo la documentación, sin iniciar bases ni servicios
de negocio:

```powershell
docker compose up -d --build --no-deps api-gateway
```

El gateway sirve la UI aunque los destinos no estén disponibles; las llamadas
de negocio requieren sus servicios. Para construir y arrancar todo el sistema,
usar `docker compose up -d --build`.
Si hay volúmenes anteriores a Flyway, seguir primero [migrations.md](migrations.md).
El diagnóstico y la adopción de los volúmenes de esta instalación están en
[compose-troubleshooting.md](compose-troubleshooting.md).
Los requisitos, perfiles y variables están en [local-development.md](local-development.md)
y [environments.md](environments.md). Si se cambia `GATEWAY_HOST_PORT`, sustituir
4004 por ese puerto en las URLs. La UI y sus especificaciones se sirven desde
el gateway aunque los servicios de negocio no estén disponibles.

## Catálogos disponibles

El selector superior de Swagger contiene cinco documentos OpenAPI 3.0.3:

| Catálogo | JSON servido por gateway | Uso |
|---|---|---|
| API publica (gateway) | `/openapi/gateway.json` | API de clientes: autenticación, pacientes y analytics. |
| Patient interno | `/openapi/patient.json` | Contrato HTTP directo de patient-service, puerto 4000. |
| Auth interno | `/openapi/auth.json` | Contrato directo de auth-service, puerto 4005. |
| Analytics interno | `/openapi/analytics.json` | Contrato directo de analytics-service, puerto 4002. |
| Operacion interna (Actuator) | `/openapi/operations.json` | Salud, métricas y contadores operativos, con servidores por operación. |

`GET /v3/api-docs` devuelve también el contrato público completo y
`GET /v3/api-docs.yaml` su representación YAML. La UI obtiene su configuración
de `/v3/api-docs/swagger-config` y carga sus recursos desde `/swagger-ui/**`.
Se conservan las rutas de documentación propias que auth ya tenía; el catálogo
central incorpora los detalles de contratos, recuperación y roles.

Los JSON son importables en Postman por URL o archivo. Su fuente versionada está
en [static/openapi](../api-gateway/src/main/resources/static/openapi).
El servidor del catálogo público es `/`: las llamadas usan el mismo origen
que la UI, sin fijar un host de producción ni habilitar CORS adicional.

Los catálogos internos describen acceso directo. Sus URLs `localhost` suponen
procesos locales o puertos Docker publicados; los nombres `*-service` requieren
la red Docker. Compose no publica normalmente patient ni billing. Un navegador
puede bloquear las llamadas directas por CORS o contenido mixto; importar el
catálogo en Postman desde una red con acceso al servicio. Esta entrega no agrega
puertos ni proxies para los endpoints internos. La seguridad JWT y roles de
pacientes/analytics se aplica en el gateway: los servicios directos necesitan
red privada, según [recovery-operations.md](recovery-operations.md).

## API pública: siete operaciones de negocio

| Método | Ruta en gateway | Acceso | Éxito |
|---|---|---|---|
| POST | `/api/auth/login` | Público; credenciales de una cuenta existente | 200 con `token` |
| GET | `/api/auth/validate` | JWT válido, sin exigir un rol particular | 200 vacío; header `X-Validated-Roles` |
| GET | `/api/patients` | ADMIN o USER | 200 con array de pacientes |
| POST | `/api/patients` | ADMIN | 200 con snapshot del paciente creado |
| PUT | `/api/patients/{id}` | ADMIN | 200 con paciente actualizado |
| DELETE | `/api/patients/{id}` | ADMIN | 204 vacío |
| GET | `/api/analytics/summary` | ADMIN o USER | 200 con `patientsCreated` |

Las rutas directas son `/login`, `/validate`, `/patients`, `/patients/{id}` y
`/analytics/summary`. No existe GET de un paciente por ID, registro HTTP de
usuarios ni REST de billing. Las respuestas automáticas HEAD/OPTIONS del
framework no constituyen operaciones de negocio adicionales.

### Probar desde Swagger

1. Elegir **API publica (gateway)**.
2. Abrir `POST /api/auth/login`, pulsar **Try it out** y enviar las credenciales
   de una cuenta existente. Los ejemplos `@example.com` son ilustrativos.
3. Copiar `token` de la respuesta, pulsar **Authorize** y pegar sólo el JWT,
   sin escribir `Bearer`. Swagger agrega el prefijo.
4. Ejecutar los endpoints habilitados para el rol de esa cuenta.
5. Para crear, enviar `Idempotency-Key`, por ejemplo
   `patient-demo-20261031-0001`. Reutilizar la misma clave y cuerpo si hay un
   timeout o 503. Crear, actualizar y eliminar desde la UI modifica datos reales
   del entorno al que apunte el gateway.

La UI no persiste el JWT al recargar ni envía la especificación a un validador
remoto. Los filtros existentes continúan comprobando autenticación y roles.
`X-Validated-Roles` es una respuesta de auth, no una credencial que el cliente
pueda utilizar para concederse permisos.

### Datos y recuperación

Ejemplo de POST:

```json
{
  "name": "Ana Pérez",
  "email": "ana@example.com",
  "address": "Calle Ejemplo 123",
  "dateOfBirth": "1990-05-20",
  "registeredDate": "2026-10-31",
  "DNI": "30123456"
}
```

- `DNI` se escribe con mayúsculas en la petición; la respuesta usa `dni`.
  Es una cadena de 1 a 10 dígitos ASCII que representa un entero entre 0 y
  2147483647. Se admiten ceros iniciales; la respuesta los normaliza.
- Nombre, email y dirección son obligatorios, con máximos de 100, 255 y 200
  caracteres. Las fechas no vacías deben ser fechas calendario `yyyy-MM-dd`.
- `registeredDate` es obligatorio en POST y no se devuelve en el DTO. En PUT
  es opcional y no se actualiza; si se envía un valor no vacío, debe ser fecha
  válida. No hay restricciones de edad agregadas por esta documentación.
- `Idempotency-Key` es opcional, de 16 a 128 caracteres `[A-Za-z0-9._:-]`.
  La misma clave con el mismo payload lógico devuelve el snapshot original,
  incluso después de actualizar el paciente. La misma clave con otro payload
  o un recurso eliminado devuelve 409. Otro alta con email/DNI duplicado
  devuelve 400. Véase [patient-registration-semantics.md](patient-registration-semantics.md).
- Un 200 de POST confirma almacenamiento del paciente, billing y registro durable
  de la intención del evento. Kafka y analytics son posteriores y recuperables.
  Un 503 puede dejar el alta pendiente/incierta; un 502 puede indicar revisión
  de billing o indisponibilidad de auth. No se debe asumir que falló todo.
- PUT/DELETE devuelven 409 mientras billing no esté confirmado.
- `patientsCreated` cuenta altas históricas distintas procesadas por analytics.
  Es eventualmente consistente; actualizar/eliminar no reduce el contador.
- Todas las interfaces HTTP aceptan `X-Correlation-Id`. Se conserva si cumple
  `[A-Za-z0-9._:-]{1,64}`; de otro modo se genera un UUID. Se devuelve en respuesta,
  incluidos errores, para seguir la operación sin guardar el token.

Las especificaciones detallan 400, 401, 403, 404, 409, 500, 502 y 503 donde
corresponde. Los errores de patient pueden devolver `{"error":"..."}` o un mapa
campo/mensaje. Errores del framework pueden tener otro formato; auth y filtros
JWT también responden sin cuerpo. No se presupone un DTO de error universal.

## Endpoints operativos

| GET | Servicios que lo exponen |
|---|---|
| `/actuator` | patient 4000, billing HTTP 4001, analytics 4002, gateway 4004, auth 4005 |
| `/actuator/health` | Los cinco servicios |
| `/actuator/health/{component}` | Según componente configurado y visibilidad; puede devolver 404 |
| `/actuator/metrics` | patient y analytics |
| `/actuator/metrics/{requiredMetricName}` | patient y analytics; filtro repetible `tag=clave:valor` |
| `/actuator/registrations` | patient: billing pendiente/revisión, eventos pendientes/confirmados y recuperaciones |
| `/actuator/analyticswork` | analytics: `patientsCreated` y `quarantined` |

Estos endpoints no se publican mediante `/api`. Health no muestra detalles por
defecto; los nombres de componentes y métricas dependen del contexto activo.
Swagger no cambia su exposición ni su seguridad. Los contadores de operación
se describen con sus nombres exactos en `operations.json`.

## Billing: contrato gRPC

Swagger UI ejecuta HTTP; billing expone una RPC binaria, no un endpoint REST.
La descripción inicial del catálogo público incluye también su método, campos,
validaciones, respuesta y errores para consultarlos desde la propia UI.
El contrato está en [billing_service.proto](../billing-service/src/main/proto/billing_service.proto)
y su [copia de patient](../patient-service/src/main/proto/billing_service.proto).

| Propiedad | Valor |
|---|---|
| Servicio / método | `BillingService/CreateBillingAccount` |
| Transporte | gRPC unary; puerto 9001 (4001 es sólo HTTP operativo) |
| Dirección Docker | `billing-service:9001` dentro de su red |
| Request | `BillingRequest`: `patientId` (string #1), `name` (string #2), `email` (string #3) |
| Response | `BillingResponse`: `accountId` (string #1, UUID), `success` (bool #2) |
| Metadata opcional | `correlation-id` |

`patientId` debe ser no vacío y tener como máximo 255 caracteres; no se exige
UUID en el contrato de billing. Nombre no vacío, máximo 100; email válido no
vacío, máximo 255. Repetir un `patientId` válido devuelve la misma cuenta UUID
persistida y conserva el nombre/email iniciales. Se valida todo request, incluso
si ya existe la cuenta. El éxito devuelve `success=true`; no hay éxito simulado.

| Status gRPC | Significado |
|---|---|
| `OK` | Cuenta creada o recuperada de almacenamiento |
| `INVALID_ARGUMENT` | Datos inválidos |
| `UNAVAILABLE` | Fallo de almacenamiento transitorio o de conexión clasificado por el adaptador |
| `INTERNAL` | Otro fallo inesperado |

El transporte también puede producir errores de conexión/deadline. Patient
clasifica los resultados para recuperación/revisión; no hay atomicidad entre
SQL, gRPC y Kafka.

Para probar con Postman, crear una petición **gRPC**, importar el `.proto`,
seleccionar `BillingService / CreateBillingAccount` y usar una dirección con
acceso al puerto 9001. Compose normal no lo publica en el host. Para procesos
locales usar `localhost:9001`; en las pruebas aisladas utilizar el puerto
publicado por su override. Ejemplo de mensaje:

```json
{"patientId":"swagger-demo-001","name":"Ana Pérez","email":"ana@example.com"}
```

Analytics recibe `PatientEvent` por Kafka: no es un endpoint HTTP ni gRPC. Su
resultado consultable es `/api/analytics/summary`; el contrato de eventos se
describe en [architecture.md](architecture.md) y los Protobuf existentes.

## Mantenimiento y verificación

Los catálogos son explícitos porque las rutas del gateway son filtros, no
controladores de negocio anotados. Al cambiar un contrato aprobado, actualizar
el JSON público, su catálogo directo y esta guía. No se alteran DTO ni Protobuf
para adaptarlos a Swagger. La única dependencia añadida es springdoc WebFlux UI
3.2.0 en gateway, compatible con la línea Spring Boot 4 según su
[documentación oficial](https://springdoc.org/faq.html).

```powershell
.\scripts\maven.ps1 -Module api-gateway clean verify -Pisolated-tests
.\scripts\check-contracts.ps1
```

`SwaggerDocumentationIT` usa el perfil test, puerto aleatorio y destinos
loopback inactivos: no conecta a bases ni a servicios del desarrollador.
Comprueba UI/JS/CSS/configuración, cinco catálogos, referencias locales,
unicidad de operationId, OpenAPI público, roles/DTO y cobertura de todas las
rutas de los controladores de negocio actuales. La suite de routing existente
comprueba que la incorporación de Swagger conserva el proxy y la autorización.
Las pruebas no certifican despliegue AWS ni ejecutan mutaciones mediante la UI.

Verificación local del 31/10/2026: `clean verify -Pisolated-tests` aprobó 72
pruebas de gateway (43 unitarias y 29 de contexto/HTTP, incluidas las cuatro
de documentación). `check-contracts.ps1` confirmó ambas parejas Protobuf
idénticas. Se corrigió el aviso de copia de esquemas de springdoc aplicando el
catálogo mediante `OpenApiCustomizer`, con generación OpenAPI 3.0 explícita;
se comprobaron los esquemas completos para no perder restricciones al generar
JSON/YAML. La comparación inicial detectó diferencias de orden en `required`,
normalizadas en los catálogos. No quedaron fallos en la suite completa.

En la verificación inicial se comprobó la entrega HTTP de la página, JavaScript,
CSS y configuración, sin sesión visual de navegador, Compose ni AWS.
Posteriormente se verificó el arranque real de Compose al resolver la adopción
de bases descrita en compose-troubleshooting.md; no hubo despliegue AWS.
Los JSON requieren mantenimiento cuando cambien los contratos: la cobertura
de rutas no demuestra por sí sola todos los posibles comportamientos del backend.
