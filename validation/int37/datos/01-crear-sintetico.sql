-- =====================================================================================================
-- NEXORA AI — INT-3.7 — CONJUNTO DE DATOS SINTÉTICOS (FICTICIOS)
--
-- Estos datos son INVENTADOS. No proceden de Wellnode ni de ninguna otra empresa. Los nombres
-- («POZO FICTICIO 001», «CAMPO FICTICIO ALFA», «OPERADORA FICTICIA S.A.») lo indican de forma explícita.
-- Solo reproducen la ESTRUCTURA mínima de wm_well y bkw_electronic_logs que usan las siete definiciones
-- semánticas de la demo (P1, P2, P3). Los tipos son los supuestos por las entidades JPA de wellnode-report
-- (texto y timestamp sin zona); los tipos reales de Wellnode siguen pendientes de verificación.
--
-- Reproducible e idempotente: crea (si no existen) la base nexora_demo_sintetico, el esquema sintetico, las
-- dos tablas y el rol de solo lectura nexora_sintetico_reader; vacía las dos tablas y vuelve a cargar
-- exactamente las mismas filas. No toca ninguna otra base, esquema ni rol.
--
-- Ejecución (NO ejecutar sin autorización): Initialize-Int37SyntheticDb.ps1, que pasa la contraseña del rol
-- desde el almacén de secretos como variable de entorno (nunca en este fichero ni en la línea de comandos):
--   docker exec -e NEXORA_SINTETICO_READER_PASSWORD -i nexora-reports-postgres \
--     psql -U nexora_reports_owner -d postgres -v ON_ERROR_STOP=1 -q -f -
-- Requiere psql 15 o superior (\getenv); la imagen postgres:17.6 lo cumple.
-- =====================================================================================================

\set ON_ERROR_STOP on
\getenv reader_password NEXORA_SINTETICO_READER_PASSWORD
\if :{?reader_password}
\else
  \echo 'Falta la variable de entorno NEXORA_SINTETICO_READER_PASSWORD'
  \quit
\endif

-- 1. Base de datos y rol (en la base postgres) -----------------------------------------------------------
SELECT 'CREATE DATABASE nexora_demo_sintetico'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'nexora_demo_sintetico') \gexec

SELECT format('CREATE ROLE nexora_sintetico_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION PASSWORD %L', :'reader_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexora_sintetico_reader') \gexec
SELECT format('ALTER ROLE nexora_sintetico_reader WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION PASSWORD %L', :'reader_password') \gexec
\unset reader_password
ALTER ROLE nexora_sintetico_reader SET default_transaction_read_only = on;

COMMENT ON DATABASE nexora_demo_sintetico IS 'NEXORA INT-3.7: DATOS SINTÉTICOS (FICTICIOS). No contiene datos de Wellnode.';
REVOKE ALL ON DATABASE nexora_demo_sintetico FROM PUBLIC;
GRANT CONNECT ON DATABASE nexora_demo_sintetico TO nexora_sintetico_reader;

-- 2. Estructura (en la base nexora_demo_sintetico) ------------------------------------------------------
\connect nexora_demo_sintetico

REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS sintetico;
COMMENT ON SCHEMA sintetico IS 'NEXORA INT-3.7: DATOS SINTÉTICOS (FICTICIOS).';
REVOKE ALL ON SCHEMA sintetico FROM PUBLIC;

CREATE TABLE IF NOT EXISTS sintetico.wm_well (
    dbid        bigint       PRIMARY KEY,
    uid         varchar(64)  NOT NULL UNIQUE,
    name        varchar(128) NOT NULL,
    field       varchar(128),
    statuswell  varchar(64),
    state       varchar(64),
    operator    varchar(128)
);
COMMENT ON TABLE sintetico.wm_well IS 'SINTÉTICO: pozos ficticios con la estructura mínima de wm_well (P1, P2).';

CREATE TABLE IF NOT EXISTS sintetico.bkw_electronic_logs (
    id          bigint       PRIMARY KEY,
    uid_well    varchar(64),
    start_date  timestamp without time zone,
    end_date    timestamp without time zone,
    description varchar(256)
);
COMMENT ON TABLE sintetico.bkw_electronic_logs IS 'SINTÉTICO: registros ficticios con la estructura mínima de bkw_electronic_logs (P3).';

-- 3. Datos (siempre los mismos) ------------------------------------------------------------------------
BEGIN;
TRUNCATE sintetico.bkw_electronic_logs, sintetico.wm_well;

-- 20 pozos: campo ALFA 7, BETA 6, GAMMA 5, sin campo 2; estatus ACTIVO 8, EN PERFORACION 5, CERRADO 4,
-- ABANDONADO 2, sin estatus 1.
INSERT INTO sintetico.wm_well (dbid, uid, name, field, statuswell, state, operator) VALUES
    (1001, 'SINT-UID-001', 'POZO FICTICIO 001', 'CAMPO FICTICIO ALFA', 'ACTIVO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1002, 'SINT-UID-002', 'POZO FICTICIO 002', 'CAMPO FICTICIO ALFA', 'ACTIVO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1003, 'SINT-UID-003', 'POZO FICTICIO 003', 'CAMPO FICTICIO ALFA', 'CERRADO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1004, 'SINT-UID-004', 'POZO FICTICIO 004', 'CAMPO FICTICIO ALFA', 'ACTIVO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1005, 'SINT-UID-005', 'POZO FICTICIO 005', 'CAMPO FICTICIO ALFA', 'EN PERFORACION', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1006, 'SINT-UID-006', 'POZO FICTICIO 006', 'CAMPO FICTICIO ALFA', 'CERRADO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1007, 'SINT-UID-007', 'POZO FICTICIO 007', 'CAMPO FICTICIO ALFA', 'ACTIVO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1008, 'SINT-UID-008', 'POZO FICTICIO 008', 'CAMPO FICTICIO BETA', 'EN PERFORACION', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1009, 'SINT-UID-009', 'POZO FICTICIO 009', 'CAMPO FICTICIO BETA', 'CERRADO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1010, 'SINT-UID-010', 'POZO FICTICIO 010', 'CAMPO FICTICIO BETA', 'ACTIVO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1011, 'SINT-UID-011', 'POZO FICTICIO 011', 'CAMPO FICTICIO BETA', 'EN PERFORACION', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1012, 'SINT-UID-012', 'POZO FICTICIO 012', 'CAMPO FICTICIO BETA', 'ABANDONADO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1013, 'SINT-UID-013', 'POZO FICTICIO 013', 'CAMPO FICTICIO BETA', 'ACTIVO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1014, 'SINT-UID-014', 'POZO FICTICIO 014', 'CAMPO FICTICIO GAMMA', 'EN PERFORACION', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1015, 'SINT-UID-015', 'POZO FICTICIO 015', 'CAMPO FICTICIO GAMMA', 'ABANDONADO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1016, 'SINT-UID-016', 'POZO FICTICIO 016', 'CAMPO FICTICIO GAMMA', 'ACTIVO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1017, 'SINT-UID-017', 'POZO FICTICIO 017', 'CAMPO FICTICIO GAMMA', 'EN PERFORACION', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1018, 'SINT-UID-018', 'POZO FICTICIO 018', 'CAMPO FICTICIO GAMMA', NULL, 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1019, 'SINT-UID-019', 'POZO FICTICIO 019', NULL, 'ACTIVO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.'),
    (1020, 'SINT-UID-020', 'POZO FICTICIO 020', NULL, 'CERRADO', 'ESTADO FICTICIO', 'OPERADORA FICTICIA S.A.');

-- 33 registros: 32 entre el 30 sep y el 9 oct de 2026 y 1 sin fecha. Incluye los bordes del intervalo de P3:
-- 2026-09-30 23:59:59 (fuera), 2026-10-01 00:00:00 (dentro), 2026-10-07 23:59:59 (dentro),
-- 2026-10-08 00:00:00 (fuera). El 3 de octubre no tiene registros.
INSERT INTO sintetico.bkw_electronic_logs (id, uid_well, start_date, end_date, description) VALUES
    (5001, 'SINT-UID-004', TIMESTAMP '2026-09-30 08:15:00', TIMESTAMP '2026-09-30 09:00:00', 'ACTIVIDAD SINTETICA 001'),
    (5002, 'SINT-UID-007', TIMESTAMP '2026-09-30 23:59:59', TIMESTAMP '2026-10-01 00:44:59', 'ACTIVIDAD SINTETICA 002'),
    (5003, 'SINT-UID-010', TIMESTAMP '2026-10-01 00:00:00', TIMESTAMP '2026-10-01 00:45:00', 'ACTIVIDAD SINTETICA 003'),
    (5004, 'SINT-UID-013', TIMESTAMP '2026-10-01 09:30:00', TIMESTAMP '2026-10-01 10:15:00', 'ACTIVIDAD SINTETICA 004'),
    (5005, 'SINT-UID-016', TIMESTAMP '2026-10-01 17:45:00', TIMESTAMP '2026-10-01 18:30:00', 'ACTIVIDAD SINTETICA 005'),
    (5006, 'SINT-UID-019', TIMESTAMP '2026-10-02 06:00:00', TIMESTAMP '2026-10-02 06:45:00', 'ACTIVIDAD SINTETICA 006'),
    (5007, 'SINT-UID-002', TIMESTAMP '2026-10-02 08:20:00', TIMESTAMP '2026-10-02 09:05:00', 'ACTIVIDAD SINTETICA 007'),
    (5008, 'SINT-UID-005', TIMESTAMP '2026-10-02 11:10:00', TIMESTAMP '2026-10-02 11:55:00', 'ACTIVIDAD SINTETICA 008'),
    (5009, 'SINT-UID-008', TIMESTAMP '2026-10-02 14:40:00', TIMESTAMP '2026-10-02 15:25:00', 'ACTIVIDAD SINTETICA 009'),
    (5010, 'SINT-UID-011', TIMESTAMP '2026-10-02 22:05:00', TIMESTAMP '2026-10-02 22:50:00', 'ACTIVIDAD SINTETICA 010'),
    (5011, 'SINT-UID-014', TIMESTAMP '2026-10-04 07:00:00', TIMESTAMP '2026-10-04 07:45:00', 'ACTIVIDAD SINTETICA 011'),
    (5012, 'SINT-UID-017', TIMESTAMP '2026-10-04 10:30:00', TIMESTAMP '2026-10-04 11:15:00', 'ACTIVIDAD SINTETICA 012'),
    (5013, 'SINT-UID-020', TIMESTAMP '2026-10-04 13:15:00', TIMESTAMP '2026-10-04 14:00:00', 'ACTIVIDAD SINTETICA 013'),
    (5014, 'SINT-UID-003', TIMESTAMP '2026-10-04 19:50:00', TIMESTAMP '2026-10-04 20:35:00', 'ACTIVIDAD SINTETICA 014'),
    (5015, 'SINT-UID-006', TIMESTAMP '2026-10-05 01:10:00', TIMESTAMP '2026-10-05 01:55:00', 'ACTIVIDAD SINTETICA 015'),
    (5016, 'SINT-UID-009', TIMESTAMP '2026-10-05 05:25:00', TIMESTAMP '2026-10-05 06:10:00', 'ACTIVIDAD SINTETICA 016'),
    (5017, 'SINT-UID-012', TIMESTAMP '2026-10-05 09:00:00', TIMESTAMP '2026-10-05 09:45:00', 'ACTIVIDAD SINTETICA 017'),
    (5018, 'SINT-UID-015', TIMESTAMP '2026-10-05 12:35:00', TIMESTAMP '2026-10-05 13:20:00', 'ACTIVIDAD SINTETICA 018'),
    (5019, 'SINT-UID-018', TIMESTAMP '2026-10-05 16:45:00', TIMESTAMP '2026-10-05 17:30:00', 'ACTIVIDAD SINTETICA 019'),
    (5020, 'SINT-UID-001', TIMESTAMP '2026-10-05 21:30:00', TIMESTAMP '2026-10-05 22:15:00', 'ACTIVIDAD SINTETICA 020'),
    (5021, 'SINT-UID-004', TIMESTAMP '2026-10-06 10:00:00', TIMESTAMP '2026-10-06 10:45:00', 'ACTIVIDAD SINTETICA 021'),
    (5022, 'SINT-UID-007', TIMESTAMP '2026-10-06 18:00:00', TIMESTAMP '2026-10-06 18:45:00', 'ACTIVIDAD SINTETICA 022'),
    (5023, 'SINT-UID-010', TIMESTAMP '2026-10-07 02:00:00', TIMESTAMP '2026-10-07 02:45:00', 'ACTIVIDAD SINTETICA 023'),
    (5024, 'SINT-UID-013', TIMESTAMP '2026-10-07 06:30:00', TIMESTAMP '2026-10-07 07:15:00', 'ACTIVIDAD SINTETICA 024'),
    (5025, 'SINT-UID-016', TIMESTAMP '2026-10-07 09:45:00', TIMESTAMP '2026-10-07 10:30:00', 'ACTIVIDAD SINTETICA 025'),
    (5026, 'SINT-UID-019', TIMESTAMP '2026-10-07 12:00:00', TIMESTAMP '2026-10-07 12:45:00', 'ACTIVIDAD SINTETICA 026'),
    (5027, 'SINT-UID-002', TIMESTAMP '2026-10-07 15:15:00', TIMESTAMP '2026-10-07 16:00:00', 'ACTIVIDAD SINTETICA 027'),
    (5028, 'SINT-UID-005', TIMESTAMP '2026-10-07 20:40:00', TIMESTAMP '2026-10-07 21:25:00', 'ACTIVIDAD SINTETICA 028'),
    (5029, 'SINT-UID-008', TIMESTAMP '2026-10-07 23:59:59', TIMESTAMP '2026-10-08 00:44:59', 'ACTIVIDAD SINTETICA 029'),
    (5030, 'SINT-UID-011', TIMESTAMP '2026-10-08 00:00:00', TIMESTAMP '2026-10-08 00:45:00', 'ACTIVIDAD SINTETICA 030'),
    (5031, 'SINT-UID-014', TIMESTAMP '2026-10-08 11:00:00', TIMESTAMP '2026-10-08 11:45:00', 'ACTIVIDAD SINTETICA 031'),
    (5032, 'SINT-UID-017', TIMESTAMP '2026-10-09 09:00:00', TIMESTAMP '2026-10-09 09:45:00', 'ACTIVIDAD SINTETICA 032'),
    (5033, 'SINT-UID-020', NULL, NULL, 'ACTIVIDAD SINTETICA 033 (SIN FECHA)');
COMMIT;

-- 4. Permisos mínimos del lector -----------------------------------------------------------------------
REVOKE ALL ON ALL TABLES IN SCHEMA sintetico FROM PUBLIC, nexora_sintetico_reader;
GRANT USAGE ON SCHEMA sintetico TO nexora_sintetico_reader;
GRANT SELECT ON sintetico.wm_well, sintetico.bkw_electronic_logs TO nexora_sintetico_reader;

-- 5. Comprobación: el lector no puede escribir (todo debe ser false) -----------------------------------
SELECT c.relname,
       has_table_privilege('nexora_sintetico_reader', c.oid, 'INSERT')   AS ins,
       has_table_privilege('nexora_sintetico_reader', c.oid, 'UPDATE')   AS upd,
       has_table_privilege('nexora_sintetico_reader', c.oid, 'DELETE')   AS del,
       has_table_privilege('nexora_sintetico_reader', c.oid, 'TRUNCATE') AS trn,
       (SELECT count(*) FROM sintetico.wm_well) AS pozos,
       (SELECT count(*) FROM sintetico.bkw_electronic_logs) AS registros
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'sintetico' AND c.relkind = 'r'
 ORDER BY c.relname;
