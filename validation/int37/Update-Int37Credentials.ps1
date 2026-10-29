#Requires -Version 7.0
<#
.SYNOPSIS
  INT-3.7: credentials of the local demo environment. Checks, rotates and creates secrets without ever printing them.

.DESCRIPTION
  -Check (default, read-only)
      Reports, by NAME only, which secrets exist, which are too short and which share a value. Covers Semantic's
      docker/.env, Reports' docker/.env and the INT-3.7 secret store.

  -Rotate (MODIFIES KEYCLOAK AND Semantic's docker/.env; REQUIRES AUTHORIZATION) — IN PLACE, without recreating
      Keycloak (users, their sub, R1 objects, signing keys and every runtime change are kept).
      New random values for KC_DEV_USER_PASSWORD (the development users of nexora-realm.json) and
      KC_REPORTS_CLIENT_SECRET (nexora-reports-dev), and for KC_BOOTSTRAP_ADMIN_PASSWORD (master admin) only if it is
      short or shared, or with -IncludeAdmin. The admin is always rotated LAST and its new access is verified.
      Preconditions: no pending journal, and every current .env credential authenticates in Keycloak. Every new
      value is verified with a token before the .env is replaced in one atomic write. On failure the change is
      rolled back automatically; if the process is interrupted, run -Recover. Engine: Int37Rotation.psm1.

  -RotatePostgres -Target Semantic|Reports (MODIFIES POSTGRESQL ROLES AND ONE .env; REQUIRES AUTHORIZATION; AUT-1b)
      In place, without recreating containers. One container and one .env per run:
        Semantic -> roles nexora_semantic_app, nexora_semantic_owner, then postgres (superuser)   [Semantic docker/.env]
        Reports  -> roles nexora_reports_app, nexora_reader, then nexora_reports_owner (superuser) [Reports docker/.env]
      Preconditions: no pending journal; passwordless superuser access through the container's LOCAL socket (the
      recovery path) checked with pg_hba_file_rules; the TCP verification path refuses a wrong password; every
      current .env value authenticates over TCP. Passwords are set as SCRAM-SHA-256 verifiers computed here, so the
      server never receives them in clear text; they travel to psql only through stdin or the process environment.
      Each new password is verified with a real TCP login; the .env is replaced in one atomic write. Automatic
      rollback on failure; -Recover after an interruption.

  -Recover (MODIFIES KEYCLOAK OR POSTGRESQL, AND/OR THE .env; REQUIRES AUTHORIZATION)
      Finishes an interrupted rotation (Keycloak or PostgreSQL, from the TYPE of the journal) from its journal ($HOME/.nexora/int37/rotation-journal.env): if the .env
      already holds every new value Keycloak is completed (roll forward); otherwise everything returns to the
      previous values (roll back). Safe to repeat.

  -InitializeStore (creates the local secret store, outside every repository)
      $HOME\.nexora\int37\secrets.env (or $env:NEXORA_INT37_HOME), readable only by the current user, with
      NEXORA_INTELLIGENCE_CLIENT_SECRET, NEXORA_DEMO_EDITOR_PASSWORD, NEXORA_DEMO_APPROVER_PASSWORD and
      NEXORA_SINTETICO_READER_PASSWORD. Existing values are kept unless -Force.

  -SetTenantDemoId <uuid> (modifies Semantic's docker/.env; REQUIRES AUTHORIZATION)
      Writes KC_TENANT_DEMO_ID = the UUID of tenant "demo" registered in Semantic (see Invoke-Int37Keycloak.ps1
      -Inspect). Takes effect when Keycloak is recreated.

  Supports -WhatIf (-Rotate shows the plan by name only and changes nothing).
#>
[CmdletBinding(SupportsShouldProcess = $true, DefaultParameterSetName = 'Check')]
param(
    [Parameter(ParameterSetName = 'Check')][switch]$Check,
    [Parameter(ParameterSetName = 'Rotate', Mandatory = $true)][switch]$Rotate,
    [Parameter(ParameterSetName = 'Rotate')][switch]$IncludeAdmin,
    [Parameter(ParameterSetName = 'Recover', Mandatory = $true)][switch]$Recover,
    [Parameter(ParameterSetName = 'Postgres', Mandatory = $true)][switch]$RotatePostgres,
    [Parameter(ParameterSetName = 'Postgres', Mandatory = $true)][ValidateSet('Semantic', 'Reports')][string]$Target,
    [Parameter(ParameterSetName = 'Store', Mandatory = $true)][switch]$InitializeStore,
    [Parameter(ParameterSetName = 'Store')][switch]$Force,
    [Parameter(ParameterSetName = 'Tenant', Mandatory = $true)][string]$SetTenantDemoId,
    [string]$SemanticEnvFile = (Join-Path $PSScriptRoot '../../../Nexora-Semantic/docker/.env'),
    [string]$ReportsEnvFile = (Join-Path $PSScriptRoot '../../../Nexora-Reports/docker/.env'),
    [string]$RealmFile = (Join-Path $PSScriptRoot '../../../Nexora-Semantic/docker/keycloak/nexora-realm.json'),
    [string]$KeycloakUrl = 'http://localhost:8180',
    [string]$Realm = 'nexora',
    [string]$UserClient = 'nexora-semantic-dev',
    [string]$SemanticContainer = 'nexora-semantic-dev-postgres-1',
    [string]$ReportsContainer = 'nexora-reports-postgres',
    [string]$Docker = 'docker'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Int37Common.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'Int37Rotation.psm1') -Force

$StoreKeys = @('NEXORA_INTELLIGENCE_CLIENT_SECRET', 'NEXORA_DEMO_EDITOR_PASSWORD', 'NEXORA_DEMO_APPROVER_PASSWORD',
    'NEXORA_SINTETICO_READER_PASSWORD')
$SemanticKeys = @('POSTGRES_PASSWORD', 'SEMANTIC_DB_OWNER_PASSWORD', 'SEMANTIC_DB_APP_PASSWORD',
    'KC_BOOTSTRAP_ADMIN_PASSWORD', 'KC_DEV_USER_PASSWORD', 'KC_REPORTS_CLIENT_SECRET')
$ReportsKeys = @('REPORTS_DB_OWNER_PASSWORD', 'REPORTS_DB_APP_PASSWORD', 'SYNTHETIC_READER_PASSWORD')

function Get-AllSecrets {
    $semantic = Read-Int37DotEnv $SemanticEnvFile
    $reports = Read-Int37DotEnv $ReportsEnvFile
    $store = Read-Int37SecretStore
    $all = @{}
    foreach ($k in $SemanticKeys) { $all["semantic:$k"] = $semantic[$k] }
    foreach ($k in $ReportsKeys) { $all["reports:$k"] = $reports[$k] }
    foreach ($k in $StoreKeys) { $all["int37:$k"] = $store[$k] }
    return $all
}

function Show-Check {
    Write-Int37Step 'Secretos (solo nombres; ningún valor se muestra)'
    if (-not (Test-Path $SemanticEnvFile)) { Write-Int37Fail "No existe $SemanticEnvFile" }
    if (-not (Test-Path $ReportsEnvFile)) { Write-Int37Warn "No existe $ReportsEnvFile" }
    $all = Get-AllSecrets
    foreach ($name in ($all.Keys | Sort-Object)) {
        if ($all[$name]) { Write-Int37Ok "$name presente ($($all[$name].Length) caracteres)" }
        else { Write-Int37Warn "$name ausente" }
    }
    $present = @{}
    foreach ($k in $all.Keys) { if ($all[$k]) { $present[$k] = $all[$k] } }
    $problems = Test-Int37SecretSet $present
    # The scripts read a secret from an environment variable FIRST: a stale variable in this session (for example
    # left by an earlier validation) silently overrides the rotated value of the .env file or of the store.
    Write-Int37Step 'Variables de entorno que tienen prioridad sobre los ficheros'
    $overrides = 0
    foreach ($name in ($all.Keys | Sort-Object)) {
        $key = $name.Split(':')[1]
        $fromEnv = [Environment]::GetEnvironmentVariable($key)
        if (-not $fromEnv) { continue }
        $overrides++
        if ($all[$name] -and $fromEnv -cne $all[$name]) {
            $problems.Add("environment variable $key is set and differs from ${name}: remove it from this session (Remove-Item Env:$key)")
        } else {
            Write-Int37Warn "la variable de entorno $key está definida (mismo valor que $name); conviene quitarla"
        }
    }
    if ($overrides -eq 0) { Write-Int37Ok 'ninguna' }
    $state = Read-Int37State
    if (-not $state.ContainsKey('credentialsRotatedAt')) {
        $problems.Add('KC_DEV_USER_PASSWORD and KC_REPORTS_CLIENT_SECRET were exposed on 29 Oct 2026 and have not been rotated with this script')
    } else {
        $when = $state['credentialsRotatedAt']
        if ($when -is [datetime]) { $when = $when.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') }
        Write-Int37Ok "Rotación registrada: $when"
    }
    if (Test-Int37RotationPending (Get-Int37RotationJournalFile)) {
        $problems.Add('a rotation did not finish (pending journal): run Update-Int37Credentials.ps1 -Recover before anything else')
    }
    Write-Int37Step 'Resultado'
    if ($problems.Count -eq 0) { Write-Int37Ok 'Sin problemas'; return $true }
    $problems | ForEach-Object { Write-Int37Fail $_ }
    return $false
}

<# Development users and service client whose credentials come from the .env, read from nexora-realm.json. #>
function Get-RealmTargets {
    if (-not (Test-Path -LiteralPath $RealmFile)) { throw "No existe $RealmFile" }
    $r = Get-Content -Raw -LiteralPath $RealmFile | ConvertFrom-Json -AsHashtable
    $users = @($r['users'] | Where-Object { $_.Contains('credentials') -and
            @($_['credentials'] | Where-Object { "$($_['value'])" -match '^\$\{KC_DEV_USER_PASSWORD(:[^}]*)?\}$' }).Count -gt 0 } |
        ForEach-Object { $_['username'] })
    $client = @($r['clients'] | Where-Object { "$($_['secret'])" -match '^\$\{KC_REPORTS_CLIENT_SECRET(:[^}]*)?\}$' } |
        ForEach-Object { $_['clientId'] }) | Select-Object -First 1
    if ($users.Count -eq 0) { throw 'No development users with KC_DEV_USER_PASSWORD in the realm file' }
    if (-not $client) { throw 'No client with KC_REPORTS_CLIENT_SECRET in the realm file' }
    return @{ Users = $users; Client = $client }
}

<# Real Keycloak operations for Int37Rotation (admin REST API and token endpoint). #>
function New-KeycloakOps {
    $kc = $KeycloakUrl; $realm = $Realm; $userClient = $UserClient
    $token = { param([string]$RealmName, [hashtable]$Form)
        (Invoke-Int37Http -Method POST -Url "$kc/realms/$RealmName/protocol/openid-connect/token" -Form $Form).Status -eq 200 }.GetNewClosure()
    return @{
        TestAdmin = { param([string]$Password)
            & $token 'master' @{ grant_type = 'password'; client_id = 'admin-cli'; username = 'admin'; password = $Password } }.GetNewClosure()
        TestUser = { param([string]$User, [string]$Password)
            & $token $realm @{ grant_type = 'password'; client_id = $userClient; username = $User; password = $Password } }.GetNewClosure()
        TestClient = { param([string]$Client, [string]$Secret)
            & $token $realm @{ grant_type = 'client_credentials'; client_id = $Client; client_secret = $Secret } }.GetNewClosure()
        OpenAdmin = { param([string]$Password)
            $s = New-Int37AdminSession -KeycloakUrl $kc -Password $Password
            Get-Int37AdminToken $s | Out-Null
            return $s }.GetNewClosure()
        SetUserPassword = { param($Session, [string]$User, [string]$Password)
            $found = @((Assert-Int37Status (Invoke-Int37Admin -Session $Session -Url "$kc/admin/realms/$realm/users?username=$User&exact=true") 200 "user $User").Json)
            if ($found.Count -ne 1) { throw "user $User not found" }
            Assert-Int37Status (Invoke-Int37Admin -Session $Session -Method PUT -Url "$kc/admin/realms/$realm/users/$($found[0]['id'])/reset-password" `
                -Body @{ type = 'password'; value = $Password; temporary = $false }) 204 "password of $User" | Out-Null }.GetNewClosure()
        SetClientSecret = { param($Session, [string]$Client, [string]$Secret)
            $found = @((Assert-Int37Status (Invoke-Int37Admin -Session $Session -Url "$kc/admin/realms/$realm/clients?clientId=$Client") 200 "client $Client").Json)
            if ($found.Count -ne 1) { throw "client $Client not found" }
            $full = (Assert-Int37Status (Invoke-Int37Admin -Session $Session -Url "$kc/admin/realms/$realm/clients/$($found[0]['id'])") 200 "client $Client").Json
            $full['secret'] = $Secret
            Assert-Int37Status (Invoke-Int37Admin -Session $Session -Method PUT -Url "$kc/admin/realms/$realm/clients/$($found[0]['id'])" -Body $full) 204 "secret of $Client" | Out-Null }.GetNewClosure()
        SetAdminPassword = { param($Session, [string]$Password)
            $found = @((Assert-Int37Status (Invoke-Int37Admin -Session $Session -Url "$kc/admin/realms/master/users?username=admin&exact=true") 200 'master admin').Json)
            if ($found.Count -ne 1) { throw 'master admin user not found' }
            Assert-Int37Status (Invoke-Int37Admin -Session $Session -Method PUT -Url "$kc/admin/realms/master/users/$($found[0]['id'])/reset-password" `
                -Body @{ type = 'password'; value = $Password; temporary = $false }) 204 'password of the master admin' | Out-Null }.GetNewClosure()
    }
}

<# Runs the docker CLI; secrets only through -Environment (this process, removed afterwards) or stdin. Output is
   returned to the caller and never printed here. #>
function Invoke-DockerCapture([string[]]$Arguments, [string]$StdIn, [hashtable]$Environment = @{}) {
    $previous = @{}
    foreach ($k in $Environment.Keys) { $previous[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $Environment[$k]) }
    try {
        if ($PSBoundParameters.ContainsKey('StdIn')) { $out = $StdIn | & $Docker @Arguments 2>&1 } else { $out = & $Docker @Arguments 2>&1 }
        return @{ Code = $LASTEXITCODE; Out = (@($out | ForEach-Object { "$_" }) -join "`n") }
    } finally {
        foreach ($k in $Environment.Keys) { [Environment]::SetEnvironmentVariable($k, $previous[$k]) }
    }
}

<# PostgreSQL rotation plan of a target, with role names from the .env files and the init scripts. #>
function Get-PostgresPlan([string]$Which) {
    if ($Which -eq 'Semantic') {
        $e = Read-Int37DotEnv $SemanticEnvFile
        $app = if ($e['SEMANTIC_DB_APP_USER']) { $e['SEMANTIC_DB_APP_USER'] } else { 'nexora_semantic_app' }
        $owner = if ($e['SEMANTIC_DB_OWNER_USER']) { $e['SEMANTIC_DB_OWNER_USER'] } else { 'nexora_semantic_owner' }
        return @{ EnvFile = $SemanticEnvFile; Plan = (New-Int37PgPlan -Target 'Semantic' -Container $SemanticContainer -Superuser 'postgres' -Items @(
            @{ Key = 'SEMANTIC_DB_APP_PASSWORD'; Role = $app; Database = 'nexora_semantic'; Superuser = $false },
            @{ Key = 'SEMANTIC_DB_OWNER_PASSWORD'; Role = $owner; Database = 'nexora_semantic'; Superuser = $false },
            @{ Key = 'POSTGRES_PASSWORD'; Role = 'postgres'; Database = 'nexora_semantic'; Superuser = $true })) }
    }
    $e = Read-Int37DotEnv $ReportsEnvFile
    $owner = if ($e['REPORTS_DB_OWNER_USER']) { $e['REPORTS_DB_OWNER_USER'] } else { 'nexora_reports_owner' }
    return @{ EnvFile = $ReportsEnvFile; Plan = (New-Int37PgPlan -Target 'Reports' -Container $ReportsContainer -Superuser $owner -Items @(
        @{ Key = 'REPORTS_DB_APP_PASSWORD'; Role = 'nexora_reports_app'; Database = 'nexora_reports'; Superuser = $false },
        @{ Key = 'SYNTHETIC_READER_PASSWORD'; Role = 'nexora_reader'; Database = 'synthetic_erp'; Superuser = $false },
        @{ Key = 'REPORTS_DB_OWNER_PASSWORD'; Role = $owner; Database = 'nexora_reports'; Superuser = $true })) }
}

<# Real PostgreSQL operations through docker exec (see Int37Rotation.psm1, Invoke-Int37PgRotation). #>
function New-PostgresOps([string]$Container, [string]$Superuser) {
    $running = Invoke-DockerCapture @('inspect', '-f', '{{.State.Running}}', $Container)
    if ($running.Code -ne 0 -or $running.Out.Trim() -ne 'true') { throw "Container $Container is not running" }
    $ipOut = Invoke-DockerCapture @('inspect', '-f', '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}', $Container)
    $ip = @($ipOut.Out.Trim().Split(' ', [StringSplitOptions]::RemoveEmptyEntries)) | Select-Object -First 1
    if (-not $ip) { throw "Container $Container has no network address" }
    $capture = ${function:Invoke-DockerCapture}
    $c = $Container; $su = $Superuser
    return @{
        # Superuser through the LOCAL socket, no password (-w): this is also the recovery path.
        TestLocal = {
            $r = & $capture @('exec', '-i', $c, 'psql', '-U', $su, '-d', 'postgres', '-X', '-w', '-tA', '-v', 'ON_ERROR_STOP=1') `
                "select current_setting('is_superuser');`nselect count(*) from pg_hba_file_rules where type = 'local' and auth_method in ('trust', 'peer') and error is null;`n"
            $lines = @($r.Out.Split("`n") | ForEach-Object { $_.Trim() } | Where-Object { $_ })
            $ok = $r.Code -eq 0 -and $lines.Count -ge 2 -and $lines[0] -eq 'on' -and [int]$lines[1] -ge 1
            return @{ Ok = $ok; Detail = $(if ($r.Code -ne 0) { "psql exit $($r.Code)" } else { "superuser=$($lines[0]) local-trust-rules=$($lines[1])" }) } }.GetNewClosure()
        # Real TCP login to the container's own network address (not loopback, so pg_hba applies scram-sha-256).
        TestLogin = { param([string]$Role, [string]$Database, [string]$Password)
            $r = & $capture @('exec', '-e', 'PGPASSWORD', $c, 'psql', '-h', $ip, '-p', '5432', '-U', $Role, '-d', $Database, '-X', '-w', '-tA', '-c', 'select 1') -Environment @{ PGPASSWORD = $Password }
            return ($r.Code -eq 0 -and $r.Out.Trim() -eq '1') }.GetNewClosure()
        # ALTER ROLE with a SCRAM-SHA-256 verifier through stdin: the clear password never leaves this process.
        SetPassword = { param([string]$Role, [string]$Password)
            $verifier = ConvertTo-Int37ScramVerifier $Password
            $r = & $capture @('exec', '-i', $c, 'psql', '-U', $su, '-d', 'postgres', '-X', '-w', '-q', '-v', 'ON_ERROR_STOP=1') "ALTER ROLE `"$Role`" PASSWORD '$verifier';`n"
            if ($r.Code -ne 0) { throw "ALTER ROLE $Role failed (psql exit $($r.Code))" } }.GetNewClosure()
    }
}

switch ($PSCmdlet.ParameterSetName) {
    'Check' {
        if (-not (Show-Check)) { exit 1 }
    }
    'Rotate' {
        if (-not (Test-Path $SemanticEnvFile)) { throw "No existe $SemanticEnvFile" }
        $semantic = Read-Int37DotEnv $SemanticEnvFile
        $realmDef = Get-RealmTargets
        $admin = Get-Int37Secret 'KC_BOOTSTRAP_ADMIN_PASSWORD' @($semantic)
        $others = @{}
        foreach ($k in $SemanticKeys) { if ($k -ne 'KC_BOOTSTRAP_ADMIN_PASSWORD' -and $semantic[$k]) { $others[$k] = $semantic[$k] } }
        $adminShared = @($others.Values | Where-Object { $_ -ceq $admin }).Count -gt 0
        $rotateAdmin = [bool]($IncludeAdmin -or $admin.Length -lt 24 -or $adminShared)
        $plan = New-Int37RotationPlan -Users $realmDef.Users -Client $realmDef.Client -Admin $rotateAdmin
        Write-Int37Step 'Plan de rotación en caliente (sin recrear Keycloak; solo nombres)'
        Write-Host "  $($plan.UserKey): usuarios $($plan.Users -join ', ')"
        Write-Host "  $($plan.ClientKey): cliente $($plan.Client)"
        Write-Host "  $($plan.AdminKey): $(if ($rotateAdmin) { 'administrador de master, AL FINAL' } else { 'no se rota (no es corta ni compartida; use -IncludeAdmin para forzarlo)' })"
        Write-Host "  .env: $SemanticEnvFile (una sola escritura atómica al final)"
        Write-Host "  diario: $(Get-Int37RotationJournalFile)"
        if (-not $PSCmdlet.ShouldProcess("Keycloak $KeycloakUrl y $SemanticEnvFile", "rotar $($plan.Keys -join ', ')")) { return }
        $old = @{}; $new = @{}
        foreach ($k in $plan.Keys) { $old[$k] = Get-Int37Secret $k @($semantic); $new[$k] = New-Int37Secret 32 }
        Initialize-Int37Home | Out-Null
        $result = Invoke-Int37Rotation -Plan $plan -Old $old -New $new -AdminPassword $admin -EnvFile $SemanticEnvFile `
            -JournalFile (Get-Int37RotationJournalFile) -Ops (New-KeycloakOps)
        $state = Read-Int37State
        $state['credentialsRotatedAt'] = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        $state['credentialsRotated'] = @($result.Keys)
        Write-Int37State $state
        Write-Int37Ok "Rotación completada en caliente: $($result.Keys -join ', ')"
        Write-Int37Warn 'Reinicie Reports si está arrancado (toma KC_REPORTS_CLIENT_SECRET al arrancar) y actualice Postman u otras herramientas con la nueva contraseña de desarrollo (léala del .env; no se muestra).'
    }
    'Postgres' {
        $p = Get-PostgresPlan $Target
        $plan = $p.Plan
        $envValues = Read-Int37DotEnv $p.EnvFile
        Write-Int37Step "Plan de rotación PostgreSQL en caliente: $Target (sin recrear contenedores; solo nombres)"
        Write-Host "  contenedor: $($plan.Container) · acceso de recuperación: $($plan.Superuser) por el socket local"
        foreach ($i in $plan.Items) { Write-Host "  $($i.Key): rol $($i.Role) (base $($i.Database))$(if ($i.Superuser) { ' — superusuario, AL FINAL' })" }
        Write-Host "  .env: $($p.EnvFile) (una sola escritura atómica al final)"
        Write-Host "  diario: $(Get-Int37RotationJournalFile)"
        Write-Host "  contraseñas enviadas como verificadores SCRAM-SHA-256 (nunca en claro al servidor)"
        if (-not $PSCmdlet.ShouldProcess("$($plan.Container) y $($p.EnvFile)", "rotar $($plan.Keys -join ', ')")) { return }
        $old = @{}; $new = @{}
        foreach ($k in $plan.Keys) { $old[$k] = Get-Int37Secret $k @($envValues); $new[$k] = New-Int37Secret 32 }
        Initialize-Int37Home | Out-Null
        $result = Invoke-Int37PgRotation -Plan $plan -Old $old -New $new -EnvFile $p.EnvFile -JournalFile (Get-Int37RotationJournalFile) `
            -Ops (New-PostgresOps $plan.Container $plan.Superuser)
        Write-Int37Ok "Rotación PostgreSQL completada ($Target): $($result.Keys -join ', ')"
        Write-Int37Warn "Reinicie $Target ahora: sus conexiones abiertas siguen funcionando, pero las nuevas del pool necesitan la contraseña nueva del .env."
    }
    'Recover' {
        $journal = Get-Int37RotationJournalFile
        if (-not (Test-Int37RotationPending $journal)) { Write-Int37Ok 'No hay ninguna rotación pendiente'; return }
        $j = Read-Int37RotationJournal $journal
        $type = if ($j.ContainsKey('TYPE')) { $j['TYPE'] } else { 'keycloak' }
        if ($type -eq 'postgres') {
            if (-not $PSCmdlet.ShouldProcess("$($j['CONTAINER']) y $($j['ENV_FILE'])", 'recuperar la rotación PostgreSQL interrumpida')) { return }
            $result = Invoke-Int37PgRecovery -JournalFile $journal -Ops (New-PostgresOps $j['CONTAINER'] $j['SUPERUSER'])
            Write-Int37Ok "Recuperación PostgreSQL ($($j['TARGET'])): $($result.Direction) ($($result.Keys -join ', '))"
            Write-Int37Warn "Reinicie $($j['TARGET']) si está arrancado."
            return
        }
        if (-not $PSCmdlet.ShouldProcess("Keycloak $KeycloakUrl y $SemanticEnvFile", 'recuperar la rotación interrumpida')) { return }
        $result = Invoke-Int37RotationRecovery -JournalFile $journal -Ops (New-KeycloakOps)
        Write-Int37Ok "Recuperación: $($result.Direction) ($($result.Keys -join ', '))"
        Write-Int37Warn 'Reinicie Reports si está arrancado.'
    }
    'Store' {
        Initialize-Int37Home | Out-Null
        $file = Get-Int37SecretsFile
        $store = Read-Int37SecretStore
        if ($PSCmdlet.ShouldProcess($file, 'crear o completar el almacén de secretos de INT-3.7')) {
            if (-not (Test-Path $file)) { New-Item -ItemType File -Path $file | Out-Null }
            Set-Int37OwnerOnly $file
            foreach ($k in $StoreKeys) {
                if ($Force -or -not $store[$k]) {
                    Set-Int37DotEnvValue $file $k (New-Int37Secret 32)
                    Write-Int37Ok "$k generado"
                } else {
                    Write-Int37Ok "$k ya existía (se conserva)"
                }
            }
            Set-Int37OwnerOnly $file
            Write-Int37Ok "Almacén: $file (solo el usuario actual)"
        }
    }
    'Tenant' {
        $id = $SetTenantDemoId.Trim().ToLowerInvariant()
        if (-not (Test-Int37CanonicalUuid $id) -or $id -eq (Get-Int37ZeroUuid)) { throw 'SetTenantDemoId must be a canonical, non-zero UUID' }
        if ($PSCmdlet.ShouldProcess($SemanticEnvFile, "KC_TENANT_DEMO_ID=$id")) {
            Set-Int37DotEnvValue $SemanticEnvFile 'KC_TENANT_DEMO_ID' $id
            Write-Int37Ok "KC_TENANT_DEMO_ID=$id escrito. Efectivo tras recrear Keycloak."
        }
    }
}
