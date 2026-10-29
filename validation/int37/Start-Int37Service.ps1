#Requires -Version 7.0
<#
.SYNOPSIS
  INT-3.7: starts one service of the local demo, in the foreground, with its configuration and secrets.

.DESCRIPTION
  One terminal per service, in this order: Semantic, Discovery, Reports, Intelligence (README, "Orden de arranque").
  Secrets are put only in the environment of THIS process (removed when the service stops); nothing secret is
  written to disk or printed. Non-secret identifiers (tenant, data source) come from the local state file written by
  Invoke-Int37Catalog.ps1.

    Semantic      8080  profile dev; DB passwords from Nexora-Semantic/docker/.env
    Discovery     8070  only 127.0.0.1/32 and port 5434 allowed (command-line arguments; no configuration change)
    Reports       8090  service account nexora-reports-dev (secret from Semantic's .env); one enterprise source:
                        (tenant, data source) -> 127.0.0.1:5434/nexora_demo_sintetico as nexora_sintetico_reader
    Intelligence  8100  profiles ollama; generated application-int37.yml (no secrets) in the local INT-3.7 folder;
                        client secret of nexora-intelligence from the secret store

  -PrintConfig shows the non-secret configuration and exits.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Semantic', 'Discovery', 'Reports', 'Intelligence')][string]$Service,
    [string]$Jar,
    [switch]$PrintConfig,
    [string]$KeycloakUrl = 'http://localhost:8180',
    [string]$Realm = 'nexora',
    [string]$SemanticUrl = 'http://localhost:8080',
    [string]$ReportsUrl = 'http://localhost:8090',
    [string]$SourceZone = 'America/Mexico_City',
    [string]$Root = (Join-Path $PSScriptRoot '../../..'),
    [string]$SemanticEnvFile = (Join-Path $PSScriptRoot '../../../Nexora-Semantic/docker/.env'),
    [string]$ReportsEnvFile = (Join-Path $PSScriptRoot '../../../Nexora-Reports/docker/.env')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Int37Common.psm1') -Force

$Issuer = "$KeycloakUrl/realms/$Realm"
$semanticEnv = Read-Int37DotEnv $SemanticEnvFile
$reportsEnv = Read-Int37DotEnv $ReportsEnvFile
$store = Read-Int37SecretStore
$state = Read-Int37State

$defaults = @{
    Semantic = @{ Port = 8080; Jar = 'Nexora-Semantic/target/nexora-semantic-0.1.0-SNAPSHOT.jar' }
    Discovery = @{ Port = 8070; Jar = 'Nexora Discovery/target/nexora-discovery-0.1.0-SNAPSHOT.jar' }
    Reports = @{ Port = 8090; Jar = 'Nexora-Reports/target/nexora-reports-0.1.0-SNAPSHOT.jar' }
    Intelligence = @{ Port = 8100; Jar = 'Nexora-Intelligence/target/nexora-intelligence-0.1.0-SNAPSHOT.jar' }
}
$port = $defaults[$Service].Port
if (-not $Jar) { $Jar = Join-Path $Root $defaults[$Service].Jar }

function Require-State([string]$Key) {
    if (-not $state.ContainsKey($Key) -or -not $state[$Key]) {
        throw "State '$Key' missing: run Invoke-Int37Catalog.ps1 first (README, step V4)."
    }
    return $state[$Key]
}

function Test-PortInUse([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try { return $client.ConnectAsync('127.0.0.1', $Port).Wait(500) } catch { return $false } finally { $client.Dispose() }
}

$envVars = [ordered]@{}      # name -> value (secret values are never printed)
$secretNames = [System.Collections.Generic.List[string]]::new()
$appArgs = [System.Collections.Generic.List[string]]::new()

function Set-Var([string]$Name, [string]$Value, [switch]$Secret) {
    $envVars[$Name] = $Value
    if ($Secret) { $secretNames.Add($Name) }
}

switch ($Service) {
    'Semantic' {
        Set-Var 'SPRING_PROFILES_ACTIVE' 'dev'
        Set-Var 'SEMANTIC_DB_APP_PASSWORD' (Get-Int37Secret 'SEMANTIC_DB_APP_PASSWORD' @($semanticEnv)) -Secret
        Set-Var 'SEMANTIC_DB_OWNER_PASSWORD' (Get-Int37Secret 'SEMANTIC_DB_OWNER_PASSWORD' @($semanticEnv)) -Secret
        if ($semanticEnv['SEMANTIC_DB_APP_USER']) { Set-Var 'SEMANTIC_DB_APP_USER' $semanticEnv['SEMANTIC_DB_APP_USER'] }
        if ($semanticEnv['SEMANTIC_DB_OWNER_USER']) { Set-Var 'SEMANTIC_DB_OWNER_USER' $semanticEnv['SEMANTIC_DB_OWNER_USER'] }
    }
    'Discovery' {
        $appArgs.Add("--server.port=$port")
        $appArgs.Add('--nexora.discovery.api.targets.allowed-networks=127.0.0.1/32')
        $appArgs.Add('--nexora.discovery.api.targets.allowed-ports=5434')
    }
    'Reports' {
        $tenant = Require-State 'tenantId'
        $dataSource = Require-State 'dataSourceId'
        $db = Require-State 'database'
        Set-Var 'REPORTS_PORT' "$port"
        Set-Var 'REPORTS_DB_URL' 'jdbc:postgresql://localhost:5434/nexora_reports?currentSchema=reports'
        Set-Var 'REPORTS_DB_USER' 'nexora_reports_app'
        Set-Var 'REPORTS_DB_PASSWORD' (Get-Int37Secret 'REPORTS_DB_APP_PASSWORD' @($reportsEnv)) -Secret
        Set-Var 'REPORTS_DB_OWNER_USER' $(if ($reportsEnv['REPORTS_DB_OWNER_USER']) { $reportsEnv['REPORTS_DB_OWNER_USER'] } else { 'nexora_reports_owner' })
        Set-Var 'REPORTS_DB_OWNER_PASSWORD' (Get-Int37Secret 'REPORTS_DB_OWNER_PASSWORD' @($reportsEnv)) -Secret
        Set-Var 'REPORTS_OIDC_ISSUER_URI' $Issuer
        Set-Var 'REPORTS_SEMANTIC_BASE_URL' $SemanticUrl
        Set-Var 'REPORTS_SEMANTIC_TOKEN_URI' "$Issuer/protocol/openid-connect/token"
        Set-Var 'NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_0_TENANTID' $tenant
        Set-Var 'NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_0_CLIENTID' 'nexora-reports-dev'
        Set-Var 'NEXORA_REPORTS_SEMANTIC_SERVICEACCOUNTS_0_CLIENTSECRET' (Get-Int37Secret 'KC_REPORTS_CLIENT_SECRET' @($semanticEnv)) -Secret
        Set-Var 'NEXORA_REPORTS_ENTERPRISE_ALLOWEDHOSTS_0' $db['host']
        Set-Var 'NEXORA_REPORTS_ENTERPRISE_ALLOWEDPORTS_0' "$($db['port'])"
        Set-Var 'NEXORA_REPORTS_ENTERPRISE_SOURCES_0_TENANTID' $tenant
        Set-Var 'NEXORA_REPORTS_ENTERPRISE_SOURCES_0_DATASOURCEID' $dataSource
        Set-Var 'NEXORA_REPORTS_ENTERPRISE_SOURCES_0_HOST' $db['host']
        Set-Var 'NEXORA_REPORTS_ENTERPRISE_SOURCES_0_PORT' "$($db['port'])"
        Set-Var 'NEXORA_REPORTS_ENTERPRISE_SOURCES_0_DATABASE' $db['name']
        Set-Var 'NEXORA_REPORTS_ENTERPRISE_SOURCES_0_USERNAME' $db['user']
        Set-Var 'NEXORA_REPORTS_ENTERPRISE_SOURCES_0_PASSWORD' (Get-Int37Secret 'NEXORA_SINTETICO_READER_PASSWORD' @($store)) -Secret
        Set-Var 'NEXORA_REPORTS_ENTERPRISE_SOURCES_0_SSLMODE' 'disable'
    }
    'Intelligence' {
        $tenant = Require-State 'tenantId'
        $dataSource = Require-State 'dataSourceId'
        $alias = Require-State 'alias'
        $config = Join-Path (Initialize-Int37Home) 'application-int37.yml'
        $yaml = @"
# Generated by Start-Int37Service.ps1 (INT-3.7, local demo). NO SECRETS: client secrets come from environment
# variables of the Intelligence process. Regenerated on every start from the local state file.
nexora:
  intelligence:
    semantic:
      base-url: $SemanticUrl
      identity:
        tenant-id: $tenant
        token-uri: $Issuer/protocol/openid-connect/token
        jwk-set-uri: $Issuer/protocol/openid-connect/certs
        issuer: $Issuer
        audience: nexora-semantic
        client-id: nexora-intelligence
        client-secret: `${NEXORA_INTELLIGENCE_SEMANTIC_CLIENT_SECRET}
    reports:
      base-url: $ReportsUrl
      read-timeout: 90s
      max-rows: 1000
      max-retries: 0
      identity:
        tenant-id: $tenant
        token-uri: $Issuer/protocol/openid-connect/token
        jwk-set-uri: $Issuer/protocol/openid-connect/certs
        issuer: $Issuer
        audience: nexora-reports
        required-role: REPORT_RUNNER
        client-id: nexora-intelligence
        client-secret: `${NEXORA_INTELLIGENCE_REPORTS_CLIENT_SECRET}
    planning:
      default-zone: UTC
      source-zones:
        "[$dataSource]": $SourceZone
    api:
      enabled: true
      max-concurrent-questions: 2
      data-sources:
        ${alias}:
          tenant-id: $tenant
          data-source-id: $dataSource
"@
        Set-Content -LiteralPath $config -Value $yaml -Encoding utf8
        $secret = Get-Int37Secret 'NEXORA_INTELLIGENCE_CLIENT_SECRET' @($store)
        Set-Var 'NEXORA_INTELLIGENCE_SEMANTIC_CLIENT_SECRET' $secret -Secret
        Set-Var 'NEXORA_INTELLIGENCE_REPORTS_CLIENT_SECRET' $secret -Secret
        Set-Var 'SPRING_PROFILES_ACTIVE' 'ollama'
        $appArgs.Add('--spring.config.additional-location=file:' + ($config -replace '\\', '/'))
    }
}

Write-Int37Step "$Service (puerto $port)"
Write-Host "  jar: $Jar"
foreach ($k in $envVars.Keys) {
    $shown = if ($secretNames -contains $k) { '<secreto>' } else { $envVars[$k] }
    Write-Host "  $k=$shown"
}
if ($appArgs.Count -gt 0) { Write-Host "  argumentos: $($appArgs -join ' ')" }
if ($PrintConfig) { return }

if (-not (Test-Path $Jar)) { throw "Jar not found: $Jar (build it with: mvn -DskipTests package)" }
if (Test-PortInUse $port) { throw "Port $port is already in use" }

$previous = @{}
foreach ($k in $envVars.Keys) {
    $previous[$k] = [Environment]::GetEnvironmentVariable($k)
    [Environment]::SetEnvironmentVariable($k, $envVars[$k])
}
try {
    & java -jar $Jar @appArgs
} finally {
    foreach ($k in $envVars.Keys) { [Environment]::SetEnvironmentVariable($k, $previous[$k]) }
}
