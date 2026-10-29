#!/bin/bash
# INT-3.7 · shared nexora-reports audience: reproduction and fix in the isolated sandbox (simulated Keycloak with the
# user's stray mapper, real Semantic jar, throwaway PostgreSQL, FICTITIOUS secrets). Nothing touches the user's machine.
set -u
W=/work/aut0; R=$W/NexoraAI; I37=$R/Nexora-Intelligence/validation/int37; L=$W/aud/run; OUT=$L/steps.log
export NEXORA_INT37_HOME=$W/aud/home PATH=$W/bin:$PATH
SE=$R/Nexora-Semantic/docker/.env
killsim(){ ps -eo pid,args | awk -v p="$1" '$2=="python3" && $3 ~ p {print $1}' | xargs -r kill; }
killjava(){ ps -eo pid,args | awk -v p="$1" '$2 ~ /java$/ && $0 ~ p {print $1}' | xargs -r kill; }
startkc(){ (cd $W && SIM_SHARED_REPORTS_AUD=1 nohup python3 sim/keycloak_sim.py "$R/Nexora-Semantic/docker/keycloak/nexora-realm.json" "$SE" 8180 $L/kc-requests.log > $L/kc.out 2>&1 &); sleep 2; }
waitfor(){ for i in $(seq 1 120); do curl -s -o /dev/null "$1" && return 0; sleep 1; done; echo "TIMEOUT $1"; return 1; }
step(){ echo | tee -a $OUT; echo "################ $1" | tee -a $OUT; }
run(){ (cd $I37 && pwsh -NoProfile -File "$@" > $L/last.out 2>&1); rc=$?; sed 's/\x1b\[[0-9;]*m//g' $L/last.out | tee -a $OUT; echo "-> exit $rc" | tee -a $OUT; }
getv(){ grep "^$2=" "$1" | cut -d= -f2-; }
aud_of(){ # client user password -> aud claim only
  curl -s -d grant_type=password -d client_id=$1 -d username=$2 --data-urlencode password="$3" http://localhost:8180/realms/nexora/protocol/openid-connect/token |
  python3 -c "import sys,json,base64;t=json.load(sys.stdin)['access_token'].split('.')[1];t+='='*(-len(t)%4);print(json.loads(base64.urlsafe_b64decode(t)).get('aud'))"; }
show_aud(){ echo "   steward.demo (nexora-semantic-dev) aud=$(aud_of nexora-semantic-dev steward.demo "$(getv $SE KC_DEV_USER_PASSWORD)")" | tee -a $OUT; }

killjava nexora-; killsim keycloak_sim; sleep 1
docker rm -f semantic-postgres nexora-reports-postgres nexora-semantic-dev-postgres-1 >/dev/null 2>&1
rm -rf $W/aud/home $L; mkdir -p $L $W/aud/home
rm -rf $R/Nexora-Intelligence/validation && cp -r /work/int37/Nexora-Intelligence/validation $R/Nexora-Intelligence/
rnd(){ python3 -c "import secrets,string;a=string.ascii_letters+string.digits;print(''.join(secrets.choice(a) for _ in range(32)))"; }
cat > $SE <<E
# SANDBOX: valores ficticios
POSTGRES_PASSWORD=$(rnd)
SEMANTIC_DB_OWNER_USER=nexora_semantic_owner
SEMANTIC_DB_OWNER_PASSWORD=$(rnd)
SEMANTIC_DB_APP_USER=nexora_semantic_app
SEMANTIC_DB_APP_PASSWORD=$(rnd)
KC_BOOTSTRAP_ADMIN_PASSWORD=$(rnd)
KC_DEV_USER_PASSWORD=$(rnd)
KC_REPORTS_CLIENT_SECRET=$(rnd)
KC_TENANT_DEMO_ID=11111111-1111-4111-8111-111111111111
KC_TENANT_OTHER_ID=22222222-2222-4222-8222-222222222222
E
PGP=$(getv $SE POSTGRES_PASSWORD)
docker run -d --name semantic-postgres -p 127.0.0.1:5433:5432 -e POSTGRES_DB=nexora_semantic -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=$PGP postgres:17.6 >/dev/null
sleep 7
PGPASSWORD=$PGP psql -h 127.0.0.1 -p 5433 -U postgres -d nexora_semantic -q -v ON_ERROR_STOP=1 -v o="$(getv $SE SEMANTIC_DB_OWNER_PASSWORD)" -v a="$(getv $SE SEMANTIC_DB_APP_PASSWORD)" <<'SQL'
CREATE ROLE nexora_semantic_owner LOGIN PASSWORD :'o';
CREATE ROLE nexora_semantic_app LOGIN PASSWORD :'a';
REVOKE ALL ON DATABASE nexora_semantic FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE nexora_semantic TO nexora_semantic_owner;
GRANT CONNECT ON DATABASE nexora_semantic TO nexora_semantic_app;
SQL
startkc
(cd $I37 && nohup pwsh -NoProfile -File ./Start-Int37Service.ps1 -Service Semantic > $L/semantic.log 2>&1 &); waitfor http://localhost:8080/actuator/health
T=$(curl -s -d grant_type=password -d client_id=nexora-semantic-dev -d username=platform.admin --data-urlencode password="$(getv $SE KC_DEV_USER_PASSWORD)" http://localhost:8180/realms/nexora/protocol/openid-connect/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
for k in demo other; do curl -s -o /dev/null -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d "{\"key\":\"$k\",\"name\":\"$k\"}" http://localhost:8080/api/v1/admin/tenants; done
DEMO=$(curl -s -H "Authorization: Bearer $T" "http://localhost:8080/api/v1/admin/tenants?size=200" | python3 -c "import sys,json;print([t['id'] for t in json.load(sys.stdin)['items'] if t['key']=='demo'][0])")
unset T
(cd $I37 && pwsh -NoProfile -File ./Update-Int37Credentials.ps1 -SetTenantDemoId $DEMO > /dev/null 2>&1)
(cd $I37 && pwsh -NoProfile -File ./Update-Int37Credentials.ps1 -InitializeStore > /dev/null 2>&1)
killsim keycloak_sim; sleep 1; startkc   # re-imports the realm with the tenant UUID (sandbox only), stray mapper included

step "1. -Inspect (estado de partida, como en tu realm: mapper nexora-reports-audience en nexora-semantic)"; run ./Invoke-Int37Keycloak.ps1 -Inspect
show_aud
step "2. -SharedReportsAudience Disable antes de -Apply (debe negarse: Intelligence aún no tiene su propia audiencia)"; run ./Invoke-Int37Keycloak.ps1 -SharedReportsAudience Disable
step "3. -Apply"; run ./Invoke-Int37Keycloak.ps1 -Apply
step "4. -Verify (reproducción: deben fallar demo.editor y demo.approver por aud=nexora-reports)"; run ./Invoke-Int37Keycloak.ps1 -Verify
step "5. -SharedReportsAudience Disable -WhatIf (no cambia nada)"; run ./Invoke-Int37Keycloak.ps1 -SharedReportsAudience Disable -WhatIf
show_aud
step "6. -SharedReportsAudience Disable"; run ./Invoke-Int37Keycloak.ps1 -SharedReportsAudience Disable
show_aud
echo "   copia de seguridad: $(ls $NEXORA_INT37_HOME/backup/ 2>/dev/null)" | tee -a $OUT
python3 -c "import json;s=json.load(open('$NEXORA_INT37_HOME/state.json',encoding='utf-8-sig'));print('   state.json sharedReportsAudience:',[ (x['scope'],x['mapper']['name'],x['mapper']['config']['access.token.claim']) for x in s.get('sharedReportsAudience',[])])" | tee -a $OUT
step "7. -Verify (debe pasar todo)"; run ./Invoke-Int37Keycloak.ps1 -Verify
step "8. -SharedReportsAudience Disable otra vez (idempotente)"; run ./Invoke-Int37Keycloak.ps1 -SharedReportsAudience Disable
step "9. -Inspect (sin aviso)"; run ./Invoke-Int37Keycloak.ps1 -Inspect
step "10. -SharedReportsAudience Restore (vuelve la configuración original; -Verify vuelve a fallar)"; run ./Invoke-Int37Keycloak.ps1 -SharedReportsAudience Restore
show_aud
run ./Invoke-Int37Keycloak.ps1 -Verify
step "11. Disable y después -Revert (restaura el mapper y borra solo lo marcado INT-3.7)"; run ./Invoke-Int37Keycloak.ps1 -SharedReportsAudience Disable
run ./Invoke-Int37Keycloak.ps1 -Revert
show_aud
step "12. -Inspect tras -Revert (mapper restaurado, como estaba)"; run ./Invoke-Int37Keycloak.ps1 -Inspect
killjava nexora-; killsim keycloak_sim; docker rm -f semantic-postgres >/dev/null 2>&1
echo; echo FIN
