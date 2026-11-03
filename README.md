# Patient Management

Backend Java 21 con cinco aplicaciones Spring Boot, PostgreSQL, gRPC y Kafka.

**Swagger:** [http://localhost:4004/swagger-ui.html](http://localhost:4004/swagger-ui.html).
Consultar la [guía de endpoints HTTP, operación y billing gRPC](docs/swagger.md).

**Bases existentes:** antes de arrancar esta versión con los volúmenes anteriores,
seguir [la adopción de migraciones](docs/migrations.md). Flyway no registra un
baseline automáticamente y Hibernate ya no actualiza esquemas.

La guía de herramientas, puertos, ejecución y pruebas está en
[docs/local-development.md](docs/local-development.md).
Los perfiles y las variables obligatorias están en
[docs/environments.md](docs/environments.md).
El estado de cada entrega y su evidencia se registra en
[docs/roadmap.md](docs/roadmap.md).

```powershell
# Compilar y ejecutar tests unitarios, sin servicios externos
.\scripts\verify.ps1

# Incluir contextos Spring aislados y transporte gRPC en puerto aleatorio
.\scripts\verify.ps1 -WithContexts

# Construir e inspeccionar el sistema con volúmenes de prueba propios
.\scripts\compose-smoke.ps1

# Gateway y autenticación, incluidos fallos de auth (PowerShell 7)
.\scripts\verify-auth-compose.ps1
```

Consultar también [arquitectura](docs/architecture.md),
[decisiones](docs/decisions.md) e [instrucciones para agentes](AGENTS.md).


El alta usa billing real y recuperación durable con Idempotency-Key opcional.
Kafka y analytics se completan después del 200, conservando el trabajo pendiente.
Guía de [recuperación, roles y verificación](docs/recovery-operations.md),
[decisiones](docs/decisions.md) y [preparación AWS sin desplegar](docs/aws-deployment.md).
