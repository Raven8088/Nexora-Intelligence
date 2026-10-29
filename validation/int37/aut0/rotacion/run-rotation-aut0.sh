#!/bin/bash
# AUT-0: in-place rotation (C1-C3) against the Keycloak SIMULATOR, with FICTITIOUS secrets. Nothing real is touched.
set -u
W=/work/aut0; R=$W/NexoraAI; I37=$R/Nexora-Intelligence/validation/int37; L=$W/rot/out; OUT=$L/rotation.log
export NEXORA_INT37_HOME=$W/rot/home PATH=$W/bin:$PATH
killsim(){ ps -eo pid,args | awk '$2=="python3" && $3 ~ /keycloak_sim/ {print $1}' | xargs -r kill; sleep 1; }
startkc(){ (cd $W && nohup python3 sim/keycloak_sim.py "$R/Nexora-Semantic/docker/keycloak/nexora-realm.json" "$R/Nexora-Semantic/docker/.env" 8180 $L/kc-requests.log > $L/kc.out 2>&1 &); sleep 2; }
run(){ (cd $I37 && pwsh -NoProfile -File "$@" > $L/last.out 2>&1); rc=$?; sed 's/\x1b\[[0-9;]*m//g' $L/last.out | tee -a $OUT; echo "-> exit $rc" | tee -a $OUT; }
step(){ echo; echo "################ $1" | tee -a $OUT; }
reset_env(){ cat > $R/Nexora-Semantic/docker/.env <<'E'
# SANDBOX AUT-0: valores ficticios
POSTGRES_PASSWORD=SandboxPgSuperFicticio000000001
SEMANTIC_DB_OWNER_USER=nexora_semantic_owner
SEMANTIC_DB_OWNER_PASSWORD=SandboxSemOwnerFicticio00000002
SEMANTIC_DB_APP_USER=nexora_semantic_app
SEMANTIC_DB_APP_PASSWORD=SandboxSemAppFicticio000000003
KC_BOOTSTRAP_ADMIN_PASSWORD=fictAdm1
KC_DEV_USER_PASSWORD=fict1234
KC_REPORTS_CLIENT_SECRET=fict1234
KC_TENANT_DEMO_ID=11111111-1111-4111-8111-111111111111
KC_TENANT_OTHER_ID=22222222-2222-4222-8222-222222222222
E
rm -rf $W/rot/home; }
logins(){ # checks the credentials of the .env against the simulator (prints only OK/FAIL)
  python3 - "$R/Nexora-Semantic/docker/.env" <<'P' | tee -a $OUT
import sys,re,urllib.request,urllib.parse
env={m.group(1):m.group(2) for m in (re.match(r'([A-Z_]+)=(.*)',l.strip()) for l in open(sys.argv[1])) if m}
def tok(realm,form):
    try:
        urllib.request.urlopen(urllib.request.Request(f"http://localhost:8180/realms/{realm}/protocol/openid-connect/token",data=urllib.parse.urlencode(form).encode()))
        return "OK"
    except Exception: return "FAIL"
print("logins con el .env:","admin="+tok("master",dict(grant_type="password",client_id="admin-cli",username="admin",password=env["KC_BOOTSTRAP_ADMIN_PASSWORD"])),
 *[u+"="+tok("nexora",dict(grant_type="password",client_id="nexora-semantic-dev",username=u,password=env["KC_DEV_USER_PASSWORD"])) for u in ["platform.admin","steward.demo","viewer.demo","auditor.demo","steward.other"]],
 "nexora-reports-dev="+tok("nexora",dict(grant_type="client_credentials",client_id="nexora-reports-dev",client_secret=env["KC_REPORTS_CLIENT_SECRET"])))
P
}
rm -rf $L; mkdir -p $L; killsim

step "Escenario 1: rotación completa (admin débil -> se incluye)"; reset_env; startkc
run ./Update-Int37Credentials.ps1 -Check
run ./Invoke-Int37Keycloak.ps1 -Inventory -SemanticUrl http://localhost:1
cp $W/rot/home/inventory/*.json $L/inventario-ejemplo.json 2>/dev/null
run ./Update-Int37Credentials.ps1 -Rotate -WhatIf
logins
run ./Update-Int37Credentials.ps1 -Rotate
logins
run ./Update-Int37Credentials.ps1 -Check
run ./Update-Int37Credentials.ps1 -Rotate -WhatIf

step "Escenario 2: fallo real al cambiar el secreto del cliente (HTTP 500) -> reversión automática"; reset_env; killsim; export SIM_FAULTS=client_put; startkc; unset SIM_FAULTS
run ./Update-Int37Credentials.ps1 -Rotate
logins
grep -h "^STATE\|^DETAIL" $W/rot/home/rotation-journal.env | tee -a $OUT
run ./Update-Int37Credentials.ps1 -Check

step "Escenario 3: fallo al cambiar el administrador -> reversión automática"; reset_env; killsim; export SIM_FAULTS=admin_reset; startkc; unset SIM_FAULTS
run ./Update-Int37Credentials.ps1 -Rotate
logins

step "Escenario 4: el proceso muere a mitad (kill -9) -> -Check lo detecta -> -Recover"; reset_env; killsim; export SIM_DELAY=0.7; startkc; unset SIM_DELAY
(cd $I37 && pwsh -NoProfile -File ./Update-Int37Credentials.ps1 -Rotate > $L/killed.out 2>&1 &)
sleep 9; ps -eo pid,args | awk '$0 ~ /Update-Int37Credentials.ps1 -Rotate/ && $2 ~ /pwsh/ {print $1}' | xargs -r kill -9
echo "proceso terminado con kill -9; estado del diario:" | tee -a $OUT; grep -h "^STATE\|^STEP" $W/rot/home/rotation-journal.env | tee -a $OUT
logins
run ./Update-Int37Credentials.ps1 -Check
run ./Update-Int37Credentials.ps1 -Recover
logins
run ./Update-Int37Credentials.ps1 -Recover
killsim
echo FIN
