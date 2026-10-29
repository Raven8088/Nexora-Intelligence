#Requires -Version 7.0
<#
.SYNOPSIS
  INT-3.7: end-to-end checks of the local demo against the expected results (datos/esperado.json).

.DESCRIPTION
  -Reference  (V3)  Runs datos/02-referencia.sql in a READ ONLY transaction on the synthetic database and compares
                    it with esperado.json.
  -Manual     (V5)  Without Ollama or the question API: with the nexora-intelligence service token, creates the
                    logical plans of P1, P2 and P3 in Semantic (approved definitions, P3 with the half-open period
                    [2026-10-01T00:00:00, 2026-10-08T00:00:00) and DAY granularity) and executes them in Reports.
  -Api        (V6)  Asks P1, P2 and P3 to POST /api/v1/questions (alias of the state file) and compares the
                    rows; also two negative checks (unknown alias, malformed body).
  -Repeat <n> (V7)  Asks every question n times and reports plan fingerprints, statuses and latencies.

  A Markdown + JSON report is written to the local INT-3.7 folder (out\). Tokens and secrets are never printed
  or written. Exit code 1 when a check fails.
#>
[CmdletBinding()]
param(
    [switch]$Reference,
    [switch]$Manual,
    [switch]$Api,
    [int]$Repeat = 0,
    [string]$KeycloakUrl = 'http://localhost:8180',
    [string]$Realm = 'nexora',
    [string]$SemanticUrl = 'http://localhost:8080',
    [string]$ReportsUrl = 'http://localhost:8090',
    [string]$IntelligenceUrl = 'http://localhost:8100',
    [string]$Container = 'nexora-reports-postgres',
    [string]$DbUser = 'nexora_reports_owner'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Import-Module (Join-Path $PSScriptRoot 'Int37Common.psm1') -Force

if (-not ($Reference -or $Manual -or $Api -or $Repeat -gt 0)) { throw 'Choose -Reference, -Manual, -Api and/or -Repeat <n>' }
$expected = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'datos/esperado.json') | ConvertFrom-Json -AsHashtable
$state = Read-Int37State
$store = Read-Int37SecretStore
$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
$results = [System.Collections.Generic.List[object]]::new()

function Add-Result([string]$Id, [string]$Check, [bool]$Pass, [string]$Detail) {
    $results.Add([pscustomobject]@{ Id = $Id; Check = $Check; Result = $(if ($Pass) { 'PASS' } else { 'FAIL' }); Detail = $Detail })
    if ($Pass) { Write-Int37Ok "$Id $Check :: $Detail" } else { Write-Int37Fail "$Id $Check :: $Detail" }
}

function Require-State([string]$Key) {
    if (-not $state.ContainsKey($Key) -or -not $state[$Key]) { throw "State '$Key' missing: run Invoke-Int37Catalog.ps1 first." }
    return $state[$Key]
}

# ------------------------------------------------------------------------------------------------ V3 reference

if ($Reference) {
    Write-Int37Step 'V3 · SQL de referencia (solo lectura) frente a esperado.json'
    $sql = Join-Path $PSScriptRoot 'datos/02-referencia.sql'
    $out = Get-Content -Raw -LiteralPath $sql | & docker exec -i $Container psql -U $DbUser -d nexora_demo_sintetico -v ON_ERROR_STOP=1 -q -tA -f - 2>&1
    if ($LASTEXITCODE -ne 0) { Add-Result 'V3' 'SQL de referencia' $false "psql exit $LASTEXITCODE" }
    else {
        $line = @($out | ForEach-Object { "$_" } | Where-Object { $_.TrimStart().StartsWith('{') }) | Select-Object -First 1
        $json = $line | ConvertFrom-Json -AsHashtable
        foreach ($q in 'P1', 'P2', 'P3') {
            $cols = @($expected[$q]['columns'] | ForEach-Object { @{ key = $_ } })
            $diff = Compare-Int37Rows $cols $json[$q] $expected[$q]
            Add-Result "V3.$q" 'referencia = esperado' ($diff -eq '') $(if ($diff) { $diff } else { "$(@($json[$q]).Count) filas" })
        }
    }
}

# ------------------------------------------------------------------------------------------------ V5 manual

if ($Manual) {
    Write-Int37Step 'V5 · Plan manual en Semantic + ejecución en Reports (sin Ollama)'
    $dsId = Require-State 'dataSourceId'
    $defs = Require-State 'definitions'
    $token = Get-Int37ClientToken $KeycloakUrl $Realm 'nexora-intelligence' (Get-Int37Secret 'NEXORA_INTELLIGENCE_CLIENT_SECRET' @($store))
    Write-Host "  nexora-intelligence: $(Format-Int37Claims (ConvertFrom-Int37Jwt $token))"
    $plans = [ordered]@{
        P1 = @{ metrics = @($defs['numero_pozos']); dimensions = @($defs['campo']); filters = @(); preferredRelationships = @(); timeGranularities = @() }
        P2 = @{ metrics = @($defs['numero_pozos']); dimensions = @($defs['estatus_pozo']); filters = @(); preferredRelationships = @(); timeGranularities = @() }
        P3 = @{ metrics = @($defs['numero_actividades']); dimensions = @($defs['fecha_actividad'])
            filters = @(@{ definitionId = $defs['fecha_actividad']; operator = 'GTE'; value = '2026-10-01T00:00:00' },
                        @{ definitionId = $defs['fecha_actividad']; operator = 'LT'; value = '2026-10-08T00:00:00' })
            preferredRelationships = @(); timeGranularities = @(@{ dimensionId = $defs['fecha_actividad']; granularity = 'DAY' }) }
    }
    foreach ($q in $plans.Keys) {
        try {
            $plan = Assert-Int37Status (Invoke-Int37Http -Method POST -Url "$SemanticUrl/api/v1/data-sources/$dsId/logical-plans" -Token $token -Body $plans[$q]) 201 "plan $q"
            $planId = $plan.Json['id']
            $exec = Assert-Int37Status (Invoke-Int37Http -Method POST -Url "$ReportsUrl/api/v1/data-sources/$dsId/plans/$planId/executions" -Token $token -Body @{ maxRows = 1000 }) 200 "execution $q"
            $diff = Compare-Int37Rows $exec.Json['columns'] $exec.Json['rows'] $expected[$q]
            $truncated = $exec.Json['truncated']
            Add-Result "V5.$q" 'plan + ejecución = esperado' ($diff -eq '' -and -not $truncated) `
                $(if ($diff) { $diff } else { "plan=$planId fingerprint=$($plan.Json['fingerprint']) filas=$($exec.Json['rowCount']) $($exec.Millis) ms" })
        } catch {
            Add-Result "V5.$q" 'plan + ejecución = esperado' $false $_.Exception.Message
        }
    }
}

# ------------------------------------------------------------------------------------------------ V6/V7 API

function Ask([string]$Question, [string]$Alias) {
    $body = @{ question = $Question; language = 'es'; context = @{ dataSource = $Alias } }
    return Invoke-Int37Http -Method POST -Url "$IntelligenceUrl/api/v1/questions" -Body $body -TimeoutSec 240
}

if ($Api) {
    Write-Int37Step 'V6 · Preguntas por la API de Intelligence'
    $alias = Require-State 'alias'
    foreach ($q in 'P1', 'P2', 'P3') {
        $r = Ask $expected[$q]['question'] $alias
        $rid = Get-Int37HeaderValue $r 'X-Request-Id'
        if ($r.Status -ne 200) {
            $code = if ($r.Json -is [System.Collections.IDictionary] -and $r.Json.Contains('code')) { $r.Json['code'] } else { '' }
            Add-Result "V6.$q" 'pregunta = esperado' $false "HTTP $($r.Status) $code requestId=$rid"
            continue
        }
        $status = $r.Json['status']
        if ($status -ne 'REPORTED') {
            Add-Result "V6.$q" 'pregunta = esperado' $false "status=$status requestId=$rid"
            continue
        }
        $diff = Compare-Int37Rows $r.Json['result']['columns'] $r.Json['result']['rows'] $expected[$q]
        Add-Result "V6.$q" 'pregunta = esperado' ($diff -eq '' -and -not $r.Json['result']['truncated']) `
            $(if ($diff) { "$diff requestId=$rid" } else { "fingerprint=$($r.Json['plan']['fingerprint']) $($r.Millis) ms requestId=$rid" })
    }
    $neg = Ask $expected['P1']['question'] 'alias-inexistente'
    $negCode = if ($neg.Json -is [System.Collections.IDictionary]) { $neg.Json['code'] } else { '' }
    Add-Result 'V6.N1' 'alias desconocido -> 400 validation_failed' ($neg.Status -eq 400 -and $negCode -eq 'validation_failed') "HTTP $($neg.Status) $negCode"
    $bad = Invoke-Int37Http -Method POST -Url "$IntelligenceUrl/api/v1/questions" -RawBody '{"question":' -TimeoutSec 30
    $badCode = if ($bad.Json -is [System.Collections.IDictionary]) { $bad.Json['code'] } else { '' }
    Add-Result 'V6.N2' 'JSON mal formado -> 400 malformed_request' ($bad.Status -eq 400 -and $badCode -eq 'malformed_request') "HTTP $($bad.Status) $badCode"
}

if ($Repeat -gt 0) {
    Write-Int37Step "V7 · Repetición ($Repeat veces por pregunta)"
    $alias = Require-State 'alias'
    foreach ($q in 'P1', 'P2', 'P3') {
        $prints = @{}; $statuses = @{}; $times = @(); $ok = 0
        for ($i = 1; $i -le $Repeat; $i++) {
            $r = Ask $expected[$q]['question'] $alias
            $times += $r.Millis
            $s = if ($r.Status -eq 200) { $r.Json['status'] } else { "HTTP $($r.Status)" }
            $statuses[$s] = 1 + $(if ($statuses.ContainsKey($s)) { $statuses[$s] } else { 0 })
            if ($s -eq 'REPORTED') {
                $fp = $r.Json['plan']['fingerprint']
                $prints[$fp] = 1 + $(if ($prints.ContainsKey($fp)) { $prints[$fp] } else { 0 })
                if ((Compare-Int37Rows $r.Json['result']['columns'] $r.Json['result']['rows'] $expected[$q]) -eq '') { $ok++ }
            }
        }
        $avg = [int](($times | Measure-Object -Average).Average)
        $max = ($times | Measure-Object -Maximum).Maximum
        $statusText = ($statuses.Keys | ForEach-Object { "$_=$($statuses[$_])" }) -join ','
        Add-Result "V7.$q" "$Repeat repeticiones: filas correctas en todas" ($ok -eq $Repeat) `
            "correctas=$ok/$Repeat estados=[$statusText] huellas distintas=$($prints.Count) media=$avg ms máx=$max ms"
    }
}

# ------------------------------------------------------------------------------------------------ report

$outDir = Join-Path (Initialize-Int37Home) 'out'
$md = [System.Collections.Generic.List[string]]::new()
$md.Add("# INT-3.7 — verificación $runId (DATOS SINTÉTICOS)")
$md.Add('')
$md.Add("- Fuente: $($state['dataSourceKey']) ($($state['dataSourceId'])) · tenant $($state['tenantId'])")
$md.Add("- Resultado: $(@($results | Where-Object Result -eq 'PASS').Count) PASS, $(@($results | Where-Object Result -eq 'FAIL').Count) FAIL")
$md.Add('')
$md.Add('| Id | Comprobación | Resultado | Detalle |')
$md.Add('|---|---|---|---|')
foreach ($r in $results) { $md.Add("| $($r.Id) | $($r.Check) | $($r.Result) | $($r.Detail -replace '\|', '/') |") }
$md | Set-Content -LiteralPath (Join-Path $outDir "int37-$runId.md") -Encoding utf8
$results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outDir "int37-$runId.json") -Encoding utf8
Write-Int37Step "Informe: $(Join-Path $outDir "int37-$runId.md")"
if (@($results | Where-Object Result -eq 'FAIL').Count -gt 0) { exit 1 }
