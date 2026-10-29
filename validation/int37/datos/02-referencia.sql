-- =====================================================================================================
-- NEXORA AI — INT-3.7 — SQL DE REFERENCIA SOBRE LOS DATOS SINTÉTICOS (solo lectura)
--
-- Mismas reglas que Reports: COUNT(*) por fila del objeto base, agrupación por la dimensión, orden
-- ascendente con NULL al final; P3 con intervalo semiabierto [2026-10-01 00:00, 2026-10-08 00:00) en hora de
-- pared (start_date es timestamp sin zona; la zona de la fuente, America/Mexico_City, no altera los límites).
--
-- Resultado esperado: datos/esperado.json. Ejecución (NO sin autorización):
--   Test-Int37EndToEnd.ps1 -Reference   (o bien, manualmente)
--   Get-Content datos/02-referencia.sql -Raw | docker exec -i nexora-reports-postgres \
--     psql -U nexora_reports_owner -d nexora_demo_sintetico -v ON_ERROR_STOP=1 -q -tA -f -
-- =====================================================================================================

\set ON_ERROR_STOP on
BEGIN TRANSACTION READ ONLY;

-- Resultado único en JSON, comparable con datos/esperado.json
SELECT json_build_object(
  'P1', (SELECT coalesce(json_agg(json_build_array(campo, numero_pozos) ORDER BY campo ASC NULLS LAST), '[]')
           FROM (SELECT field AS campo, count(*) AS numero_pozos
                   FROM sintetico.wm_well GROUP BY field) p1),
  'P2', (SELECT coalesce(json_agg(json_build_array(estatus_pozo, numero_pozos) ORDER BY estatus_pozo ASC NULLS LAST), '[]')
           FROM (SELECT statuswell AS estatus_pozo, count(*) AS numero_pozos
                   FROM sintetico.wm_well GROUP BY statuswell) p2),
  'P3', (SELECT coalesce(json_agg(json_build_array(fecha_actividad, numero_actividades) ORDER BY fecha_actividad ASC NULLS LAST), '[]')
           FROM (SELECT CAST(date_trunc('day', start_date) AS date) AS fecha_actividad, count(*) AS numero_actividades
                   FROM sintetico.bkw_electronic_logs
                  WHERE start_date >= TIMESTAMP '2026-10-01 00:00:00'
                    AND start_date <  TIMESTAMP '2026-10-08 00:00:00'
                  GROUP BY 1) p3)
)::text;

COMMIT;

-- Consultas equivalentes, legibles (para revisarlas a mano):
--
-- P1: SELECT field AS campo, count(*) AS numero_pozos FROM sintetico.wm_well
--       GROUP BY field ORDER BY field ASC NULLS LAST;
-- P2: SELECT statuswell AS estatus_pozo, count(*) AS numero_pozos FROM sintetico.wm_well
--       GROUP BY statuswell ORDER BY statuswell ASC NULLS LAST;
-- P3: SELECT CAST(date_trunc('day', start_date) AS date) AS fecha_actividad, count(*) AS numero_actividades
--       FROM sintetico.bkw_electronic_logs
--      WHERE start_date >= TIMESTAMP '2026-10-01 00:00:00' AND start_date < TIMESTAMP '2026-10-08 00:00:00'
--      GROUP BY 1 ORDER BY 1 ASC NULLS LAST;
