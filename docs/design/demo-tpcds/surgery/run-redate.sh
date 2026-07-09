#!/usr/bin/env bash
# Demo-TPCDS data surgery #1 — re-base all dates +OFFSET years (default 23: 1998–2003 → 2021–2026;
# decided 2026-07-09: last full year 2025, narrative present sits inside calendar 2026).
#
# Strategy: date_dim is NOT modified — it already contains correct rows (weekday, holiday,
# week_seq) for the target years. Instead every *_date_sk column in every other table is
# re-pointed via an old_sk→new_sk map built from d_date + OFFSET years. Date-typed columns
# (i_rec_start_date, s_rec_*, …) are shifted by the same interval, and c_birth_year is bumped
# so customer ages stay believable relative to the new "present".
#
# Idempotency: guarded — aborts if store_sales already reads as re-dated (max d_year >= 2010).
# Runs as the postgres superuser over the pod-local socket (tpcds_readonly obviously can't write).
#
# Usage: ./run-redate.sh [kube-context] [offset-years] [database]   (defaults: dsk, 23, hartland)
# E-2 (2026-07-09): surgery targets the demo DB `hartland` (restored from a pristine tpc-ds-1g
# dump); tpc-ds-1g itself stays pristine for the integration fixtures. Refuses to touch
# tpc-ds-1g unless you *explicitly* name it as arg 3.
set -euo pipefail
CTX="${1:-dsk}"; OFFSET="${2:-23}"; DB="${3:-hartland}"; NS="data"; POD="test-pg-1"

echo "== re-dating $DB on context=$CTX by +$OFFSET years (single transaction) =="

kubectl --context "$CTX" -n "$NS" exec -i "$POD" -c postgres -- \
  psql -1 -v ON_ERROR_STOP=1 -v off="$OFFSET" -d "$DB" -f - <<'SQL'
SELECT set_config('redate.off', :'off'::text, false);

DO $body$
DECLARE
  cur_max_year int;
  off_years    int := current_setting('redate.off')::int;
  r            record;
  n            bigint;
  total        bigint := 0;
BEGIN
  -- ---- guard: refuse to run twice -------------------------------------------------
  SELECT max(d.d_year) INTO cur_max_year
  FROM store_sales ss JOIN date_dim d ON ss.ss_sold_date_sk = d.d_date_sk;
  IF cur_max_year >= 2010 THEN
    RAISE EXCEPTION 're-date guard: store_sales max d_year is % — already re-dated', cur_max_year;
  END IF;

  -- ---- sk map: every date_dim day -> the day OFFSET years later -------------------
  CREATE TEMP TABLE _redate_map ON COMMIT DROP AS
  SELECT d1.d_date_sk AS old_sk, d2.d_date_sk AS new_sk
  FROM date_dim d1
  JOIN date_dim d2 ON d2.d_date = (d1.d_date + make_interval(years => off_years))::date;
  CREATE INDEX ON _redate_map (old_sk);
  RAISE NOTICE 'sk map: % day pairs (+% years)', (SELECT count(*) FROM _redate_map), off_years;

  -- ---- re-point every *_date_sk column outside date_dim ---------------------------
  FOR r IN
    SELECT c.table_name, c.column_name
    FROM information_schema.columns c
    JOIN information_schema.tables t
      ON t.table_schema = c.table_schema AND t.table_name = c.table_name
     AND t.table_type = 'BASE TABLE'
    WHERE c.table_schema = 'public'
      AND c.column_name LIKE '%date\_sk' ESCAPE '\'
      AND c.table_name NOT IN ('date_dim', 'dbgen_version')
    ORDER BY c.table_name, c.column_name
  LOOP
    EXECUTE format(
      'UPDATE %I t SET %I = m.new_sk FROM _redate_map m WHERE t.%I = m.old_sk',
      r.table_name, r.column_name, r.column_name);
    GET DIAGNOSTICS n = ROW_COUNT;
    total := total + n;
    RAISE NOTICE '%.% -> % rows', r.table_name, r.column_name, n;
  END LOOP;

  -- ---- shift plain date-typed columns (dim validity windows etc.) -----------------
  FOR r IN
    SELECT c.table_name, c.column_name
    FROM information_schema.columns c
    JOIN information_schema.tables t
      ON t.table_schema = c.table_schema AND t.table_name = c.table_name
     AND t.table_type = 'BASE TABLE'
    WHERE c.table_schema = 'public' AND c.data_type = 'date'
      AND c.table_name NOT IN ('date_dim', 'dbgen_version')
    ORDER BY c.table_name, c.column_name
  LOOP
    EXECUTE format(
      'UPDATE %I SET %I = (%I + make_interval(years => %s))::date WHERE %I IS NOT NULL',
      r.table_name, r.column_name, r.column_name, off_years, r.column_name);
    GET DIAGNOSTICS n = ROW_COUNT;
    total := total + n;
    RAISE NOTICE '%.% (date) -> % rows', r.table_name, r.column_name, n;
  END LOOP;

  -- ---- keep customer ages believable in the new present ---------------------------
  UPDATE customer SET c_birth_year = c_birth_year + off_years WHERE c_birth_year IS NOT NULL;
  GET DIAGNOSTICS n = ROW_COUNT;
  total := total + n;
  RAISE NOTICE 'customer.c_birth_year +% -> % rows', off_years, n;

  RAISE NOTICE 'TOTAL row-updates: %', total;
END
$body$;
SQL

echo "== ANALYZE =="
kubectl --context "$CTX" -n "$NS" exec -i "$POD" -c postgres -- \
  psql -q -v ON_ERROR_STOP=1 -d "$DB" -c "ANALYZE;"

echo "== verification =="
kubectl --context "$CTX" -n "$NS" exec -i "$POD" -c postgres -- \
  psql --csv -v ON_ERROR_STOP=1 -d "$DB" -f - <<'SQL'
SELECT 'store_sales' AS fact, min(d.d_date) AS min_date, max(d.d_date) AS max_date
FROM store_sales JOIN date_dim d ON ss_sold_date_sk = d.d_date_sk
UNION ALL
SELECT 'catalog_sales', min(d.d_date), max(d.d_date)
FROM catalog_sales JOIN date_dim d ON cs_sold_date_sk = d.d_date_sk
UNION ALL
SELECT 'web_sales', min(d.d_date), max(d.d_date)
FROM web_sales JOIN date_dim d ON ws_sold_date_sk = d.d_date_sk
UNION ALL
SELECT 'inventory', min(d.d_date), max(d.d_date)
FROM inventory JOIN date_dim d ON inv_date_sk = d.d_date_sk
UNION ALL
SELECT 'item_rec_windows', min(i_rec_start_date), max(coalesce(i_rec_end_date, i_rec_start_date))
FROM item;
SQL

echo
echo "Done. Expected spans (offset 23): sales 2021-01-02 .. 2026-01-08, inventory .. 2025-12-25."
echo "Next: pg_dump backup (see surgery/README.md), then update curated-query year params."
