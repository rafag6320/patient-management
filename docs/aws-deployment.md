# Preparación AWS — R18/R19

Estado: candidato local revisable; **no desplegado ni publicado en AWS**.
La síntesis no verifica cuotas, precios, versiones disponibles por región,
conectividad efectiva, certificado, bootstrap CDK ni permisos de una cuenta real.

## Artefactos y comandos locales

Desde la raíz, con Java 21, Maven Wrapper, PowerShell 7 y Node 22:

```powershell
./scripts/synth.ps1
./scripts/review-infrastructure.ps1
./scripts/verify-localstack.ps1
./scripts/build-release.ps1 -Version roadmap-r11-r19-20261030
```

`infrastructure` mantiene CDK 2.178.1; elimina el SDK AWS v1 completo sin uso y
agrega JUnit sólo para tests y exec-maven-plugin para síntesis reproducible.
No requiere credenciales para los comandos anteriores. La salida es siempre
`.verification/infrastructure/cdk.out`, independientemente del directorio del IDE.
El provider de descubrimiento usa Node 22 explícito, evitando el runtime antiguo
que seleccionaba por defecto esta versión de CDK.

- `patient-management-registry.template.json`: cinco repositorios ECR con tags
  inmutables, escaneo y retención. Se despliega antes de publicar imágenes.
- `patient-management-candidate.template.json`: infraestructura nueva propuesta;
  no es una actualización automática del viejo stack `localstack`.
- `patient-management-emulation.template.json`: únicamente un secreto ficticio.
- `.verification/infrastructure/review.txt`: resumen, SHA-256 y parámetros.
- `.verification/release/release.json`: imágenes locales, IDs, versión, revisión
  Git, estado dirty y hash del conjunto de fuentes. Un árbol dirty no se presenta
  como contenido íntegramente identificado por el commit Git inicial.

Para comparar con un template revisado anteriormente:

```powershell
./scripts/review-infrastructure.ps1 -BaselineTemplate <template-anterior.json>
```

Esto produce `review.diff` local. Sin baseline desplegado, se revisa el template
como infraestructura nueva; no se inventa un diff contra una cuenta AWS.

## Candidato de red, datos y servicios

VPC con dos zonas, subredes públicas para ALB, privadas con salida para tareas y
privadas aisladas para RDS/MSK. Un NAT reduce el alcance inicial; implica coste y
un punto de dependencia de zona que se debe revisar antes de producción.

Sólo el ALB es público: HTTPS 443 y redirección HTTP 80. El certificado ACM es un
parámetro obligatorio. Gateway se conecta por DNS Cloud Map a auth/patient/analytics;
patient utiliza billing-service.patient-management.local:9001. Las tareas no
reciben IP pública. Los security groups permiten esas conexiones y el acceso de
cada servicio a su propia base, no acceso cruzado entre bases.

Cuatro PostgreSQL RDS independientes: patient_db, auth_db, billing_db y analytics_db.
Se propone PostgreSQL 16.15, instancias t3.small Multi-AZ, almacenamiento cifrado,
backups 14 días, eliminación protegida y snapshot al retirar el recurso.
La disponibilidad de esa revisión del motor se debe confirmar en la región elegida.
Los JDBC usan sslmode=verify-full y el bundle público versionado de `docker/certs`.

MSK propone 3.9.x, dos brokers en dos zonas, TLS y SASL/SCRAM-SHA-512, sin acceso
anónimo. Dos secretos nuevos con KMS propio y prefijo AmazonMSK_ se asocian al
cluster: publicador y consumidor. El descubrimiento de brokers usa un custom
resource con permiso derivado de getBootstrapBrokers sobre el ARN del cluster.
No hay localhost ni puertos LocalStack en la configuración AWS.

El perfil `prod,aws` de patient/analytics activa SCRAM usando credenciales inyectadas;
local/docker siguen con Kafka de desarrollo. No se introducen dependencias IAM
Kafka en las aplicaciones ni claves estáticas AWS.

Las cinco imágenes se referencian con **repo@sha256:digest**; los cinco digests son
parámetros obligatorios. El rol de ejecución ECS recibe permisos de pull, logs y
lectura de sus secretos mediante los grants CDK. La aplicación no requiere un rol
con acceso a las bases/secretos de otros servicios. Los secretos van en `Secrets`,
no como texto en `Environment`. Logs se conservan 30 días y sus grupos se retienen.

## Entradas que debe completar el operador antes de pedir despliegue

- Cuenta, región, entorno, presupuesto, cuotas y responsables de operación/datos.
- Certificado ACM válido en esa región y DNS público deseado para el ALB.
- ARN de un secreto JWT existente con campo JSON `secret`, de al menos 32 bytes;
  no reutilizar valores de desarrollo ni modificar cuentas/credenciales sin autorización.
- Digests publicados y comprobados para authserviceDigest, patientserviceDigest,
  billingserviceDigest, analyticsserviceDigest y apigatewayDigest.
- Revisión de IAM del despliegue/CDK bootstrap. CI sólo tiene contents:read y no
  contiene credenciales ni permisos de despliegue. No se solicita AdministratorAccess
  para las aplicaciones.
- Administración inicial de usuarios/roles de auth por un procedimiento autorizado;
  prod no ejecuta el seed. No se crea una cuenta pública predeterminada.

## Secuencia de publicación y despliegue, pendiente de aprobación

1. Verificar en AWS las versiones RDS/MSK, cuotas, coste, certificado y acceso a
   los registros. Con una sesión temporal autorizada, confirmar identidad/región.
2. Revisar el bootstrap CDK y su rol de ejecución. Desplegar únicamente registry
   tras aprobar su template, sin retirar ni reemplazar recursos existentes.
3. Para cada imagen del manifiesto, ejecutar docker tag y docker push al repositorio
   `ACCOUNT.dkr.ecr.REGION.amazonaws.com/patient-management/SERVICE:VERSION`.
   Autenticarse con `aws ecr get-login-password --region REGION | docker login
   --username AWS --password-stdin REGISTRY`, sin imprimir ni almacenar el token.
4. Consultar `aws ecr describe-images --repository-name patient-management/SERVICE
   --image-ids imageTag=VERSION --region REGION`. Registrar el imageDigest publicado;
   el ID local de Docker no se confunde con el digest del manifiesto de ECR.
5. Revisar el diff del candidato con la cuenta elegida antes de crear un changeset.
   Usar la CLI CDK fijada en 2.178.1 con `--app .verification/infrastructure/cdk.out`.
   La síntesis ya está preparada; ningún comando local de este proyecto despliega
   automáticamente el candidato.
6. Aprobar template, parámetros, permisos y costes. Desplegar primero con
   ServiceDesiredCount=0 para preparar red, bases, MSK y secretos. No se deben
   iniciar consumidores antes de revisar topics/ACL y esquema.
7. Desde un cliente privado autorizado, crear patient-events (3 particiones,
   replicación 2) y ACL: patient_publisher puede escribir/describir el topic y usar
   productor idempotente; analytics_consumer puede leer/describir el topic y operar
   el grupo analytics-service. Revisar allow.everyone.if.no.acl.found=false antes
   de poner tráfico. No se publican secretos en comandos/logs ni repositorio.
8. Ejecutar migraciones como tareas ECS del mismo digest/perfil y secretos, sin
   exponer las bases al host. Para bases existentes, backup restaurable y adopción
   manual de V1 según migrations.md; V2/V3 de patient se aplican después. No activar
   ddl-auto=update, baseline automático ni clean para sortear un error.
9. Tras verificar esquemas, habilitar ServiceDesiredCount=1. Flyway valida el historial
   al iniciar cada aplicación. Comprobar health HTTP, RPC real, login, permisos,
   alta sintética con clave, cuenta billing y resultado analytics antes del tráfico.
10. Registrar digests, versiones Flyway y métricas pendientes/revisión. Aprobar el
    resultado antes de dirigir DNS/tráfico del entorno real.

## Recuperación y límites que se deben revisar

Una migración fallida bloquea el servicio; inspeccionar el historial y el error,
no reparar/borrar automáticamente. Cambios de aplicación compatibles pueden volver
al digest anterior. Una migración ya aplicada se corrige con una nueva versión:
volver la imagen no deshace el esquema ni cuentas/eventos remotos.

Antes de migrar, probar restauración en bases nuevas. Para un incidente con efectos
remotos, conservar los patientId y el registro durable; restaurar varias bases a
puntos incompatibles puede romper idempotencia. Reconciliar en copia y autorizar
la recuperación antes de modificar datos persistentes.

La red interna HTTP/gRPC del candidato es privada, pero no tiene mTLS. SCRAM requiere
la configuración y comprobación operativa de ACL descrita; no se presenta la síntesis
como prueba de autenticación de un broker real. Revisar además TLS interno, alarmas,
retención de snapshots personales, rotación de secretos/CA, capacidad y el NAT único
antes de exponer datos reales. Nada de eso queda validado por LocalStack.

## Emulación comprobable y fuentes

`verify-localstack.ps1` crea un contenedor nuevo de LocalStack **4.14.0**, copia sólo
el template de emulación y comprueba crear/leer un secreto ficticio por CloudFormation.
El contenedor se elimina al finalizar; el LocalStack existente no se toca. No emula
RDS, MSK, Fargate, IAM efectivo, copias de seguridad ni certificados productivos.
La versión antigua se fija para el alcance local sin introducir tokens/licencias;
antes de actualizar, revisar los requisitos actuales de autenticación del proveedor.

Fuentes técnicas consultadas el 30/10/2026:

- [Inyección de secretos ECS](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/specifying-sensitive-data-tutorial.html).
- [TLS y CA RDS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html).
- [Versiones MSK](https://docs.aws.amazon.com/msk/latest/developerguide/supported-kafka-versions.html).
- [Asociación SCRAM CloudFormation](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-msk-batchscramsecret.html).
- [Runtimes Lambda](https://docs.aws.amazon.com/lambda/latest/dg/lambda-runtimes.html).
- [Cambios de imágenes LocalStack](https://blog.localstack.cloud/localstack-single-image-next-steps/).
