# CA pública de Amazon RDS

`global-bundle.pem` se descargó el 30/10/2026 desde
https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem.
SHA-256: `90a6f1a7978a1b1b096dca6c57511ca48d3b8cf052f8e704baf358f34ea2f21b`.

No contiene secretos ni claves privadas. Se copia a las cuatro imágenes con
PostgreSQL para `sslmode=verify-full` en la preparación AWS. Local/docker siguen
usando sus bases aisladas sin activar el perfil aws. Revisar vigencias y la
actualización del bundle antes de un despliegue; nunca desactivar la verificación
para resolver una rotación. Fuente:
[SSL/TLS y certificados RDS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html).
