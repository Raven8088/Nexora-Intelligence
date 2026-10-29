#Requires -Version 7.0
<#
.SYNOPSIS
  INT-3.7: creates or reloads the SYNTHETIC demo database (datos/01-crear-sintetico.sql). REQUIRES AUTHORIZATION.

.DESCRIPTION
  Runs the script inside the PostgreSQL container of Reports' development environment (nexora-reports-postgres,
  127.0.0.1:5434). It creates (if missing) the database nexora_demo_sintetico, schema sintetico, the tables
  wm_well and bkw_electronic_logs and the read-only role nexora_sintetico_reader, and reloads exactly the same
  fictitious rows. It touches no other database, schema or role.

  The reader password comes from the INT-3.7 secret store (NEXORA_SINTETICO_READER_PASSWORD) and is handed to
  docker as an environment variable of this process only: it is never on the command line, in a file or printed.
  Supports -WhatIf.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Container = 'nexora-reports-postgres',
    [string]$DbUser = 'nexora_reports_owner'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Int37Common.psm1') -Force

$sql = Join-Path $PSScriptRoot 'datos/01-crear-sintetico.sql'
if (-not (Test-Path $sql)) { throw "Missing $sql" }
$password = Get-Int37Secret 'NEXORA_SINTETICO_READER_PASSWORD' @(Read-Int37SecretStore)

$running = & docker inspect -f '{{.State.Running}}' $Container 2>$null
if ($running -ne 'true') { throw "Container $Container is not running (docker compose -f Nexora-Reports/docker/docker-compose.yml up -d)" }

if ($PSCmdlet.ShouldProcess("$Container / nexora_demo_sintetico", 'crear o recargar los datos sintéticos')) {
    $env:NEXORA_SINTETICO_READER_PASSWORD = $password
    try {
        $output = Get-Content -Raw -LiteralPath $sql |
            & docker exec -e NEXORA_SINTETICO_READER_PASSWORD -i $Container psql -U $DbUser -d postgres -v ON_ERROR_STOP=1 -q -f - 2>&1
        $code = $LASTEXITCODE
    } finally {
        Remove-Item Env:\NEXORA_SINTETICO_READER_PASSWORD -ErrorAction SilentlyContinue
    }
    $output | ForEach-Object { "  $_" }
    if ($code -ne 0) { throw "psql failed (exit $code)" }
    Write-Int37Ok 'Base sintética creada o recargada. Las columnas ins/upd/del/trn deben ser f; pozos 20, registros 33.'
}
