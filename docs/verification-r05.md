# Evidencia de R05 — 30/10/2026

## Implementación

- `api-gateway/src/main/resources/application.yml`: rutas comunes para ambos
  perfiles; URLs configurables de auth y patient; límites positivos de 2s.
- `application-prod.yml`: hereda las rutas y sólo cambia destinos predeterminados,
  conservando host.docker.internal. `/api/auth/login` llega a `/login`, eliminando
  dos segmentos también en prod.
- `JwtValidationGatewayFilterFactory`: rechaza Bearer ausente/vacío, valida con
  auth, limita el tiempo incluyendo liberación de su respuesta y devuelve 502
  ante fallos de conexión/timeout. Sólo después de validación exitosa llama a
  patient. Los errores y tiempos de patient quedan fuera de esa recuperación.
- `AuthProxyGatewayFilterFactory`: acota el contacto del proxy con auth y devuelve
  502 ante fallo o silencio antes de enviar la respuesta. Conserva los códigos
  que auth responde normalmente, incluido 401 de login inválido.
- `auth-service/.../service/AuthService.java`: captura también IllegalArgumentException
  al validar JWT vacío; lo trata como inválido y responde el 401 previsto.
- No se agregaron dependencias ni se modificaron versiones, credenciales,
  contratos Protobuf, rutas públicas o formatos de DTO.

Los límites se configuran con `AUTH_VALIDATION_TIMEOUT` y `AUTH_PROXY_TIMEOUT`
(por defecto `2s`). La URL `AUTH_SERVICE_ADDRESS` se comparte entre login y
validación; `PATIENT_SERVICE_ADDRESS` configura el proxy a pacientes.
La política y sus consecuencias están en D13 de [decisions.md](decisions.md).

## Verificaciones de código

Se ejecutaron con Java 21.0.12.1 y Maven Wrapper 3.9.16:

```powershell
.\scripts\maven.ps1 -Module api-gateway clean verify -Pisolated-tests
.\scripts\maven.ps1 -Module auth-service clean verify -Pisolated-tests
```

| Grupo | Pruebas | Fallos / errores / omitidas |
| --- | ---: | --- |
| Gateway: JwtValidationGatewayFilterFactoryTest | 19 | 0 / 0 / 0 |
| Gateway: AuthProxyGatewayFilterFactoryTest | 5 | 0 / 0 / 0 |
| Gateway: contexto | 1 | 0 / 0 / 0 |
| Gateway: HTTP real, perfil principal | 11 | 0 / 0 / 0 |
| Gateway: HTTP real, perfil prod | 11 | 0 / 0 / 0 |
| Auth: contexto H2, login, OpenAPI y validación JWT | 12 | 0 / 0 / 0 |
| **Total** | **59** | **0 / 0 / 0** |

Los tests de gateway usan upstreams Reactor Netty efímeros en loopback y puertos
asignados por el sistema; el servidor gateway también usa puerto aleatorio.
Auth tiene datasource H2 en memoria y propiedades de prueba explícitas que
prevalecen sobre configuración de desarrollo. No se usaron bases personales.

Se comprobaron:

- Login sin token, traducción a `/login` y preservación del 401 de login inválido.
- Validación en `/validate`, encabezado Authorization y posterior acceso a
  `/patients`, conservando query string.
- Rechazo sin acceso a patient ante token ausente, inválido o rechazado por auth.
- JWT realmente firmado con la clave configurada pero expirado, firma ajena,
  entradas malformadas y token válido en auth real con H2.
- Auth devolviendo 5xx, cerrando conexiones o sin responder: 502 y ningún acceso
  a patient. Se cubre también el proxy de login ante desconexión o silencio.
- Tiempo virtual: el límite predeterminado es 2s y cancela la validación pendiente.
  Un error o demora de patient no se convierte en error de auth.
- HTTP real de pruebas: límite configurado de 1s y respuesta antes de un margen
  de 4s. Se prueban las mismas rutas y escenarios en ambos perfiles.
- Duraciones cero/negativas rechazadas y respuestas ya enviadas no reescritas.

Reportes actuales: `.verification/api-gateway/{surefire,failsafe}-reports/`
y `.verification/auth-service/failsafe-reports/`.
Logs locales ignorados por Git: `.verification-r05-api-gateway.log` y
`.verification-r05-auth-service.log`.

## Prueba real con Compose

Comando ejecutado y finalizado correctamente:

```powershell
.\scripts\verify-auth-compose.ps1
```

El script construyó imágenes desde las fuentes actuales y utilizó exclusivamente
el proyecto `pm-r05-test`: gateway, auth, patient, billing, dos PostgreSQL y Kafka.
No necesitó analytics para este alcance. Puertos host 24004/24005, bases
25433/25434 y Kafka 29094; volúmenes propios separados del desarrollo y de R04.

Para **principal y prod**, se aprobaron estos once controles HTTP:

1. Login por `/api/auth/login`: 200 y JWT emitido por auth real.
2. Acceso por `/api/patients` con ese JWT: 200 y lista.
3. Sin token: 401.
4. JWT malformado: 401.
5. JWT expirado, con firma válida: 401.
6. JWT con firma adulterada: 401.
7. Login con password inválido: 401.
8. Auth pausado: lectura de pacientes devuelve 502.
9. Auth pausado: login devuelve 502.
10. Auth detenido: lectura de pacientes devuelve 502.
11. Auth detenido: login devuelve 502.

Se comprobó la respuesta de auth pausado con límite configurado de 2s y margen
externo de 5s por solicitud. Pausar el contenedor simula una conexión sin
respuesta; detenerlo verifica el caso de indisponibilidad real. En ambas rondas
se imprimió `OK` y el script terminó con código 0.
Los tokens y la clave existente usada para generar el fixture expirado permanecen
en memoria y no se imprimen. No se cambiaron usuarios ni credenciales existentes.

La parada final eliminó sólo contenedores/red del proyecto de prueba y conservó
sus tres volúmenes. El log está en `.verification-r05-compose.log`, ignorado por Git.

## Fallos encontrados durante el trabajo

- La primera tanda de tests HTTP falló porque el upstream de prueba comparaba
  incorrectamente `request.path()` con rutas que incluían `/`. Se corrigió el
  fixture para obtener el path desde la URI. Las dos tandas finales aprobaron.
- Se encontró la excepción no controlada de JWT vacío y se añadió su manejo y
  prueba. No se presentaron los reportes de R01–R04 como evidencia de este cambio.

## Límites

- Esta verificación de prod es local; no acredita un despliegue AWS.
- Los 2s acotan la operación reactiva de validación y el contacto del proxy con
  auth, no un plazo absoluto de entrega al cliente incluyendo toda la red.
  Una respuesta ya enviada o un stream parcial no puede cambiarse a 502.
- No se agregaron roles, refresh tokens, reintentos, recuperación distribuida
  ni cambios del CRUD. Las tareas R06–R19 mantienen su alcance pendiente.
- Los tests HTTP heredados de integration-tests siguen pendientes de R15 y no
  se ejecutaron; las pruebas nuevas de R05 tienen sus propios destinos aislados.
- Los cambios de R01–R04 que ya estaban en el árbol de trabajo se preservaron.
  No se realizó push, despliegue externo ni eliminación de volúmenes.

Se revisó el diff con un índice Git temporal, incluidos los archivos nuevos:
sin errores de whitespace ni cambios Protobuf. La configuración Compose de la
prueba pasó validación sintáctica. El índice del usuario permaneció intacto.
