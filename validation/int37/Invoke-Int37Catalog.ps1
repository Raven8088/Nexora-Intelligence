#Requires -Version 7.0
<#
.SYNOPSIS
  INT-3.7: registers the demo data source in Semantic, imports its metadata through Discovery and creates and
  approves the seven semantic definitions. REQUIRES AUTHORIZATION (writes development data in Semantic).

.DESCRIPTION
  Uses only the existing contracts of the services:
    1. steward.demo (STEWARD) creates the data source "wellnode-sintetico" (or reuses it if it already exists).
    2. Discovery (POST /api/discovery) reads ONLY schema "sintetico" of nexora_demo_sintetico with the read-only
       role; steward.demo imports the discovery-api/0.1 envelope (POST .../snapshots).
    3. The import must contain exactly the two expected tables; otherwise the script stops.
    4. demo.editor (EDITOR) creates the seven definitions; demo.approver (APPROVER, another human) approves them
       with If-Match. Existing APPROVED definitions are kept; DRAFT ones are approved; any other state stops.
    5. Every definition must end APPROVED and usable; dimensions are checked against the expected valueType.
    6. tenantId, dataSourceId and definition ids are saved in the local state file (no secrets), used by
       Start-Int37Service.ps1 and Test-Int37EndToEnd.ps1.
  Idempotent. Only tokens' claim summaries are printed; never tokens or passwords.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$KeycloakUrl = 'http://localhost:8180',
    [string]$Realm = 'nexora',
    [string]$SemanticUrl = 'http://localhost:8080',
    [string]$DiscoveryUrl = 'http://localhost:8070',
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 5434,
    [string]$Database = 'nexora_demo_sintetico',
    [string]$Schema = 'sintetico',
    [string]$DataSourceKey = 'wellnode-sintetico',
    [string]$Alias = 'wellnode-sintetico',
    [switch]$Reimport,
    [string]$SemanticEnvFile = (Join-Path $PSScriptRoot '../../../Nexora-Semantic/docker/.env')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Import-Module (Join-Path $PSScriptRoot 'Int37Common.psm1') -Force

$semanticEnv = Read-Int37DotEnv $SemanticEnvFile
$store = Read-Int37SecretStore
$Api = "$SemanticUrl/api/v1/data-sources"
$ExpectedTables = @("`"$Schema`".`"wm_well`"", "`"$Schema`".`"bkw_electronic_logs`"")

function Obj([string]$Table, [string]$Column) {
    $key = "`"$Schema`".`"$Table`"" + $(if ($Column) { ".`"$Column`"" } else { '' })
    if (-not $script:objects.ContainsKey($key)) { throw "physical object $key was not imported" }
    return $script:objects[$key]
}

# ------------------------------------------------------------------------------------------------ tokens

Write-Int37Step 'Tokens'
$steward = Get-Int37PasswordToken $KeycloakUrl $Realm 'nexora-semantic-dev' 'steward.demo' (Get-Int37Secret 'KC_DEV_USER_PASSWORD' @($semanticEnv))
$editor = Get-Int37PasswordToken $KeycloakUrl $Realm 'nexora-semantic-dev' 'demo.editor' (Get-Int37Secret 'NEXORA_DEMO_EDITOR_PASSWORD' @($store))
$approver = Get-Int37PasswordToken $KeycloakUrl $Realm 'nexora-semantic-dev' 'demo.approver' (Get-Int37Secret 'NEXORA_DEMO_APPROVER_PASSWORD' @($store))
$claims = ConvertFrom-Int37Jwt $steward
$tenant = "$($claims['tenant'])"
foreach ($t in @($editor, $approver)) {
    if ((ConvertFrom-Int37Jwt $t)['tenant'] -ne $tenant) { throw 'steward.demo, demo.editor and demo.approver must share the tenant' }
}
if (-not (Test-Int37CanonicalUuid $tenant) -or $tenant -eq (Get-Int37ZeroUuid)) { throw 'Tenant demo not configured (run Invoke-Int37Keycloak.ps1 -Inspect)' }
Write-Int37Ok "steward.demo  $(Format-Int37Claims $claims)"
Write-Int37Ok "demo.editor   $(Format-Int37Claims (ConvertFrom-Int37Jwt $editor))"
Write-Int37Ok "demo.approver $(Format-Int37Claims (ConvertFrom-Int37Jwt $approver))"

if (-not $PSCmdlet.ShouldProcess("Semantic, tenant $tenant", "registrar $DataSourceKey y aprobar el catálogo de la demo")) { return }

# ------------------------------------------------------------------------------------------------ data source

Write-Int37Step "Fuente $DataSourceKey"
$list = Assert-Int37Status (Invoke-Int37Http -Url "$Api`?size=200" -Token $steward) 200 'list data sources'
$ds = @($list.Json['items']) | Where-Object { $_['key'] -eq $DataSourceKey } | Select-Object -First 1
if ($ds) {
    if ($ds['status'] -ne 'ACTIVE') { throw "data source $DataSourceKey is $($ds['status'])" }
    if ($ds['databaseName'] -ne $Database) { throw "data source $DataSourceKey points to database $($ds['databaseName'])" }
    Write-Int37Ok "ya existía: $($ds['id'])"
} else {
    $ds = (Assert-Int37Status (Invoke-Int37Http -Method POST -Url $Api -Token $steward -Body @{
        key = $DataSourceKey; name = 'Wellnode - DATOS SINTÉTICOS (demo INT-3.7)'; engine = 'postgresql'
        databaseName = $Database
        description = 'Datos ficticios con la estructura mínima de wm_well y bkw_electronic_logs. No son datos de Wellnode.' }) 201 'create data source').Json
    Write-Int37Ok "creada: $($ds['id'])"
}
$dsId = "$($ds['id'])"

# ------------------------------------------------------------------------------------------------ metadata

function Read-Objects {
    $script:objects = @{}
    $script:tables = @()
    $page = 0
    do {
        $r = Assert-Int37Status (Invoke-Int37Http -Url "$Api/$dsId/objects?size=200&page=$page" -Token $editor) 200 'objects'
        foreach ($item in @($r.Json['items'])) {
            $script:objects[$item['naturalKey']] = $item['id']
            if ($item['type'] -in 'TABLE', 'VIEW', 'MATERIALIZED_VIEW') { $script:tables += $item['naturalKey'] }
        }
        $page++
    } while (($page * 200) -lt $r.Json['totalItems'])
}

Read-Objects
if ($Reimport -or $script:objects.Count -eq 0) {
    Write-Int37Step "Discovery del esquema $Schema"
    $discovery = Assert-Int37Status (Invoke-Int37Http -Method POST -Url "$DiscoveryUrl/api/discovery" -Body @{
        engine = 'postgresql'
        connection = @{ host = $DbHost; port = $DbPort; database = $Database; username = 'nexora_sintetico_reader'
            password = (Get-Int37Secret 'NEXORA_SINTETICO_READER_PASSWORD' @($store)); sslMode = 'disable' }
        schemas = @($Schema) }) 200 'Discovery'
    $envelope = '{"contractVersion":"discovery-api/0.1","discoveredAt":"' +
        (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') + '","requestedSchemas":["' + $Schema +
        '"],"payload":' + $discovery.Text + '}'
    $snap = Assert-Int37Status (Invoke-Int37Http -Method POST -Url "$Api/$dsId/snapshots" -Token $steward -RawBody $envelope) 201 'import snapshot'
    Write-Int37Ok "importado (snapshot $($snap.Json['id']))"
    Read-Objects
} else {
    Write-Int37Ok 'metadatos ya importados (use -Reimport para volver a importarlos)'
}
$unexpected = @($script:tables | Where-Object { $ExpectedTables -notcontains $_ })
$missing = @($ExpectedTables | Where-Object { $script:tables -notcontains $_ })
if ($unexpected.Count -gt 0 -or $missing.Count -gt 0) {
    throw "Imported relations differ from the expected two (unexpected: $($unexpected -join ', '); missing: $($missing -join ', '))"
}
Write-Int37Ok "relaciones importadas: $($script:tables -join ', ')"

# ------------------------------------------------------------------------------------------------ definitions

function Get-Definitions {
    $map = @{}
    $r = Assert-Int37Status (Invoke-Int37Http -Url "$Api/$dsId/definitions?size=200" -Token $editor) 200 'definitions'
    foreach ($d in @($r.Json['items'])) { $map[$d['key']] = $d }
    return $map
}

function Ensure-Approved([hashtable]$Body) {
    $key = $Body['key']
    $existing = (Get-Definitions)[$key]
    if (-not $existing) {
        $existing = (Assert-Int37Status (Invoke-Int37Http -Method POST -Url "$Api/$dsId/definitions" -Token $editor -Body $Body) 201 "create $key").Json
        Write-Int37Ok "$key creada por demo.editor"
    }
    switch ($existing['status']) {
        'APPROVED' { Write-Int37Ok "$key aprobada" }
        'DRAFT' {
            $etag = '"' + $existing['version'] + '"'
            $existing = (Assert-Int37Status (Invoke-Int37Http -Method POST -Url "$Api/$dsId/definitions/$($existing['id'])/approve" `
                -Token $approver -Headers @{ 'If-Match' = $etag }) 200 "approve $key").Json
            Write-Int37Ok "$key aprobada por demo.approver"
        }
        default { throw "definition $key is $($existing['status']); fix it in Semantic before continuing" }
    }
    return "$($existing['id'])"
}

Write-Int37Step 'Catálogo (7 definiciones)'
$ids = [ordered]@{}
$ids['pozo'] = Ensure-Approved @{ kind = 'CONCEPT'; key = 'pozo'; name = 'Pozo'
    description = 'Un pozo registrado en Wellnode (tabla wm_well).'; concept = @{ relationId = (Obj 'wm_well') } }
$ids['numero_pozos'] = Ensure-Approved @{ kind = 'METRIC'; key = 'numero_pozos'; name = 'Número de pozos'
    description = 'Cantidad de pozos registrados.'; metric = @{ conceptId = $ids['pozo']; aggregation = 'COUNT' } }
$ids['campo'] = Ensure-Approved @{ kind = 'DIMENSION'; key = 'campo'; name = 'Campo'
    description = 'Campo petrolero al que pertenece el pozo.'
    dimension = @{ conceptId = $ids['pozo']; columnId = (Obj 'wm_well' 'field'); type = 'CATEGORICAL' } }
$ids['estatus_pozo'] = Ensure-Approved @{ kind = 'DIMENSION'; key = 'estatus_pozo'; name = 'Estatus del pozo'
    description = 'Estatus operativo del pozo (activo, en perforación, abandonado…, según los valores reales).'
    dimension = @{ conceptId = $ids['pozo']; columnId = (Obj 'wm_well' 'statuswell'); type = 'CATEGORICAL' } }
$ids['registro_actividad'] = Ensure-Approved @{ kind = 'CONCEPT'; key = 'registro_actividad'; name = 'Registro de actividad'
    description = 'Cada actividad anotada en el registro electrónico de operaciones de un pozo.'
    concept = @{ relationId = (Obj 'bkw_electronic_logs') } }
$ids['numero_actividades'] = Ensure-Approved @{ kind = 'METRIC'; key = 'numero_actividades'; name = 'Número de actividades registradas'
    description = 'Cantidad de registros del registro electrónico.'
    metric = @{ conceptId = $ids['registro_actividad']; aggregation = 'COUNT' } }
$ids['fecha_actividad'] = Ensure-Approved @{ kind = 'DIMENSION'; key = 'fecha_actividad'; name = 'Fecha de la actividad'
    description = 'Fecha y hora de inicio de la actividad.'
    dimension = @{ conceptId = $ids['registro_actividad']; columnId = (Obj 'bkw_electronic_logs' 'start_date'); type = 'TEMPORAL'
        granularities = @('DAY', 'WEEK', 'MONTH') } }

Write-Int37Step 'Comprobación del catálogo (V4)'
$final = Get-Definitions
$expectedTypes = @{ campo = 'TEXT'; estatus_pozo = 'TEXT'; fecha_actividad = 'TIMESTAMP' }
$failures = 0
foreach ($k in $ids.Keys) {
    $d = $final[$k]
    $ok = $d -and $d['status'] -eq 'APPROVED' -and $d['usable'] -eq $true -and "$($d['id'])" -eq $ids[$k]
    $type = ''
    if ($ok -and $expectedTypes.ContainsKey($k)) {
        $type = "$($d['dimension']['valueType'])"
        $ok = $type -eq $expectedTypes[$k]
    }
    if ($ok) { Write-Int37Ok "$k APPROVED, usable $type" } else { Write-Int37Fail "$k no está lista ($($d['status']), usable=$($d['usable']) $type)"; $failures++ }
}
foreach ($k in $final.Keys) { if (-not $ids.Contains($k)) { Write-Int37Warn "definición adicional en la fuente: $k ($($final[$k]['status']))" } }

$state = Read-Int37State
$state['tenantId'] = $tenant
$state['dataSourceId'] = $dsId
$state['dataSourceKey'] = $DataSourceKey
$state['alias'] = $Alias
$state['database'] = @{ host = $DbHost; port = $DbPort; name = $Database; schema = $Schema; user = 'nexora_sintetico_reader' }
$state['definitions'] = $ids
Write-Int37State $state
Write-Int37Ok "estado guardado en $(Get-Int37StateFile)"
if ($failures -gt 0) { exit 1 }
