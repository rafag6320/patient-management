-- Preflight de adopcion: ejecutar con search_path del esquema revisado.
-- No modifica tablas, filas ni historial. Los nombres de constraints pueden variar.
DO $$
BEGIN
 IF EXISTS (
  WITH expected(column_name,udt_name,character_maximum_length,is_nullable) AS (VALUES ('id','uuid',NULL::integer,'NO'),
('email','varchar',255,'NO'),
('password','varchar',255,'NO'),
('role','varchar',255,'NO')),
  actual AS (SELECT column_name::text,udt_name::text,character_maximum_length,is_nullable::text
    FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='users')
  (SELECT * FROM expected EXCEPT SELECT * FROM actual)
  UNION ALL (SELECT * FROM actual EXCEPT SELECT * FROM expected)
 ) THEN RAISE EXCEPTION 'Incompatible columns for users; adoption refused'; END IF;
 IF EXISTS (
  WITH expected(definition) AS (VALUES ('PRIMARY KEY (id)'),('UNIQUE (email)')),
  actual AS (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=current_schema() AND t.relname='users')
  (SELECT * FROM expected EXCEPT SELECT * FROM actual)
  UNION ALL (SELECT * FROM actual EXCEPT SELECT * FROM expected)
 ) THEN RAISE EXCEPTION 'Incompatible constraints for users; adoption refused'; END IF;
 IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=current_schema()
   AND table_name NOT IN ('users','flyway_schema_history'))
 THEN RAISE EXCEPTION 'Unexpected tables; verify service database before adoption'; END IF;
END $$;
