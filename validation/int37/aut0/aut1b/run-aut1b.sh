#!/bin/bash
# AUT-1b sandbox integration: -RotatePostgres against REAL PostgreSQL 17.6 containers (throwaway, FICTITIOUS secrets).
# Nothing touches the user's machine. docker exec ... psql is served by the shim (/work/aut0/bin/docker):
#   with -h  -> TCP to the published port (server sees the Docker gateway: pg_hba "host all all all scram-sha-256");
#   without  -> the container's Unix socket mounted on the host (pg_hba "local all all trust").
set -u
W=/work/aut0; R=$W/NexoraAI; I37=$R/Nexora-Intelligence/validation/int37; L=$W/pg/run; OUT=$L/steps.log
export NEXORA_INT37_HOME=$W/pg/home PATH=$W/bin:$PATH
SE=$R/Nexora-Semantic/docker/.env; RE=$R/Nexora-Reports/docker/.env
SC=nexora-semantic-dev-postgres-1; RC=nexora-reports-postgres
WEAK=f1x8   # same shape as the Windows state: 4 characters, one value shared by all nine credentials
killsim(){ ps -eo pid,args | awk -v p="$1" '$2=="python3" && $3 ~ p {print $1}' | xargs -r kill; }
killjava(){ ps -eo pid,args | awk -v p="$1" '$2 ~ /java$/ && $0 ~ p {print $1}' | xargs -r kill; }
startkc(){ (cd $W && nohup python3 sim/keycloak_sim.py "$R/Nexora-Semantic/docker/keycloak/nexora-realm.json" "$SE" 8180 $L/kc-requests.log > $L/kc.out 2>&1 &); sleep 2; }
waitfor(){ for i in $(seq 1 120); do curl -s -o /dev/null "$1" && return 0; sleep 1; done; echo "TIMEOUT $1"; return 1; }
step(){ echo | tee -a $OUT; echo "################ $1" | tee -a $OUT; }
run(){ (cd $I37 && pwsh -NoProfile -File "$@" > $L/last.out 2>&1); rc=$?; sed 's/\x1b\[[0-9;]*m//g' $L/last.out | tee -a $OUT; echo "-> exit $rc" | tee -a $OUT; }
getv(){ grep "^$2=" "$1" | cut -d= -f2-; }
# login check through TCP (scram path). Prints OK/FAIL only, never the value.
login(){ # port role db value
  if PGPASSWORD="$4" psql -h 127.0.0.1 -p "$1" -U "$2" -d "$3" -X -w -tAc 'select 1' >/dev/null 2>&1; then echo OK; else echo FAIL; fi; }
show_logins(){ # label
  echo "-- $1" | tee -a $OUT
  for spec in "5433 $(getv $SE SEMANTIC_DB_APP_USER) nexora_semantic SEMANTIC_DB_APP_PASSWORD $SE" \
              "5433 $(getv $SE SEMANTIC_DB_OWNER_USER) nexora_semantic SEMANTIC_DB_OWNER_PASSWORD $SE" \
              "5433 postgres nexora_semantic POSTGRES_PASSWORD $SE" \
              "5434 nexora_reports_app nexora_reports REPORTS_DB_APP_PASSWORD $RE" \
              "5434 nexora_reader synthetic_erp SYNTHETIC_READER_PASSWORD $RE" \
              "5434 nexora_reports_owner nexora_reports REPORTS_DB_OWNER_PASSWORD $RE"; do
    set -- $spec
    v=$(getv $5 $4)
    printf '   %-22s .env=%s  valor-inicial=%s  len(.env)=%s\n' "$2" "$(login $1 $2 $3 "$v")" "$(login $1 $2 $3 $WEAK)" "${#v}" | tee -a $OUT
  done; }
journal_state(){ f=$NEXORA_INT37_HOME/rotation-journal.env; if [ -f $f ]; then echo "   diario: $(grep -E '^(TYPE|TARGET|STATE)=' $f | tr '\n' ' ')" | tee -a $OUT; else echo "   diario: (no existe)" | tee -a $OUT; fi; }
pgrun(){ # name port socketdir extra-env...
  local n=$1 p=$2 s=$3; shift 3
  rm -rf $s; mkdir -p $s; chmod 1777 $s
  docker run -d --name $n -p 127.0.0.1:$p:5432 -v $s:/tmp "$@" postgres:17.6 >/dev/null; }

# ---------------------------------------------------------------- reset
killjava nexora-; killsim keycloak_sim; sleep 1
docker rm -f $SC $RC semantic-postgres nexora-trust-postgres >/dev/null 2>&1
rm -rf $W/pg/home $L; mkdir -p $L $W/pg/home
rm -rf $R/Nexora-Intelligence/validation && cp -r /work/int37/Nexora-Intelligence/validation $R/Nexora-Intelligence/
cat > $SE <<E
# SANDBOX AUT-1b: valores ficticios (forma del estado de Windows: 4 caracteres, el mismo en las nueve)
POSTGRES_PASSWORD=$WEAK
SEMANTIC_DB_OWNER_USER=nexora_semantic_owner
SEMANTIC_DB_OWNER_PASSWORD=$WEAK
SEMANTIC_DB_APP_USER=nexora_semantic_app
SEMANTIC_DB_APP_PASSWORD=$WEAK
KC_BOOTSTRAP_ADMIN_PASSWORD=$WEAK
KC_DEV_USER_PASSWORD=$WEAK
KC_REPORTS_CLIENT_SECRET=$WEAK
KC_TENANT_DEMO_ID=11111111-1111-4111-8111-111111111111
KC_TENANT_OTHER_ID=22222222-2222-4222-8222-222222222222
E
cat > $RE <<E
# SANDBOX AUT-1b: valores ficticios
REPORTS_DB_OWNER_USER=nexora_reports_owner
REPORTS_DB_OWNER_PASSWORD=$WEAK
REPORTS_DB_APP_PASSWORD=$WEAK
SYNTHETIC_READER_PASSWORD=$WEAK
E
cp $SE $L/semantic.env.before; cp $RE $L/reports.env.before

pgrun $SC 5433 $W/pgsock/semantic -e POSTGRES_DB=nexora_semantic -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=$WEAK
pgrun $RC 5434 $W/pgsock/reports -e POSTGRES_USER=nexora_reports_owner -e POSTGRES_PASSWORD=$WEAK -e POSTGRES_DB=nexora_reports
sleep 7
# same bootstrap as docker/postgres/init of Semantic and Reports (the sandbox image runs no init scripts)
PGPASSWORD=$WEAK psql -h 127.0.0.1 -p 5433 -U postgres -d nexora_semantic -q -v ON_ERROR_STOP=1 -v w=$WEAK <<'SQL'
CREATE ROLE nexora_semantic_owner LOGIN PASSWORD :'w';
CREATE ROLE nexora_semantic_app LOGIN PASSWORD :'w';
REVOKE ALL ON DATABASE nexora_semantic FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE nexora_semantic TO nexora_semantic_owner;
GRANT CONNECT ON DATABASE nexora_semantic TO nexora_semantic_app;
SQL
export PGPASSWORD=$WEAK; P="psql -h 127.0.0.1 -p 5434 -U nexora_reports_owner -q -v ON_ERROR_STOP=1"
$P -d nexora_reports -v w=$WEAK <<'SQL'
CREATE ROLE nexora_reports_app LOGIN PASSWORD :'w';
REVOKE ALL ON DATABASE nexora_reports FROM PUBLIC;
GRANT CONNECT ON DATABASE nexora_reports TO nexora_reports_app;
SQL
$P -d nexora_reports -c "CREATE DATABASE synthetic_erp"
$P -d synthetic_erp -f $R/Nexora-Reports/docker/postgres/init/sql/synthetic-erp.sql > $L/synthetic-init.log 2>&1
$P -d synthetic_erp -v reader_password=$WEAK <<'SQL'
CREATE ROLE nexora_reader LOGIN PASSWORD :'reader_password';
REVOKE ALL ON DATABASE synthetic_erp FROM PUBLIC;
GRANT CONNECT ON DATABASE synthetic_erp TO nexora_reader;
REVOKE ALL ON SCHEMA public FROM nexora_reader;
GRANT USAGE ON SCHEMA plant, finance, docs TO nexora_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA plant, finance, docs TO nexora_reader;
ALTER ROLE nexora_reader SET default_transaction_read_only = on;
SQL
unset PGPASSWORD
startkc

step "Versión del servidor y reglas pg_hba reales (sin contraseñas)"
for c in "5433 postgres" "5434 nexora_reports_owner"; do set -- $c
  PGPASSWORD=$WEAK psql -h 127.0.0.1 -p $1 -U $2 -d postgres -X -tA -c "select version()" | cut -c1-40 | tee -a $OUT
  PGPASSWORD=$WEAK psql -h 127.0.0.1 -p $1 -U $2 -d postgres -X -tA -c "select type||' '||array_to_string(database,',')||' '||array_to_string(user_name,',')||' '||coalesce(address,'-')||' '||auth_method from pg_hba_file_rules order by rule_number" | sed 's/^/   /' | tee -a $OUT
  PGPASSWORD=$WEAK psql -h 127.0.0.1 -p $1 -U $2 -d postgres -X -tA -c "show password_encryption" | sed 's/^/   password_encryption=/' | tee -a $OUT
done
show_logins "estado inicial"

step "Semantic arrancado con las credenciales actuales (pool Hikari de nexora_semantic_app y Flyway con el propietario)"
(cd $I37 && nohup pwsh -NoProfile -File ./Start-Int37Service.ps1 -Service Semantic > $L/semantic-1.log 2>&1 &); waitfor http://localhost:8080/actuator/health && curl -s http://localhost:8080/actuator/health | tee -a $OUT; echo | tee -a $OUT

step "-Check (estado inicial)"; run ./Update-Int37Credentials.ps1 -Check
step "-RotatePostgres -Target Semantic -WhatIf"; run ./Update-Int37Credentials.ps1 -RotatePostgres -Target Semantic -WhatIf
show_logins "tras -WhatIf (no debe cambiar nada)"; journal_state
step "-RotatePostgres -Target Semantic"; run ./Update-Int37Credentials.ps1 -RotatePostgres -Target Semantic
show_logins "tras rotar Semantic"; journal_state
echo "-- Semantic sigue arrancado con el pool anterior (conexiones abiertas):" | tee -a $OUT; curl -s http://localhost:8080/actuator/health | tee -a $OUT; echo | tee -a $OUT
step "Reiniciar Semantic con el .env nuevo (Flyway con el propietario nuevo, pool con la app nueva)"
killjava nexora-semantic; sleep 3
(cd $I37 && nohup pwsh -NoProfile -File ./Start-Int37Service.ps1 -Service Semantic > $L/semantic-2.log 2>&1 &); waitfor http://localhost:8080/actuator/health && curl -s http://localhost:8080/actuator/health | tee -a $OUT; echo | tee -a $OUT
grep -m1 -o "Successfully validated [0-9]* migrations\|Schema .* is up to date.*\|Successfully applied [0-9]* migrations.*" $L/semantic-2.log | tee -a $OUT
T=$(curl -s -d grant_type=password -d client_id=nexora-semantic-dev -d username=platform.admin -d password=$WEAK http://localhost:8180/realms/nexora/protocol/openid-connect/token | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))")
curl -s -o /dev/null -w "   GET /api/v1/admin/tenants (lee la base con el pool nuevo): HTTP %{http_code}\n" -H "Authorization: Bearer $T" "http://localhost:8080/api/v1/admin/tenants?size=5" | tee -a $OUT
curl -s -o /dev/null -w "   POST /api/v1/admin/tenants (escribe con el pool nuevo): HTTP %{http_code}\n" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"key":"demo","name":"demo"}' http://localhost:8080/api/v1/admin/tenants | tee -a $OUT
DEMO=$(curl -s -H "Authorization: Bearer $T" "http://localhost:8080/api/v1/admin/tenants?size=200" | python3 -c "import sys,json;print([t['id'] for t in json.load(sys.stdin)['items'] if t['key']=='demo'][0])")
unset T

step "-RotatePostgres -Target Reports -WhatIf"; run ./Update-Int37Credentials.ps1 -RotatePostgres -Target Reports -WhatIf
step "-RotatePostgres -Target Reports"; run ./Update-Int37Credentials.ps1 -RotatePostgres -Target Reports
show_logins "tras rotar Reports"; journal_state
step "Arrancar Reports con el .env nuevo (Flyway con nexora_reports_owner, pool con nexora_reports_app)"
cat > $NEXORA_INT37_HOME/state.json <<E
{"tenantId":"$DEMO","dataSourceId":"33333333-3333-4333-8333-333333333333","database":{"host":"127.0.0.1","port":5434,"name":"nexora_sintetico","user":"nexora_sintetico_reader"},"alias":"sintetico"}
E
printf 'NEXORA_SINTETICO_READER_PASSWORD=SandboxLectorFicticio0000000009\n' > $NEXORA_INT37_HOME/secrets.env; chmod 600 $NEXORA_INT37_HOME/secrets.env
(cd $I37 && nohup pwsh -NoProfile -File ./Start-Int37Service.ps1 -Service Reports > $L/reports.log 2>&1 &); waitfor http://localhost:8090/actuator/health && curl -s http://localhost:8090/actuator/health | tee -a $OUT; echo | tee -a $OUT
grep -m1 -o "Successfully validated [0-9]* migrations\|Schema .* is up to date.*\|Successfully applied [0-9]* migrations.*" $L/reports.log | tee -a $OUT
grep -m1 -o "Started [A-Za-z]* in [0-9.]* seconds" $L/reports.log | tee -a $OUT
rm -f $NEXORA_INT37_HOME/secrets.env $NEXORA_INT37_HOME/state.json
killjava nexora-reports; sleep 2
step "-Check tras AUT-1b (Keycloak y almacén siguen pendientes: deben aparecer solo ellos)"; run ./Update-Int37Credentials.ps1 -Check

# ---------------------------------------------------------------- interruption: kill -9 after an ALTER, before the .env
crash_test(){ # target port role db key envfile label
  local target=$1 port=$2 role=$3 db=$4 key=$5 envf=$6 label=$7
  step "Interrupción real ($label): kill -9 tras cambiar $role en la base y antes de escribir el .env"
  local before; before=$(getv $envf $key); cp $envf $L/env.crash.before
  (cd $I37 || exit 1; SHIM_DELAY=1.5 exec pwsh -NoProfile -File ./Update-Int37Credentials.ps1 -RotatePostgres -Target $target > $L/crash-$label.out 2>&1 & echo $! > $L/crash.pid)
  local pid; pid=$(cat $L/crash.pid); local killed=0
  for i in $(seq 1 600); do
    if [ "$(login $port $role $db "$before")" = FAIL ]; then kill -9 $pid; killed=1; break; fi
    sleep 0.1
  done
  sleep 1
  st=$(ps -o stat= -p $pid 2>/dev/null | tr -d ' '); case "$st" in ''|Z*) alive=NO;; *) alive=SI;; esac
  echo "   kill -9 enviado: $killed · proceso vivo después: $alive · última línea de su salida: $(sed 's/\x1b\[[0-9;]*m//g' $L/crash-$label.out | grep -v '^$' | tail -1 | cut -c1-90)" | tee -a $OUT
  if cmp -s $envf $L/env.crash.before; then echo "   .env: SIN CAMBIOS (anterior)" | tee -a $OUT; else echo "   .env: CAMBIADO" | tee -a $OUT; fi
  show_logins "estado desincronizado tras la interrupción"; journal_state
  step "-Check con la rotación interrumpida"; run ./Update-Int37Credentials.ps1 -Check
  step "-RotatePostgres de nuevo con un diario pendiente (debe negarse)"; run ./Update-Int37Credentials.ps1 -RotatePostgres -Target $target
  step "-Recover"; run ./Update-Int37Credentials.ps1 -Recover
  show_logins "tras -Recover"; journal_state
  step "-Recover repetido (no debe hacer nada)"; run ./Update-Int37Credentials.ps1 -Recover
}
crash_test Semantic 5433 nexora_semantic_owner nexora_semantic SEMANTIC_DB_OWNER_PASSWORD $SE semantic-propietario
crash_test Reports 5434 nexora_reports_owner nexora_reports REPORTS_DB_OWNER_PASSWORD $RE reports-superusuario

step "Rotación completa después de recuperar (Semantic y Reports)"
run ./Update-Int37Credentials.ps1 -RotatePostgres -Target Semantic
run ./Update-Int37Credentials.ps1 -RotatePostgres -Target Reports
show_logins "tras la segunda rotación"

step "Secuencia completa: AUT-1a (-Rotate, Keycloak simulado) + -InitializeStore -> -Check debe pasar"
run ./Update-Int37Credentials.ps1 -Rotate
run ./Update-Int37Credentials.ps1 -InitializeStore
run ./Update-Int37Credentials.ps1 -Check

# ---------------------------------------------------------------- refusal: verification path that trusts
step "Negativo: pg_hba con 'host all all all trust' (la verificación TCP aceptaría cualquier contraseña)"
cp $RE $L/reports.env.pretrust
docker rm -f $RC >/dev/null; pgrun $RC 5434 $W/pgsock/reports -e POSTGRES_USER=nexora_reports_owner -e POSTGRES_PASSWORD=$WEAK -e POSTGRES_DB=nexora_reports
sleep 7
# the sandbox image ignores POSTGRES_HOST_AUTH_METHOD: pg_hba.conf is rewritten through the local socket and reloaded
S="psql -h $W/pgsock/reports -p 5432 -U nexora_reports_owner -w -X -q"
$S -d postgres -c "create temp table h(l text); insert into h values ('local all all trust'),('host all all all trust'); copy h to '/var/lib/postgresql/data/pg_hba.conf'; select pg_reload_conf();" >/dev/null
# same roles as the real container (any password: the host rule trusts), so only the trust probe can stop the rotation
$S -d nexora_reports -c "CREATE ROLE nexora_reports_app LOGIN PASSWORD 'x'" -c "CREATE ROLE nexora_reader LOGIN PASSWORD 'x'" -c "CREATE DATABASE synthetic_erp"
$S -d postgres -tA -c "select '   regla real: '||type||' '||array_to_string(database,',')||' '||array_to_string(user_name,',')||' '||coalesce(address,'-')||' '||auth_method from pg_hba_file_rules order by rule_number" | tee -a $OUT
run ./Update-Int37Credentials.ps1 -RotatePostgres -Target Reports
if cmp -s $RE $L/reports.env.pretrust; then echo "   .env de Reports: SIN CAMBIOS" | tee -a $OUT; else echo "   .env de Reports: CAMBIADO (ERROR)" | tee -a $OUT; fi
journal_state
docker rm -f $RC >/dev/null

step "Negativo: contenedor detenido"
docker stop $SC >/dev/null; cp $SE $L/semantic.env.prestop
run ./Update-Int37Credentials.ps1 -RotatePostgres -Target Semantic
if cmp -s $SE $L/semantic.env.prestop; then echo "   .env de Semantic: SIN CAMBIOS" | tee -a $OUT; else echo "   .env de Semantic: CAMBIADO (ERROR)" | tee -a $OUT; fi
docker start $SC >/dev/null

killjava nexora-; killsim keycloak_sim
echo; echo "FIN"
