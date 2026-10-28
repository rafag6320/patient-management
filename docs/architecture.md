# Arquitectura actual

## Alcance y evidencia
Este documento describe el código inspeccionado el 28/10/2026.
No certifica el funcionamiento del sistema desplegado.

## Organización
Existen cinco aplicaciones Spring Boot independientes:
patient-service, auth-service, billing-service,
analytics-service y api-gateway.

integration-tests e infrastructure son módulos auxiliares.
No existe un POM agregador raíz.

## Plataforma declarada
Java 21 y Spring Boot 4.1.1.
Gateway utiliza Spring Cloud 2025.1.3 y WebFlux.
Los servicios HTTP utilizan Spring MVC donde está configurado.
Patient y auth utilizan JPA.

## Responsabilidades
- Patient: listar, crear, actualizar y eliminar pacientes.
- Auth: autenticar usuarios y emitir/validar JWT.
- Billing: responder CreateBillingAccount mediante gRPC;
  actualmente devuelve una respuesta fija sin persistencia.
- Analytics: consumir PatientEvent y registrarlo en logs.
- Gateway: enrutar HTTP y consultar a auth para validar JWT.

## Contratos HTTP actuales
Patient:
- GET /patients: 200 con una lista.
- POST /patients: 200 con el paciente creado.
- PUT /patients/{id}: 200 con el paciente actualizado.
- DELETE /patients/{id}: 204.

Auth:
- POST /login: 200 con token o 401.
- GET /validate: valida el encabezado Authorization.

Gateway, configuración principal:
- /api/patients/** elimina el segmento /api.
- /api/auth/** elimina los segmentos /api/auth.

El perfil prod difiere y presenta una incompatibilidad
de enrutamiento hacia auth.

## Comunicación
- Gateway -> auth: HTTP GET /validate.
- Gateway -> patient y auth: proxy HTTP.
- Patient -> billing: gRPC CreateBillingAccount.
- Patient -> Kafka: PatientEvent Protobuf, topic patient-events.
- Kafka -> analytics: consumer group analytics-service.

Los contratos Protobuf están duplicados entre módulos.

## Persistencia
- Patient es propietario de patient_db en Compose.
- Auth es propietario de auth_db en Compose.
- Billing y analytics no tienen persistencia implementada.
- No existe acceso cruzado entre bases en el código inspeccionado.
- Patient y auth utilizan ddl-auto=update.
- Auth ejecuta un seed SQL condicional.
- No existen migraciones versionadas.

## Consistencia del alta
Secuencia actual:
guardar paciente -> invocar billing -> publicar evento -> responder.

No hay recuperación persistente ni atomicidad entre esos pasos.
Una integración fallida puede dejar un paciente guardado
aunque la solicitud termine con error.

## Seguridad
Auth verifica passwords con BCrypt y firma JWT HS256.
Gateway delega su validación a auth.
Los roles del token no se utilizan actualmente para autorizar
operaciones de pacientes.
La configuración contiene valores de desarrollo.

## Infraestructura local
Compose define cinco aplicaciones, dos PostgreSQL y Kafka KRaft.
Existen volúmenes para ambas bases y Kafka.
Sólo auth-db tiene un healthcheck declarado.

## Infraestructura CDK
Se declaran VPC, RDS, MSK, ECS/Fargate, Cloud Map,
CloudWatch Logs y ALB.
La configuración contiene defectos pendientes y no representa
un despliegue validado.
El script apunta a LocalStack, cuyo arranque no está definido
en Compose.

## Límites conocidos
Billing y analytics no implementan resultados de negocio persistentes.
No hay estrategia de recuperación distribuida, autorización por roles,
migraciones, observabilidad completa ni CI.
Los cambios propuestos se registran en decisions.md y roadmap.md.