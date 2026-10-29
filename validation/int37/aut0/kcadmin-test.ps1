# AUT-0 test helper (sandbox only): direct admin calls against the Keycloak SIMULATOR.
param([string]$Action, [string]$Name)
Import-Module /work/aut0/NexoraAI/Nexora-Intelligence/validation/int37/Int37Common.psm1 -Force
$envf = Read-Int37DotEnv /work/aut0/NexoraAI/Nexora-Semantic/docker/.env
$t = Get-Int37PasswordToken 'http://localhost:8180' 'master' 'admin-cli' 'admin' $envf['KC_BOOTSTRAP_ADMIN_PASSWORD']
$A = 'http://localhost:8180/admin/realms/nexora'
switch ($Action) {
  'create-unmarked-user' { (Invoke-Int37Http -Method POST -Url "$A/users" -Token $t -Body @{ username = $Name; enabled = $true; firstName = 'Otro'; lastName = 'Usuario'; email = "$Name@example.org" }).Status }
  'delete-user' { $u = @((Invoke-Int37Http -Url "$A/users?username=$Name&exact=true" -Token $t).Json)[0]; (Invoke-Int37Http -Method DELETE -Url "$A/users/$($u['id'])" -Token $t).Status }
  'create-unmarked-role' { (Invoke-Int37Http -Method POST -Url "$A/roles" -Token $t -Body @{ name = $Name }).Status }
  'role-attrs' { $r = Invoke-Int37Http -Url "$A/roles/$Name" -Token $t; "$($r.Status) $(ConvertTo-Json $r.Json['attributes'] -Compress)" }
}
