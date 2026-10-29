#Requires -Version 7.0
<#
.SYNOPSIS
  INT-3.7: Keycloak (development realm "nexora") for the local demo. Inspect, inventory, apply, verify or revert.

.DESCRIPTION
  -Inspect (read-only; V0)
      Realm, issuer, roles, client scopes, clients, INT-3.7 objects, leftovers of the R1 validation, the tenant
      attribute of group tenant-demo and its consistency with KC_TENANT_DEMO_ID (Semantic docker/.env) and with
      tenant "demo" registered in Semantic. Changes nothing. Exit code 1 when the tenant is inconsistent.

  -Inventory (read-only; before AUT-1)
      Writes to the local INT-3.7 folder (inventory/, readable only by the current user) a JSON inventory WITHOUT
      SECRETS: the realm's partial export (client secrets masked and every secret-like field removed), every user
      with its id (the "sub" referenced by Semantic's audit and authorship), roles, groups and attributes, the users
      of realm master, and the objects that exist at runtime but not in nexora-realm.json (they would be lost if the
      container were recreated). It refuses to write if any known secret value appears in the result.
      AN INVENTORY IS NOT A RESTORABLE COPY: it has no passwords, client secrets, signing keys nor credentials, and
      Keycloak assigns new ids when a realm is imported. It is a reference to compare and to rebuild by hand.

  -Apply (MODIFIES KEYCLOAK; REQUIRES AUTHORIZATION)
      Idempotent and additive. Only creates or updates objects marked as INT-3.7:
        - realm role REPORT_RUNNER (reused, never marked, if it already exists);
        - client scope nexora-reports (audience mapper "nexora-reports");
        - confidential client nexora-intelligence: client credentials only, principal_type=SERVICE, default scopes
          nexora-semantic + nexora-reports (+ the built-in basic/service_account if present; no optional scopes);
          its service account has exactly REPORTS_CLIENT and
          REPORT_RUNNER (the realm default roles are removed) and belongs to group tenant-demo;
        - users demo.editor (EDITOR) and demo.approver (APPROVER) in tenant-demo, e-mail @nexora-int37.invalid.
      Refuses to run if the tenant is inconsistent, if a secret is missing, short or shared, or if an object with
      the same name exists without the INT-3.7 marker.

  -Verify (read-only; V2)
      Obtains tokens for nexora-intelligence, demo.editor, demo.approver and nexora-reports-dev and checks the
      claims tenant, principal_type, roles, aud and iss. Optionally checks that Semantic and Reports accept them.
      Only a claim summary is printed; never a token.

  -SharedReportsAudience Disable|Restore (MODIFIES KEYCLOAK; REQUIRES AUTHORIZATION)
      Disable: an audience mapper "nexora-reports" inside the SHARED client scope nexora-semantic (not in
      nexora-realm.json; added by hand at runtime) puts aud=nexora-reports in every token carrying nexora-semantic,
      humans included. Disable switches that mapper off (access/introspection/lightweight claims = false); it is not
      deleted. Its original configuration is kept in state.json and in backup/ (no secrets). Refuses unless
      nexora-intelligence already gets nexora-reports from the INT-3.7 scope. Lists who loses the audience.
      Restore: puts back the saved configuration (also done by -Revert).

  -Revert (MODIFIES KEYCLOAK; REQUIRES AUTHORIZATION)
      Deletes only objects carrying the INT-3.7 marker. REPORT_RUNNER is deleted only if INT-3.7 created it and no
      other user holds it. Restores the shared nexora-reports audience mapper if -SharedReportsAudience Disable
      switched it off.

  Secrets: the Keycloak admin password, KC_DEV_USER_PASSWORD and KC_REPORTS_CLIENT_SECRET come from Semantic's
  docker/.env (or environment variables); NEXORA_INTELLIGENCE_CLIENT_SECRET, NEXORA_DEMO_EDITOR_PASSWORD and
  NEXORA_DEMO_APPROVER_PASSWORD from the INT-3.7 secret store (Update-Int37Credentials.ps1 -InitializeStore).
#>
[CmdletBinding(SupportsShouldProcess = $true, DefaultParameterSetName = 'Inspect')]
param(
    [Parameter(ParameterSetName = 'Inspect')][switch]$Inspect,
    [Parameter(ParameterSetName = 'Apply', Mandatory = $true)][switch]$Apply,
    [Parameter(ParameterSetName = 'Verify', Mandatory = $true)][switch]$Verify,
    [Parameter(ParameterSetName = 'Revert', Mandatory = $true)][switch]$Revert,
    [Parameter(ParameterSetName = 'Inventory', Mandatory = $true)][switch]$Inventory,
    [Parameter(ParameterSetName = 'SharedAudience', Mandatory = $true)][ValidateSet('Disable', 'Restore')][string]$SharedReportsAudience,
    [string]$RealmFile = (Join-Path $PSScriptRoot '../../../Nexora-Semantic/docker/keycloak/nexora-realm.json'),
    [string]$KeycloakUrl = 'http://localhost:8180',
    [string]$Realm = 'nexora',
    [string]$SemanticUrl = 'http://localhost:8080',
    [string]$ReportsUrl = 'http://localhost:8090',
    [string]$SemanticEnvFile = (Join-Path $PSScriptRoot '../../../Nexora-Semantic/docker/.env')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Import-Module (Join-Path $PSScriptRoot 'Int37Common.psm1') -Force

$Issuer = "$KeycloakUrl/realms/$Realm"
$AdminUrl = "$KeycloakUrl/admin/realms/$Realm"
$Marker = Get-Int37Marker
$MarkerDomain = Get-Int37UserMarkerDomain
$Group = 'tenant-demo'
$ClientId = 'nexora-intelligence'
$ScopeName = 'nexora-reports'
$Users = @(
    @{ Name = 'demo.editor'; Role = 'EDITOR'; Secret = 'NEXORA_DEMO_EDITOR_PASSWORD' },
    @{ Name = 'demo.approver'; Role = 'APPROVER'; Secret = 'NEXORA_DEMO_APPROVER_PASSWORD' })
$R1Objects = @('nexora-reports-e2e', 'nexora-reports-e2e-ai', 'nexora-reports-e2e-other')
$R1Users = @('e2e.editor', 'e2e.approver', 'e2e.runner', 'e2e.runner.other')

$semanticEnv = Read-Int37DotEnv $SemanticEnvFile
$store = Read-Int37SecretStore

# ------------------------------------------------------------------------------------------------ admin helpers

<#
  Opens the admin session used by every admin request of this script (Int37Common: token renewed from expires_in
  with a 15 s margin, at most one retry on HTTP 401). The first token is requested immediately, so a wrong admin
  password still fails at the start, as before.
#>
function Open-AdminSession {
    $script:adminSession = New-Int37AdminSession -KeycloakUrl $KeycloakUrl -Password (Get-Int37Secret 'KC_BOOTSTRAP_ADMIN_PASSWORD' @($semanticEnv))
    Get-Int37AdminToken $script:adminSession | Out-Null
}

<# The only way this script talks to the Keycloak admin REST API. #>
function Invoke-Admin([string]$Method = 'GET', [string]$Path, $Body) {
    if ($PSBoundParameters.ContainsKey('Body')) {
        return Invoke-Int37Admin -Session $script:adminSession -Method $Method -Url "$AdminUrl$Path" -Body $Body
    }
    return Invoke-Int37Admin -Session $script:adminSession -Method $Method -Url "$AdminUrl$Path"
}

function Get-Json([string]$Path, [string]$What) {
    return (Assert-Int37Status (Invoke-Admin -Path $Path) 200 $What).Json
}

function Find-Client([string]$Id) {
    $list = @(Get-Json "/clients?clientId=$Id" "client $Id")
    if ($list.Count -gt 0) { return $list[0] } else { return $null }
}

function Find-Role([string]$Name) {
    $r = Invoke-Admin -Path "/roles/$Name"
    if ($r.Status -eq 404) { return $null }
    return (Assert-Int37Status $r 200 "role $Name").Json
}

function Find-Scope([string]$Name) {
    return (Get-Json '/client-scopes' 'client scopes') | Where-Object { $_['name'] -eq $Name } | Select-Object -First 1
}

function Find-User([string]$Name) {
    $list = @(Get-Json "/users?username=$Name&exact=true" "user $Name")
    if ($list.Count -gt 0) { return $list[0] } else { return $null }
}

function Get-GroupFull([string]$Name) {
    $found = (Get-Json "/groups?search=$Name&exact=true" "group $Name") | Where-Object { $_['name'] -eq $Name } |
        Select-Object -First 1
    if (-not $found) { return $null }
    return Get-Json "/groups/$($found['id'])" "group $Name"
}

function Test-ClientMarked($Client) {
    return $Client -and $Client.Contains('attributes') -and $Client['attributes'] -and
        $Client['attributes'][$Marker.Key] -eq $Marker.Value
}
function Test-ScopeMarked($Scope) { return Test-ClientMarked $Scope }
function Test-RoleMarked($Role) {
    return $Role -and $Role.Contains('attributes') -and $Role['attributes'] -and
        $Role['attributes'].Contains($Marker.Key) -and (@($Role['attributes'][$Marker.Key]) -contains $Marker.Value)
}
function Test-UserMarked($User) {
    return $User -and $User.Contains('email') -and "$($User['email'])".EndsWith("@$MarkerDomain")
}

# ------------------------------------------------------------------------------------------------ tenant check

<# Returns @{ Group; EnvFile; Semantic; Consistent; Notes } comparing the three sources of the demo tenant id. #>
function Get-TenantConsistency {
    $notes = [System.Collections.Generic.List[string]]::new()
    $group = Get-GroupFull $Group
    $groupTenant = $null
    if (-not $group) { $notes.Add("group $Group not found") }
    elseif ($group.Contains('attributes') -and $group['attributes'] -and $group['attributes'].Contains('tenant')) {
        $groupTenant = @($group['attributes']['tenant'])[0]
    } else { $notes.Add("group $Group has no tenant attribute") }
    $envTenant = $semanticEnv['KC_TENANT_DEMO_ID']
    $semanticTenant = $null
    try {
        $platform = Get-Int37PasswordToken $KeycloakUrl $Realm 'nexora-semantic-dev' 'platform.admin' `
            (Get-Int37Secret 'KC_DEV_USER_PASSWORD' @($semanticEnv))
        $r = Assert-Int37Status (Invoke-Int37Http -Url "$SemanticUrl/api/v1/admin/tenants?size=200" -Token $platform) 200 'Semantic tenants'
        $demo = @($r.Json['items']) | Where-Object { $_['key'] -eq 'demo' } | Select-Object -First 1
        if ($demo) { $semanticTenant = "$($demo['id'])"; if ($demo['status'] -ne 'ACTIVE') { $notes.Add("tenant demo status is $($demo['status'])") } }
        else { $notes.Add('tenant "demo" is not registered in Semantic') }
    } catch {
        $notes.Add("Semantic tenants not checked: $($_.Exception.Message)")
    }
    $zero = Get-Int37ZeroUuid
    $consistent = $groupTenant -and $semanticTenant -and ($groupTenant -eq $semanticTenant) -and
        ($envTenant -eq $semanticTenant) -and ($groupTenant -ne $zero)
    return @{ Group = $groupTenant; EnvFile = $envTenant; Semantic = $semanticTenant; Consistent = [bool]$consistent
        Notes = $notes }
}

function Show-TenantConsistency($t) {
    Write-Int37Step 'Tenant demo (debe coincidir en los tres sitios)'
    Write-Host "  Keycloak, grupo $Group (atributo tenant): $($t.Group)"
    Write-Host "  docker/.env de Semantic (KC_TENANT_DEMO_ID): $($t.EnvFile)"
    Write-Host "  Semantic, tenant con clave 'demo':           $($t.Semantic)"
    $t.Notes | ForEach-Object { Write-Int37Warn $_ }
    if ($t.Consistent) { Write-Int37Ok 'Coherente'; return }
    Write-Int37Fail 'Incoherente. Resolución (README, paso 3):'
    if ($t.Semantic) {
        Write-Host "  1. .\Update-Int37Credentials.ps1 -SetTenantDemoId $($t.Semantic)   (requiere autorización)"
        Write-Host '  2. Recrear Keycloak para reimportar el realm con ese UUID; después -Apply y -Verify.'
    } else {
        Write-Host '  Registrar primero el tenant "demo" en Semantic (README de Semantic, «Alta de tenants») y repetir -Inspect.'
    }
}

# ------------------------------------------------------------------------------------------------ inspect

function Invoke-Inspect {
    Write-Int37Step "Realm $Realm en $KeycloakUrl"
    $wk = Assert-Int37Status (Invoke-Int37Http -Url "$Issuer/.well-known/openid-configuration") 200 'realm discovery'
    if ($wk.Json['issuer'] -ne $Issuer) { Write-Int37Fail "issuer=$($wk.Json['issuer']) (se espera $Issuer)" }
    else { Write-Int37Ok "issuer=$Issuer" }
    Open-AdminSession

    Write-Int37Step 'Roles de realm'
    $roles = @(Get-Json '/roles' 'roles') | ForEach-Object { $_['name'] } | Sort-Object
    Write-Host "  $($roles -join ', ')"
    $runner = Find-Role 'REPORT_RUNNER'
    if ($runner) { Write-Int37Ok "REPORT_RUNNER existe (marcado INT-3.7: $(Test-RoleMarked $runner))" }
    else { Write-Int37Warn 'REPORT_RUNNER no existe (lo crea -Apply)' }

    Write-Int37Step 'Client scopes'
    $sem = Find-Scope 'nexora-semantic'
    if ($sem) {
        $mappers = @($sem['protocolMappers'] | ForEach-Object { "$($_['name'])" }) -join ', '
        Write-Int37Ok "nexora-semantic: mappers $mappers"
        $shared = @(Get-ActiveSharedReportsAudience $sem)
        if ($shared.Count -gt 0) {
            Write-Int37Warn "nexora-semantic: el mapper $(($shared | ForEach-Object { $_['name'] }) -join ', ') añade aud=nexora-reports a todos sus tokens: -Verify fallará para demo.editor y demo.approver"
            Write-SharedAudienceHint
        }
    } else { Write-Int37Fail 'nexora-semantic no existe' }
    $rep = Find-Scope $ScopeName
    if ($rep) { Write-Int37Ok "$ScopeName existe (marcado INT-3.7: $(Test-ScopeMarked $rep))" }
    else { Write-Int37Warn "$ScopeName no existe (lo crea -Apply)" }

    Write-Int37Step 'Clientes'
    foreach ($c in @('nexora-semantic-dev', 'nexora-reports-dev', $ClientId) + $R1Objects) {
        $client = Find-Client $c
        if (-not $client) { Write-Host "  $c : no existe"; continue }
        $kind = if ($client['publicClient']) { 'público' } else { 'confidencial' }
        $extra = if ($c -eq $ClientId) { " marcado INT-3.7: $(Test-ClientMarked $client)" } elseif ($c -in $R1Objects) { ' (resto de la validación R1)' } else { '' }
        Write-Host "  $c : $kind, serviceAccounts=$($client['serviceAccountsEnabled']), directAccess=$($client['directAccessGrantsEnabled'])$extra"
    }

    Write-Int37Step 'Usuarios'
    foreach ($u in @('platform.admin', 'steward.demo', 'viewer.demo', 'auditor.demo', 'steward.other') + ($Users | ForEach-Object { $_.Name }) + $R1Users) {
        $user = Find-User $u
        if (-not $user) { Write-Host "  $u : no existe"; continue }
        $mapped = @(Get-Json "/users/$($user['id'])/role-mappings/realm" 'role mappings') | ForEach-Object { $_['name'] } |
            Where-Object { $_ -notlike 'default-roles-*' }
        $groups = @(Get-Json "/users/$($user['id'])/groups" 'groups') | ForEach-Object { $_['name'] }
        $tag = if ($u -in $R1Users) { ' (resto de la validación R1)' } elseif (Test-UserMarked $user) { ' (INT-3.7)' } else { '' }
        Write-Host "  $u : roles=[$($mapped -join ',')] grupos=[$($groups -join ',')] enabled=$($user['enabled'])$tag"
    }

    $t = Get-TenantConsistency
    Show-TenantConsistency $t
    Write-Int37Step 'Persistencia'
    Write-Int37Warn 'Keycloak corre en start-dev sin volumen: recrear el contenedor borra todo lo aplicado por -Apply (README, «Conservar y restaurar»).'
    if (-not $t.Consistent) { exit 1 }
}

# ------------------------------------------------------------------------------------------------ apply

function Get-ApplySecrets {
    $secrets = @{
        NEXORA_INTELLIGENCE_CLIENT_SECRET = Get-Int37Secret 'NEXORA_INTELLIGENCE_CLIENT_SECRET' @($store)
        NEXORA_DEMO_EDITOR_PASSWORD = Get-Int37Secret 'NEXORA_DEMO_EDITOR_PASSWORD' @($store)
        NEXORA_DEMO_APPROVER_PASSWORD = Get-Int37Secret 'NEXORA_DEMO_APPROVER_PASSWORD' @($store)
        KC_DEV_USER_PASSWORD = Get-Int37Secret 'KC_DEV_USER_PASSWORD' @($semanticEnv)
        KC_REPORTS_CLIENT_SECRET = Get-Int37Secret 'KC_REPORTS_CLIENT_SECRET' @($semanticEnv)
        KC_BOOTSTRAP_ADMIN_PASSWORD = Get-Int37Secret 'KC_BOOTSTRAP_ADMIN_PASSWORD' @($semanticEnv)
    }
    $problems = Test-Int37SecretSet $secrets
    if ($problems.Count -gt 0) { throw "Secrets not acceptable: $($problems -join '; ')" }
    return $secrets
}

function Set-DefaultScopes([string]$ClientUuid, [string[]]$Wanted) {
    $all = @(Get-Json '/client-scopes' 'client scopes')
    $current = @(Get-Json "/clients/$ClientUuid/default-client-scopes" 'default scopes')
    foreach ($s in $current) {
        if ($Wanted -notcontains $s['name']) {
            Assert-Int37Status (Invoke-Admin -Method DELETE -Path "/clients/$ClientUuid/default-client-scopes/$($s['id'])") 204 "remove default scope $($s['name'])" | Out-Null
        }
    }
    foreach ($name in $Wanted) {
        if (-not ($current | Where-Object { $_['name'] -eq $name })) {
            $scope = $all | Where-Object { $_['name'] -eq $name } | Select-Object -First 1
            if (-not $scope) { throw "client scope $name not found" }
            Assert-Int37Status (Invoke-Admin -Method PUT -Path "/clients/$ClientUuid/default-client-scopes/$($scope['id'])") 204 "add default scope $name" | Out-Null
        }
    }
    foreach ($s in @(Get-Json "/clients/$ClientUuid/optional-client-scopes" 'optional scopes')) {
        Assert-Int37Status (Invoke-Admin -Method DELETE -Path "/clients/$ClientUuid/optional-client-scopes/$($s['id'])") 204 "remove optional scope $($s['name'])" | Out-Null
    }
}

<# Leaves exactly the wanted realm roles directly mapped to a user (only used on INT-3.7 principals). #>
function Set-ExactRealmRoles([string]$UserId, [string[]]$Wanted) {
    $current = @(Get-Json "/users/$UserId/role-mappings/realm" 'role mappings')
    $remove = @($current | Where-Object { $Wanted -notcontains $_['name'] })
    if ($remove.Count -gt 0) {
        Assert-Int37Status (Invoke-Admin -Method DELETE -Path "/users/$UserId/role-mappings/realm" -Body $remove) 204 'remove roles' | Out-Null
    }
    $add = @($Wanted | Where-Object { $n = $_; -not ($current | Where-Object { $_['name'] -eq $n }) } | ForEach-Object { Find-Role $_ })
    if ($add.Count -gt 0) {
        Assert-Int37Status (Invoke-Admin -Method POST -Path "/users/$UserId/role-mappings/realm" -Body $add) 204 'add roles' | Out-Null
    }
}

function Set-Group([string]$UserId, [string]$GroupId) {
    Assert-Int37Status (Invoke-Admin -Method PUT -Path "/users/$UserId/groups/$GroupId") 204 "group $Group" | Out-Null
}

function Invoke-Apply {
    $journal = Join-Path (Get-Int37Home) 'rotation-journal.env'
    if ((Test-Path $journal) -and ((Read-Int37DotEnv $journal)['STATE'] -notin 'COMPLETED', 'ROLLED_BACK', 'RECOVERED')) {
        throw 'A credential rotation did not finish: run Update-Int37Credentials.ps1 -Recover before -Apply.'
    }
    $secrets = Get-ApplySecrets
    Open-AdminSession

    Write-Int37Step 'Comprobaciones previas'
    $t = Get-TenantConsistency
    Show-TenantConsistency $t
    if (-not $t.Consistent) { throw 'Tenant demo inconsistent: -Apply refused.' }
    foreach ($c in @('nexora-semantic-dev', 'nexora-reports-dev')) { if (-not (Find-Client $c)) { throw "client $c not found" } }
    if (-not (Find-Scope 'nexora-semantic')) { throw 'client scope nexora-semantic not found' }
    foreach ($r in 'REPORTS_CLIENT', 'EDITOR', 'APPROVER') { if (-not (Find-Role $r)) { throw "role $r not found" } }
    $existingClient = Find-Client $ClientId
    if ($existingClient -and -not (Test-ClientMarked $existingClient)) { throw "client $ClientId exists without the INT-3.7 marker" }
    $existingScope = Find-Scope $ScopeName
    if ($existingScope -and -not (Test-ScopeMarked $existingScope)) { throw "client scope $ScopeName exists without the INT-3.7 marker" }
    foreach ($u in $Users) {
        $found = Find-User $u.Name
        if ($found -and -not (Test-UserMarked $found)) { throw "user $($u.Name) exists without the INT-3.7 marker" }
    }
    $groupFull = Get-GroupFull $Group
    Write-Int37Ok 'Comprobaciones previas superadas'
    if (-not $PSCmdlet.ShouldProcess("realm $Realm", 'aplicar la configuración de INT-3.7')) { return }

    Write-Int37Step 'Rol REPORT_RUNNER'
    if (-not (Find-Role 'REPORT_RUNNER')) {
        Assert-Int37Status (Invoke-Admin -Method POST -Path "/roles" -Body @{
            name = 'REPORT_RUNNER'; description = 'Nexora Reports: execute authorized plans'
            attributes = @{ $Marker.Key = @($Marker.Value) } }) 201 'create REPORT_RUNNER' | Out-Null
        Write-Int37Ok 'creado'
    } else { Write-Int37Ok 'ya existía (se reutiliza)' }

    Write-Int37Step "Client scope $ScopeName (audiencia nexora-reports)"
    if (-not $existingScope) {
        Assert-Int37Status (Invoke-Admin -Method POST -Path "/client-scopes" -Body @{
            name = $ScopeName; protocol = 'openid-connect'; description = 'Audience nexora-reports (INT-3.7)'
            attributes = @{ 'include.in.token.scope' = 'false'; 'display.on.consent.screen' = 'false'; $Marker.Key = $Marker.Value }
            protocolMappers = @(@{ name = 'audience-nexora-reports'; protocol = 'openid-connect'; protocolMapper = 'oidc-audience-mapper'
                config = @{ 'included.custom.audience' = 'nexora-reports'; 'access.token.claim' = 'true'; 'id.token.claim' = 'false'
                    'introspection.token.claim' = 'true' } }) }) 201 "create scope $ScopeName" | Out-Null
        Write-Int37Ok 'creado'
    } else { Write-Int37Ok 'ya existía (INT-3.7)' }

    Write-Int37Step "Cliente $ClientId"
    $representation = @{
        clientId = $ClientId; name = 'Nexora Intelligence (service, INT-3.7)'; enabled = $true; protocol = 'openid-connect'
        publicClient = $false; bearerOnly = $false; clientAuthenticatorType = 'client-secret'
        secret = $secrets.NEXORA_INTELLIGENCE_CLIENT_SECRET
        serviceAccountsEnabled = $true; standardFlowEnabled = $false; implicitFlowEnabled = $false
        directAccessGrantsEnabled = $false; frontchannelLogout = $false; fullScopeAllowed = $true
        attributes = @{ $Marker.Key = $Marker.Value }
        protocolMappers = @(@{ name = 'principal-type'; protocol = 'openid-connect'; protocolMapper = 'oidc-hardcoded-claim-mapper'
            config = @{ 'claim.name' = 'principal_type'; 'claim.value' = 'SERVICE'; 'jsonType.label' = 'String'
                'access.token.claim' = 'true'; 'id.token.claim' = 'false'; 'introspection.token.claim' = 'true' } })
    }
    if (-not $existingClient) {
        Assert-Int37Status (Invoke-Admin -Method POST -Path "/clients" -Body $representation) 201 "create $ClientId" | Out-Null
        Write-Int37Ok 'creado'
    } else {
        $test = Invoke-Int37Http -Method POST -Url "$Issuer/protocol/openid-connect/token" -Form @{
            grant_type = 'client_credentials'; client_id = $ClientId; client_secret = $secrets.NEXORA_INTELLIGENCE_CLIENT_SECRET }
        if ($test.Status -ne 200) {
            $full = Get-Json "/clients/$($existingClient['id'])" "client $ClientId"
            $full['secret'] = $secrets.NEXORA_INTELLIGENCE_CLIENT_SECRET
            Assert-Int37Status (Invoke-Admin -Method PUT -Path "/clients/$($existingClient['id'])" -Body $full) 204 "update secret of $ClientId" | Out-Null
            Write-Int37Ok 'secreto actualizado desde el almacén'
        } else { Write-Int37Ok 'ya existía (INT-3.7)' }
    }
    $client = Find-Client $ClientId
    $wantedScopes = @('nexora-semantic', $ScopeName)
    # Built-in scopes that only add identification claims (sub, client_id); no roles, no audiences.
    foreach ($builtIn in 'service_account', 'basic') { if (Find-Scope $builtIn) { $wantedScopes = @($builtIn) + $wantedScopes } }
    Set-DefaultScopes $client['id'] $wantedScopes
    Write-Int37Ok "scopes por defecto: $($wantedScopes -join ', ') (sin opcionales)"
    $sa = Get-Json "/clients/$($client['id'])/service-account-user" 'service account user'
    Set-ExactRealmRoles $sa['id'] @('REPORTS_CLIENT', 'REPORT_RUNNER')
    Set-Group $sa['id'] $groupFull['id']
    Write-Int37Ok "cuenta de servicio: roles REPORTS_CLIENT + REPORT_RUNNER, grupo $Group"

    Write-Int37Step 'Usuarios de catálogo'
    foreach ($u in $Users) {
        $user = Find-User $u.Name
        if (-not $user) {
            Assert-Int37Status (Invoke-Admin -Method POST -Path "/users" -Body @{
                username = $u.Name; enabled = $true; emailVerified = $true; firstName = 'Demo'; lastName = "$($u.Role) INT-3.7"
                email = "$($u.Name)@$MarkerDomain"; requiredActions = @() }) 201 "create $($u.Name)" | Out-Null
            $user = Find-User $u.Name
            Write-Int37Ok "$($u.Name) creado"
        } else { Write-Int37Ok "$($u.Name) ya existía (INT-3.7)" }
        Assert-Int37Status (Invoke-Admin -Method PUT -Path "/users/$($user['id'])/reset-password" -Body @{
            type = 'password'; value = $secrets[$u.Secret]; temporary = $false }) 204 "password of $($u.Name)" | Out-Null
        $default = @(Get-Json "/users/$($user['id'])/role-mappings/realm" 'role mappings') | Where-Object { $_['name'] -like 'default-roles-*' } | ForEach-Object { $_['name'] }
        Set-ExactRealmRoles $user['id'] (@($u.Role) + @($default))
        Set-Group $user['id'] $groupFull['id']
        Write-Int37Ok "$($u.Name): rol $($u.Role), grupo $Group, contraseña del almacén"
    }
    Write-Int37Step 'Hecho. Siguiente: -Verify'
}

# ------------------------------------------------------------------------------------------------ verify

$script:verifyFailures = 0
$script:humanReportsAudience = $false
function Test-Claim([string]$Who, [bool]$Condition, [string]$Text) {
    if ($Condition) { Write-Int37Ok "${Who}: $Text" } else { Write-Int37Fail "${Who}: $Text"; $script:verifyFailures++ }
}

function Test-Principal([string]$Who, [hashtable]$Claims, [string]$Type, [string]$Tenant, [string[]]$MustRoles,
                        [string[]]$MustNotRoles, [string[]]$MustAud, [string[]]$MustNotAud) {
    Write-Host "  $Who -> $(Format-Int37Claims $Claims)"
    $roles = Get-Int37ClaimList $Claims 'roles'
    $aud = Get-Int37ClaimList $Claims 'aud'
    Test-Claim $Who ($Claims['iss'] -eq $Issuer) "iss=$Issuer"
    Test-Claim $Who ($Claims['principal_type'] -eq $Type) "principal_type=$Type"
    Test-Claim $Who ($Claims['tenant'] -eq $Tenant) "tenant=$Tenant"
    foreach ($r in $MustRoles) { Test-Claim $Who ($roles -contains $r) "rol $r presente" }
    foreach ($r in $MustNotRoles) { Test-Claim $Who ($roles -notcontains $r) "rol $r ausente" }
    foreach ($a in $MustAud) { Test-Claim $Who ($aud -contains $a) "aud contiene $a" }
    foreach ($a in $MustNotAud) { Test-Claim $Who ($aud -notcontains $a) "aud no contiene $a" }
}

function Invoke-Verify {
    Open-AdminSession
    $t = Get-TenantConsistency
    Show-TenantConsistency $t
    if (-not $t.Consistent) { $script:verifyFailures++ }
    $tenant = $t.Semantic
    $others = @('VIEWER', 'STEWARD', 'AUDITOR', 'AI_AGENT', 'PLATFORM_ADMIN')

    Write-Int37Step "Token de $ClientId (client credentials)"
    $intelligence = Get-Int37ClientToken $KeycloakUrl $Realm $ClientId (Get-Int37Secret 'NEXORA_INTELLIGENCE_CLIENT_SECRET' @($store))
    Test-Principal $ClientId (ConvertFrom-Int37Jwt $intelligence) 'SERVICE' $tenant @('REPORTS_CLIENT', 'REPORT_RUNNER') `
        (@('EDITOR', 'APPROVER') + $others) @('nexora-semantic', 'nexora-reports') @()
    $pw = Invoke-Int37Http -Method POST -Url "$Issuer/protocol/openid-connect/token" -Form @{
        grant_type = 'password'; client_id = $ClientId; username = 'demo.editor'; password = 'x' }
    Test-Claim $ClientId ($pw.Status -in 400, 401) 'no admite password grant'

    Write-Int37Step 'Usuarios de catálogo (password grant con nexora-semantic-dev)'
    foreach ($u in $Users) {
        $token = Get-Int37PasswordToken $KeycloakUrl $Realm 'nexora-semantic-dev' $u.Name (Get-Int37Secret $u.Secret @($store))
        $not = @('EDITOR', 'APPROVER', 'REPORTS_CLIENT', 'REPORT_RUNNER') + $others | Where-Object { $_ -ne $u.Role }
        $claims = ConvertFrom-Int37Jwt $token
        Test-Principal $u.Name $claims 'HUMAN' $tenant @($u.Role) $not @('nexora-semantic') @('nexora-reports')
        if ((Get-Int37ClaimList $claims 'aud') -contains 'nexora-reports') { $script:humanReportsAudience = $true }
    }
    if ($script:humanReportsAudience -and @(Get-ActiveSharedReportsAudience (Find-Scope $SharedScope)).Count -gt 0) {
        Write-SharedAudienceHint
    }

    Write-Int37Step 'Cuenta de servicio de Reports (nexora-reports-dev)'
    $reportsToken = Get-Int37ClientToken $KeycloakUrl $Realm 'nexora-reports-dev' (Get-Int37Secret 'KC_REPORTS_CLIENT_SECRET' @($semanticEnv))
    Test-Principal 'nexora-reports-dev' (ConvertFrom-Int37Jwt $reportsToken) 'SERVICE' $tenant @('REPORTS_CLIENT') `
        (@('REPORT_RUNNER', 'EDITOR', 'APPROVER') + $others) @('nexora-semantic') @()

    Write-Int37Step 'Aceptación por los servicios (si están arrancados)'
    $s = Invoke-Int37Http -Url "$SemanticUrl/api/v1/data-sources?size=1" -Token $intelligence -TimeoutSec 10
    if ($s.Status -eq 0) { Write-Int37Warn 'Semantic no responde: comprobación omitida' }
    else { Test-Claim 'Semantic' ($s.Status -eq 200) "acepta el token de $ClientId (HTTP $($s.Status))" }
    $probe = Invoke-Int37Http -Method POST -Url "$ReportsUrl/api/v1/data-sources/$([guid]::NewGuid())/plans/$([guid]::NewGuid())/executions" `
        -Token $intelligence -Body @{ maxRows = 1 } -TimeoutSec 10
    if ($probe.Status -eq 0) { Write-Int37Warn 'Reports no responde: comprobación omitida' }
    else { Test-Claim 'Reports' ($probe.Status -notin 401, 403) "autentica y autoriza el token de $ClientId (HTTP $($probe.Status) para una fuente inexistente)" }

    Write-Int37Step 'Resultado'
    if ($script:verifyFailures -eq 0) { Write-Int37Ok 'Todas las comprobaciones de claims superadas' }
    else { Write-Int37Fail "$($script:verifyFailures) comprobaciones fallidas"; exit 1 }
}

# ------------------------------------------------------------------------------------------------ inventory

$SecretFieldPattern = '^(?i)(secret|password|credentials?|privatekey|private_key|client_secret|clientsecret|registrationaccesstoken|keystore.*|.*\.secret|.*password.*)$'

<# Removes every secret-like field (by name) from a JSON structure, recursively. #>
function Remove-SecretFields($Node) {
    if ($Node -is [System.Collections.IDictionary]) {
        $out = [ordered]@{}
        foreach ($k in $Node.Keys) {
            if ("$k" -match $SecretFieldPattern) { $out[$k] = '<omitido>' } else { $out[$k] = Remove-SecretFields $Node[$k] }
        }
        return $out
    }
    if ($Node -is [System.Collections.IList] -and $Node -isnot [string]) {
        return , @($Node | ForEach-Object { Remove-SecretFields $_ })
    }
    return $Node
}

function Get-AllUsers([string]$RealmName) {
    $all = [System.Collections.Generic.List[object]]::new()
    $first = 0; $max = 100
    while ($true) {
        $page = @((Assert-Int37Status (Invoke-Int37Admin -Session $script:adminSession -Url "$KeycloakUrl/admin/realms/$RealmName/users?first=$first&max=$max&briefRepresentation=false") 200 "users of $RealmName").Json)
        foreach ($u in $page) { $all.Add($u) }
        if ($page.Count -lt $max) { break }
        $first += $max
    }
    return , $all
}

function Invoke-Inventory {
    Open-AdminSession
    Write-Int37Step "Inventario de $Realm (solo lectura, sin secretos)"
    $export = (Assert-Int37Status (Invoke-Admin -Method POST -Path '/partial-export?exportClients=true&exportGroupsAndRoles=true') 200 'partial export').Json
    $export = Remove-SecretFields $export
    $users = foreach ($u in (Get-AllUsers $Realm)) {
        $roles = @(Get-Json "/users/$($u['id'])/role-mappings/realm" 'role mappings') | ForEach-Object { $_['name'] } | Sort-Object
        $groups = @(Get-Json "/users/$($u['id'])/groups" 'groups') | ForEach-Object { $_['path'] } | Sort-Object
        [ordered]@{ sub = $u['id']; username = $u['username']; enabled = $u['enabled']
            serviceAccountClientId = $(if ($u.Contains('serviceAccountClientId')) { $u['serviceAccountClientId'] } else { $null })
            email = $(if ($u.Contains('email')) { $u['email'] } else { $null })
            firstName = $(if ($u.Contains('firstName')) { $u['firstName'] } else { $null })
            lastName = $(if ($u.Contains('lastName')) { $u['lastName'] } else { $null })
            createdTimestamp = $(if ($u.Contains('createdTimestamp')) { $u['createdTimestamp'] } else { $null })
            attributes = Remove-SecretFields $(if ($u.Contains('attributes')) { $u['attributes'] } else { @{} })
            realmRoles = @($roles); groups = @($groups) }
    }
    $masterUsers = foreach ($u in (Get-AllUsers 'master')) { [ordered]@{ sub = $u['id']; username = $u['username']; enabled = $u['enabled'] } }

    # objects that exist only at runtime (lost if the container is recreated)
    $file = Get-Content -Raw -LiteralPath $RealmFile | ConvertFrom-Json -AsHashtable
    $fileRoles = @($file['roles']['realm'] | ForEach-Object { $_['name'] })
    $fileClients = @($file['clients'] | ForEach-Object { $_['clientId'] })
    $fileScopes = @($file['clientScopes'] | ForEach-Object { $_['name'] })
    $fileGroups = @($file['groups'] | ForEach-Object { $_['name'] })
    $fileUsers = @($file['users'] | ForEach-Object { $_['username'] })
    $builtInRoles = @('offline_access', 'uma_authorization', "default-roles-$Realm")
    $builtInClients = @('account', 'account-console', 'admin-cli', 'broker', 'realm-management', 'security-admin-console')
    $runtimeOnly = [ordered]@{
        roles = @(@($export['roles']['realm']) | ForEach-Object { $_['name'] } | Where-Object { $fileRoles -notcontains $_ -and $builtInRoles -notcontains $_ })
        clients = @(@($export['clients']) | ForEach-Object { $_['clientId'] } | Where-Object { $fileClients -notcontains $_ -and $builtInClients -notcontains $_ })
        clientScopes = @(@($export['clientScopes']) | ForEach-Object { $_['name'] } | Where-Object { $fileScopes -notcontains $_ })
        groups = @(@($export['groups']) | ForEach-Object { $_['name'] } | Where-Object { $fileGroups -notcontains $_ })
        users = @($users | Where-Object { -not $_.serviceAccountClientId -and $fileUsers -notcontains $_.username } | ForEach-Object { $_.username })
    }
    $now = (Get-Date).ToUniversalTime()
    $doc = [ordered]@{
        kind = 'nexora-keycloak-inventory/1'; generatedAt = $now.ToString('yyyy-MM-ddTHH:mm:ssZ'); keycloakUrl = $KeycloakUrl; realm = $Realm
        restorable = $false
        note = 'Inventario SIN SECRETOS. No es una copia restaurable: no contiene contraseñas, secretos de clientes, claves de firma ni credenciales, y Keycloak asigna ids nuevos al importar. Conserva los sub actuales como referencia de trazabilidad.'
        runtimeOnly = $runtimeOnly
        notes = @('clientScopes de runtimeOnly incluye los scopes integrados de Keycloak (basic, profile, roles...): compárelos con la lista conocida antes de concluir que son cambios manuales.')
        users = @($users); masterUsers = @($masterUsers); realmExport = $export }
    $json = $doc | ConvertTo-Json -Depth 50
    # last line of defence: no known secret value may appear in the inventory
    $known = @($semanticEnv.Keys | Where-Object { $_ -match 'PASSWORD|SECRET' } | ForEach-Object { $semanticEnv[$_] }) +
        @((Read-Int37SecretStore).Values)
    foreach ($v in ($known | Where-Object { $_ -and $_.Length -ge 6 })) {
        if ($json.Contains($v)) { throw 'The inventory would contain a secret value: not written' }
    }
    $dir = Join-Path (Initialize-Int37Home) 'inventory'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $path = Join-Path $dir "keycloak-$Realm-$($now.ToString('yyyyMMddHHmmss')).json"
    Set-Content -LiteralPath $path -Value $json -Encoding utf8
    Set-Int37OwnerOnly $path
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
    Write-Int37Ok "usuarios: $(@($users).Count) (con su sub) · roles: $(@($export['roles']['realm']).Count) · clientes: $(@($export['clients']).Count) · grupos: $(@($export['groups']).Count)"
    Write-Int37Step 'Objetos que solo existen en ejecución (se perderían al recrear el contenedor)'
    foreach ($k in $runtimeOnly.Keys) { Write-Host "  $k : $(if (@($runtimeOnly[$k]).Count) { @($runtimeOnly[$k]) -join ', ' } else { '—' })" }
    Write-Int37Ok "Inventario: $path"
    Write-Int37Ok "SHA-256: $hash"
    Write-Int37Warn 'Un inventario NO es una copia restaurable (sin contraseñas, secretos ni claves). No recree Keycloak para AUT-1.'
}

# ------------------------------------------------------------------------------------------------ shared audience

$SharedScope = 'nexora-semantic'
$SharedAudienceStateKey = 'sharedReportsAudience'

function Get-MapperConfig($Mapper) {
    if ($Mapper.Contains('config') -and $Mapper['config']) { return $Mapper['config'] } else { return @{} }
}

<# An audience mapper that adds nexora-reports (custom audience or client audience). #>
function Test-ReportsAudienceMapper($Mapper) {
    $cfg = Get-MapperConfig $Mapper
    return $Mapper['protocolMapper'] -eq 'oidc-audience-mapper' -and
        ($cfg['included.custom.audience'] -eq 'nexora-reports' -or $cfg['included.client.audience'] -eq 'nexora-reports')
}

<# Keycloak adds the claim to the access token unless access.token.claim is explicitly "false". #>
function Test-MapperActive($Mapper) { return "$((Get-MapperConfig $Mapper)['access.token.claim'])" -ne 'false' }

function Get-ActiveSharedReportsAudience($Scope) {
    if (-not $Scope -or -not $Scope.Contains('protocolMappers')) { return @() }
    return @($Scope['protocolMappers'] | Where-Object { (Test-ReportsAudienceMapper $_) -and (Test-MapperActive $_) })
}

function Write-SharedAudienceHint {
    Write-Int37Warn "Causa: el client scope compartido $SharedScope tiene un mapper de audiencia nexora-reports activo; añade esa audiencia a TODOS los tokens con $SharedScope (también a los humanos). No está en nexora-realm.json."
    Write-Int37Warn 'Corrección (requiere autorización): .\Invoke-Int37Keycloak.ps1 -SharedReportsAudience Disable -WhatIf, después sin -WhatIf; luego -Verify.'
}

function Invoke-SharedAudienceDisable {
    Write-Int37Step "Mapper de audiencia nexora-reports en el scope compartido $SharedScope"
    $sem = Find-Scope $SharedScope
    if (-not $sem) { throw "client scope $SharedScope not found" }
    $targets = @(Get-ActiveSharedReportsAudience $sem)
    if ($targets.Count -eq 0) { Write-Int37Ok 'no hay ningún mapper de audiencia nexora-reports activo: nada que hacer'; return }
    Write-Int37Ok "activos: $(($targets | ForEach-Object { $_['name'] }) -join ', ')"

    Write-Int37Step 'Comprobaciones previas'
    # nexora-intelligence must keep nexora-reports through the INT-3.7 scope (it never depended on the shared mapper)
    $client = Find-Client $ClientId
    if (-not (Test-ClientMarked $client)) { throw "client $ClientId (INT-3.7) not found: run -Apply first" }
    $own = Find-Scope $ScopeName
    if (-not (Test-ScopeMarked $own) -or @(Get-ActiveSharedReportsAudience $own).Count -eq 0) {
        throw "client scope $ScopeName (INT-3.7) with an active nexora-reports audience not found: run -Apply first"
    }
    $intelligenceScopes = @(@(Get-Json "/clients/$($client['id'])/default-client-scopes" 'default scopes') | ForEach-Object { $_['name'] })
    if ($intelligenceScopes -notcontains $ScopeName) { throw "$ScopeName is not a default scope of ${ClientId}: run -Apply first" }
    Write-Int37Ok "$ClientId conserva nexora-reports por su scope $ScopeName"

    Write-Int37Step 'Impacto: quién deja de recibir nexora-reports'
    $losers = [System.Collections.Generic.List[string]]::new()
    foreach ($c in @(Get-Json '/clients' 'clients')) {
        $names = @(@(Get-Json "/clients/$($c['id'])/default-client-scopes" 'default scopes') | ForEach-Object { $_['name'] })
        $ownAudience = $c.Contains('protocolMappers') -and @($c['protocolMappers'] | Where-Object { (Test-ReportsAudienceMapper $_) -and (Test-MapperActive $_) }).Count -gt 0
        if ($names -contains $SharedScope -and $names -notcontains $ScopeName -and -not $ownAudience) { $losers.Add($c['clientId']) }
    }
    Write-Host "  clientes con $SharedScope por defecto que pierden la audiencia: $(if ($losers.Count) { $losers -join ', ' } else { '—' })"
    Write-Host '  (nexora-reports-dev no la necesita: solo llama a Semantic; lo comprueba -Verify)'
    $runners = @(@(Get-Json '/roles/REPORT_RUNNER/users' 'REPORT_RUNNER holders') | Where-Object { "$($_['username'])" -notlike 'service-account-*' } |
        ForEach-Object { $_['username'] })
    if ($runners.Count -gt 0) {
        Write-Int37Warn "usuarios humanos con REPORT_RUNNER que ya no podrán llamar a Reports con un token de nexora-semantic-dev: $($runners -join ', '). Para llamar a Reports directamente (Postman), use un token de $ClientId."
    }
    Write-Int37Warn 'Los tokens ya emitidos conservan la audiencia hasta que caducan.'

    if (-not $PSCmdlet.ShouldProcess("scope $SharedScope", "desactivar $($targets.Count) mapper(s) de audiencia nexora-reports (sin borrarlos)")) { return }
    $state = Read-Int37State
    $saved = @(if ($state.ContainsKey($SharedAudienceStateKey)) { $state[$SharedAudienceStateKey] })
    $dir = Join-Path (Initialize-Int37Home) 'backup'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
    foreach ($m in $targets) {
        $original = $m | ConvertTo-Json -Depth 10 | ConvertFrom-Json -AsHashtable
        $backup = Join-Path $dir "keycloak-$SharedScope-mapper-$($m['name'])-$stamp.json"
        @{ scope = $SharedScope; scopeId = $sem['id']; mapper = $original } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $backup -Encoding utf8
        Set-Int37OwnerOnly $backup
        if (-not ($saved | Where-Object { $_['mapper']['id'] -eq $m['id'] })) {
            $saved += @{ scopeId = $sem['id']; scope = $SharedScope; mapper = $original; disabledAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') }
        }
        $state[$SharedAudienceStateKey] = @($saved)
        Write-Int37State $state       # recorded BEFORE the change, so -Revert can always undo it
        $changed = $m | ConvertTo-Json -Depth 10 | ConvertFrom-Json -AsHashtable
        if (-not $changed.Contains('config') -or -not $changed['config']) { $changed['config'] = @{} }
        foreach ($k in 'access.token.claim', 'introspection.token.claim', 'lightweight.claim', 'id.token.claim', 'userinfo.token.claim') {
            $changed['config'][$k] = 'false'
        }
        Assert-Int37Status (Invoke-Admin -Method PUT -Path "/client-scopes/$($sem['id'])/protocol-mappers/models/$($m['id'])" -Body $changed) 204 "disable mapper $($m['name'])" | Out-Null
        Write-Int37Ok "$($m['name']) desactivado (copia: $backup)"
    }
    $left = @(Get-ActiveSharedReportsAudience (Find-Scope $SharedScope))
    if ($left.Count -gt 0) { throw "Mapper still active after the change: $(($left | ForEach-Object { $_['name'] }) -join ', ')" }
    Write-Int37Step 'Hecho. Siguiente: -Verify'
}

<# Puts back the saved configuration of the shared mappers. Called by -SharedReportsAudience Restore and -Revert. #>
function Restore-SharedAudience {
    $state = Read-Int37State
    $saved = @(if ($state.ContainsKey($SharedAudienceStateKey)) { $state[$SharedAudienceStateKey] })
    if ($saved.Count -eq 0) { Write-Int37Ok "ningún mapper de $SharedScope desactivado por INT-3.7: nada que restaurar"; return }
    foreach ($s in $saved) {
        $m = $s['mapper']
        $path = "/client-scopes/$($s['scopeId'])/protocol-mappers/models"
        $current = Invoke-Admin -Path "$path/$($m['id'])"
        if ($current.Status -eq 200) {
            Assert-Int37Status (Invoke-Admin -Method PUT -Path "$path/$($m['id'])" -Body $m) 204 "restore mapper $($m['name'])" | Out-Null
        } elseif ($current.Status -eq 404) {
            $copy = $m | ConvertTo-Json -Depth 10 | ConvertFrom-Json -AsHashtable
            $copy.Remove('id')
            Assert-Int37Status (Invoke-Admin -Method POST -Path $path -Body $copy) 201 "recreate mapper $($m['name'])" | Out-Null
        } else { Assert-Int37Status $current 200 "mapper $($m['name'])" | Out-Null }
        Write-Int37Ok "$($m['name']) restaurado en $($s['scope'])"
    }
    $state.Remove($SharedAudienceStateKey)
    Write-Int37State $state
}

function Invoke-SharedAudience {
    Open-AdminSession
    if ($SharedReportsAudience -eq 'Disable') { Invoke-SharedAudienceDisable; return }
    Write-Int37Step "Restaurar el mapper de audiencia nexora-reports en $SharedScope"
    if (-not $PSCmdlet.ShouldProcess("scope $SharedScope", 'restaurar la configuración guardada')) { return }
    Restore-SharedAudience
}

# ------------------------------------------------------------------------------------------------ revert

function Invoke-Revert {
    Open-AdminSession
    if (-not $PSCmdlet.ShouldProcess("realm $Realm", 'eliminar solo los objetos marcados INT-3.7')) { return }
    Write-Int37Step 'Revertir INT-3.7'
    Restore-SharedAudience
    foreach ($u in $Users) {
        $user = Find-User $u.Name
        if ($user -and (Test-UserMarked $user)) {
            Assert-Int37Status (Invoke-Admin -Method DELETE -Path "/users/$($user['id'])") 204 "delete $($u.Name)" | Out-Null
            Write-Int37Ok "$($u.Name) eliminado"
        } elseif ($user) { Write-Int37Warn "$($u.Name) existe sin marca INT-3.7: no se toca" }
    }
    $client = Find-Client $ClientId
    if ($client -and (Test-ClientMarked $client)) {
        Assert-Int37Status (Invoke-Admin -Method DELETE -Path "/clients/$($client['id'])") 204 "delete $ClientId" | Out-Null
        Write-Int37Ok "$ClientId eliminado"
    } elseif ($client) { Write-Int37Warn "$ClientId existe sin marca INT-3.7: no se toca" }
    $scope = Find-Scope $ScopeName
    if ($scope -and (Test-ScopeMarked $scope)) {
        Assert-Int37Status (Invoke-Admin -Method DELETE -Path "/client-scopes/$($scope['id'])") 204 "delete scope $ScopeName" | Out-Null
        Write-Int37Ok "$ScopeName eliminado"
    } elseif ($scope) { Write-Int37Warn "$ScopeName existe sin marca INT-3.7: no se toca" }
    $role = Find-Role 'REPORT_RUNNER'
    if ($role -and (Test-RoleMarked $role)) {
        $holders = @(Get-Json '/roles/REPORT_RUNNER/users' 'role holders')
        if ($holders.Count -gt 0) {
            Write-Int37Warn "REPORT_RUNNER (INT-3.7) lo tienen otros $($holders.Count) usuarios: no se elimina"
        } else {
            Assert-Int37Status (Invoke-Admin -Method DELETE -Path "/roles/REPORT_RUNNER") 204 'delete REPORT_RUNNER' | Out-Null
            Write-Int37Ok 'REPORT_RUNNER eliminado'
        }
    } elseif ($role) { Write-Int37Ok 'REPORT_RUNNER no lo creó INT-3.7: se conserva' }
}

switch ($PSCmdlet.ParameterSetName) {
    'Inspect' { Invoke-Inspect }
    'Apply' { Invoke-Apply }
    'Verify' { Invoke-Verify }
    'Revert' { Invoke-Revert }
    'Inventory' { Invoke-Inventory }
    'SharedAudience' { Invoke-SharedAudience }
}
