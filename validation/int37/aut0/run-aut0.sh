#!/bin/bash
# AUT-0: clean end-to-end run of the INT-3.7 scripts in the isolated sandbox (simulated Keycloak and Ollama,
# real Semantic/Discovery/Reports jars from the user's machine, Intelligence built from the same source, throwaway
# PostgreSQL containers, FICTITIOUS secrets). Nothing touches the user's machine.
set -u
W=/work/aut0; R=$W/NexoraAI; I37=$R/Nexora-Intelligence/validation/int37; L=$W/run; OUT=$L/steps.log
export NEXORA_INT37_HOME=$W/home PATH=$W/bin:$PATH
killsim(){ ps -eo pid,args | awk -v p="$1" '$2=="python3" && $3 ~ p {print $1}' | xargs -r kill; }
killjava(){ ps -eo pid,args | awk -v p="$1" '$2 ~ /java$/ && $0 ~ p {print $1}' | xargs -r kill; }
startkc(){ (cd $W && nohup python3 sim/keycloak_sim.py "$R/Nexora-Semantic/docker/keycloak/nexora-realm.json" "$R/Nexora-Semantic/docker/.env" 8180 $L/kc-requests.log > $L/kc.out 2>&1 &); sleep 2; }
waitfor(){ for i in $(seq 1 90); do curl -s -o /dev/null "$1" && return 0; sleep 1; done; echo "TIMEOUT $1"; }
step(){ echo; echo "################ $1" | tee -a $OUT; }
run(){ (cd $I37 && pwsh -NoProfile -File "$@" > $L/last.out 2>&1); rc=$?; sed 's/\x1b\[[0-9;]*m//g' $L/last.out | tee -a $OUT; echo "-> exit $rc" | tee -a $OUT; }

# ---- reset sandbox
killjava nexora-; killsim keycloak_sim; killsim llm_sim; sleep 2
docker rm -f semantic-postgres nexora-reports-postgres >/dev/null 2>&1
rm -rf $W/home $L; mkdir -p $L
rm -rf $R/Nexora-Intelligence/validation && cp -r /work/int37/Nexora-Intelligence/validation $R/Nexora-Intelligence/
cat > $R/Nexora-Semantic/docker/.env <<'E'
# SANDBOX AUT-0: valores ficticios
POSTGRES_PASSWORD=SandboxPgSuperFicticio000000001
SEMANTIC_DB_OWNER_USER=nexora_semantic_owner
SEMANTIC_DB_OWNER_PASSWORD=SandboxSemOwnerFicticio00000002
SEMANTIC_DB_APP_USER=nexora_semantic_app
SEMANTIC_DB_APP_PASSWORD=SandboxSemAppFicticio000000003
KC_BOOTSTRAP_ADMIN_PASSWORD=SandboxKcAdminFicticio00000004
KC_DEV_USER_PASSWORD=fict1234
KC_REPORTS_CLIENT_SECRET=fict1234
KC_TENANT_DEMO_ID=11111111-1111-4111-8111-111111111111
KC_TENANT_OTHER_ID=22222222-2222-4222-8222-222222222222
E
getv(){ grep "^$2=" "$1" | cut -d= -f2-; }
SE=$R/Nexora-Semantic/docker/.env; RE=$R/Nexora-Reports/docker/.env
POSTGRES_PASSWORD=$(getv $SE POSTGRES_PASSWORD); SEMANTIC_DB_OWNER_USER=$(getv $SE SEMANTIC_DB_OWNER_USER); SEMANTIC_DB_OWNER_PASSWORD=$(getv $SE SEMANTIC_DB_OWNER_PASSWORD)
SEMANTIC_DB_APP_USER=$(getv $SE SEMANTIC_DB_APP_USER); SEMANTIC_DB_APP_PASSWORD=$(getv $SE SEMANTIC_DB_APP_PASSWORD)
REPORTS_DB_OWNER_PASSWORD=$(getv $RE REPORTS_DB_OWNER_PASSWORD); REPORTS_DB_APP_PASSWORD=$(getv $RE REPORTS_DB_APP_PASSWORD)
# (nothing is exported: the scripts must read the .env files themselves, as on Windows)
docker run -d --name semantic-postgres -p 127.0.0.1:5433:5432 -e POSTGRES_DB=nexora_semantic -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=$POSTGRES_PASSWORD postgres:17.6 >/dev/null
docker run -d --name nexora-reports-postgres -p 127.0.0.1:5434:5432 -e POSTGRES_USER=nexora_reports_owner -e POSTGRES_PASSWORD=$REPORTS_DB_OWNER_PASSWORD -e POSTGRES_DB=nexora_reports postgres:17.6 >/dev/null
sleep 6
# same bootstrap as docker/postgres/init of Semantic and Reports (the sandbox image has no init-script support)
PGPASSWORD=$POSTGRES_PASSWORD psql -h 127.0.0.1 -p 5433 -U postgres -d nexora_semantic -q -v ON_ERROR_STOP=1 -v db=nexora_semantic -v owner=$SEMANTIC_DB_OWNER_USER -v owner_password=$SEMANTIC_DB_OWNER_PASSWORD -v app=$SEMANTIC_DB_APP_USER -v app_password=$SEMANTIC_DB_APP_PASSWORD <<'SQL'
CREATE ROLE :"owner" LOGIN PASSWORD :'owner_password';
CREATE ROLE :"app" LOGIN PASSWORD :'app_password';
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE :"db" TO :"owner";
GRANT CONNECT ON DATABASE :"db" TO :"app";
SQL
export PGPASSWORD=$REPORTS_DB_OWNER_PASSWORD; P="psql -h 127.0.0.1 -p 5434 -U nexora_reports_owner -q -v ON_ERROR_STOP=1"
$P -d nexora_reports -v app_password=$REPORTS_DB_APP_PASSWORD <<'SQL'
CREATE ROLE nexora_reports_app LOGIN PASSWORD :'app_password';
REVOKE ALL ON DATABASE nexora_reports FROM PUBLIC;
GRANT CONNECT ON DATABASE nexora_reports TO nexora_reports_app;
SQL
$P -d nexora_reports -c "CREATE DATABASE synthetic_erp"; $P -d synthetic_erp -f $R/Nexora-Reports/docker/postgres/init/sql/synthetic-erp.sql
unset PGPASSWORD
startkc
(cd $W && nohup python3 sim/llm_sim.py 11434 $L/llm.log > $L/llm.out 2>&1 &)

# ---- simulate the user's current state: Semantic running, tenants registered BEFORE (UUIDs differ from .env)
(cd $I37 && nohup pwsh -NoProfile -File ./Start-Int37Service.ps1 -Service Semantic > $L/semantic.log 2>&1 &); waitfor http://localhost:8080/actuator/health
sleep 3
T=$(curl -s -d grant_type=password -d client_id=nexora-semantic-dev -d username=platform.admin -d password=fict1234 http://localhost:8180/realms/nexora/protocol/openid-connect/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
for k in demo other; do curl -s -o /dev/null -w "alta tenant $k: %{http_code}\n" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d "{\"key\":\"$k\",\"name\":\"$k\"}" http://localhost:8080/api/v1/admin/tenants; done
DEMO=$(curl -s -H "Authorization: Bearer $T" "http://localhost:8080/api/v1/admin/tenants?size=200" | python3 -c "import sys,json;print([t['id'] for t in json.load(sys.stdin)['items'] if t['key']=='demo'][0])")
unset T

step "V1 (sandbox) Intelligence: mvn -o clean verify -> see verify.log"
(cd $R/Nexora-Intelligence && mvn -o -q clean verify > $L/verify.log 2>&1; echo "-> exit $?" | tee -a $OUT)
python3 - <<'E' | tee -a $OUT
import glob,re
t=f=e=s=0
for p in glob.glob('/work/aut0/NexoraAI/Nexora-Intelligence/target/surefire-reports/TEST-*.xml'):
    h=open(p).read(3000); g=lambda k:int(re.search(k+r'="(\d+)"',h).group(1)); t+=g('tests');f+=g('failures');e+=g('errors');s+=g('skipped')
print(f"tests={t} failures={f} errors={e} skipped={s}")
E
(cd $R/Nexora-Intelligence && mvn -o -q -DskipTests package > $L/package.log 2>&1)

step "V0 Inspect (estado inicial: tenant incoherente)"; run ./Invoke-Int37Keycloak.ps1 -Inspect
step "V0 Credenciales -Check (estado inicial)"; run ./Update-Int37Credentials.ps1 -Check
step "Paso 3 -Rotate -WhatIf"; run ./Update-Int37Credentials.ps1 -Rotate -WhatIf
step "Paso 3 -Rotate"; run ./Update-Int37Credentials.ps1 -Rotate
step "Paso 3 -SetTenantDemoId <UUID de Semantic>"; run ./Update-Int37Credentials.ps1 -SetTenantDemoId $DEMO
step "Paso 3 -InitializeStore"; run ./Update-Int37Credentials.ps1 -InitializeStore
step "Paso 3 recrear Keycloak (simulador reiniciado: reimporta el realm con el .env nuevo)"; killsim keycloak_sim; sleep 1; startkc; echo "-> simulador reiniciado" | tee -a $OUT
step "Paso 3 -Inspect"; run ./Invoke-Int37Keycloak.ps1 -Inspect
step "Paso 3 -Check"; run ./Update-Int37Credentials.ps1 -Check
step "Paso 4 -Apply -WhatIf"; run ./Invoke-Int37Keycloak.ps1 -Apply -WhatIf
step "Paso 4 -Apply"; run ./Invoke-Int37Keycloak.ps1 -Apply
step "Paso 4 -Apply (idempotencia)"; run ./Invoke-Int37Keycloak.ps1 -Apply
step "V2 -Verify"; run ./Invoke-Int37Keycloak.ps1 -Verify
step "Paso 6 base sintética -WhatIf"; run ./Initialize-Int37SyntheticDb.ps1 -WhatIf
step "Paso 6 base sintética"; run ./Initialize-Int37SyntheticDb.ps1
step "Paso 6 base sintética (idempotencia)"; run ./Initialize-Int37SyntheticDb.ps1
step "V3 SQL de referencia"; run ./Test-Int37EndToEnd.ps1 -Reference
step "Paso 8 Discovery"; (cd $I37 && nohup pwsh -NoProfile -File ./Start-Int37Service.ps1 -Service Discovery > $L/discovery.log 2>&1 &); sleep 2; waitfor http://localhost:8070/actuator/health; grep -m1 "Started" $L/discovery.log | sed 's/.*INFO/INFO/' | tee -a $OUT
step "V4 catálogo -WhatIf"; run ./Invoke-Int37Catalog.ps1 -WhatIf
step "V4 catálogo"; run ./Invoke-Int37Catalog.ps1
step "V4 catálogo (idempotencia)"; run ./Invoke-Int37Catalog.ps1
step "Paso 10 Reports"; (cd $I37 && nohup pwsh -NoProfile -File ./Start-Int37Service.ps1 -Service Reports > $L/reports.log 2>&1 &); waitfor http://localhost:8090/actuator/health; grep -m1 "Started" $L/reports.log | sed 's/.*INFO/INFO/' | tee -a $OUT
step "V2 -Verify con Reports arrancado"; run ./Invoke-Int37Keycloak.ps1 -Verify
step "V5 -Manual (sin Ollama)"; run ./Test-Int37EndToEnd.ps1 -Manual
step "Paso 12 Intelligence"; (cd $I37 && nohup pwsh -NoProfile -File ./Start-Int37Service.ps1 -Service Intelligence > $L/intelligence.log 2>&1 &); waitfor http://localhost:8100/actuator/health; grep -m1 "Started" $L/intelligence.log | sed 's/.*INFO/INFO/' | tee -a $OUT
step "V6 -Api"; run ./Test-Int37EndToEnd.ps1 -Api
step "V7 -Repeat 5"; run ./Test-Int37EndToEnd.ps1 -Repeat 5
step "Restaurar tras recrear Keycloak: reinicio del simulador + -Inspect/-Apply/-Verify + V5"
killsim keycloak_sim; sleep 1; startkc
run ./Invoke-Int37Keycloak.ps1 -Apply; run ./Invoke-Int37Keycloak.ps1 -Verify; run ./Test-Int37EndToEnd.ps1 -Manual
step "-Revert"; run ./Invoke-Int37Keycloak.ps1 -Revert
step "-Inspect tras -Revert"; run ./Invoke-Int37Keycloak.ps1 -Inspect
step "Negativo: variable de entorno obsoleta KC_DEV_USER_PASSWORD -> -Check debe fallar"
KC_DEV_USER_PASSWORD=fict1234 run ./Update-Int37Credentials.ps1 -Check
cp $W/home/out/*.md $L/ 2>/dev/null
echo; echo "FIN"
