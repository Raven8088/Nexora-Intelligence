#Requires -Version 7.0
<#
.SYNOPSIS
  Automated tests of the in-place credential rotation (Int37Rotation.psm1): preconditions, success, a failure at
  every stage (Keycloak, verification, .env, journal), failed rollback, simulated process death at every
  checkpoint (including in the middle of the .env write) and -Recover. Offline: Keycloak is an in-memory fake and
  the .env and journal are temporary files; nothing is sent to any server.

.EXAMPLE
  pwsh -NoProfile -File ./tests/Int37Rotation.Tests.ps1      # exit code 0 = all pass
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '../Int37Common.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '../Int37Rotation.psm1') -Force

$script:failures = 0; $script:passed = 0
function Check([string]$Name, [bool]$Condition, [string]$Detail = '') {
    if ($Condition) { $script:passed++; Write-Host "[PASS] $Name" -ForegroundColor Green }
    else { $script:failures++; Write-Host "[FAIL] $Name $Detail" -ForegroundColor Red }
}

$Users = @('platform.admin', 'steward.demo', 'viewer.demo', 'auditor.demo', 'steward.other')
$Old = @{ KC_DEV_USER_PASSWORD = 'OldDevPassword-0000000000000001'; KC_REPORTS_CLIENT_SECRET = 'OldClientSecret-00000000000002'
    KC_BOOTSTRAP_ADMIN_PASSWORD = 'OldAdmin-01' }
$New = @{ KC_DEV_USER_PASSWORD = 'NewDevPassword-1111111111111111'; KC_REPORTS_CLIENT_SECRET = 'NewClientSecret-11111111111112'
    KC_BOOTSTRAP_ADMIN_PASSWORD = 'NewAdminPassword-111111111111113' }
$AllSecrets = @($Old.Values) + @($New.Values)
$Work = Join-Path ([IO.Path]::GetTempPath()) "int37-rotation-tests-$PID"
New-Item -ItemType Directory -Force -Path $Work | Out-Null

<#
  In-memory Keycloak. Faults (by name) make an operation fail AFTER it changed the state (worst case) or make a
  login fail. A token session stays usable after a password change (as in Keycloak).
#>
function New-World([hashtable]$Faults = @{}, [string]$CrashAt = '', [switch]$AdminInPlan) {
    $kc = @{ Admin = $Old.KC_BOOTSTRAP_ADMIN_PASSWORD; Client = $Old.KC_REPORTS_CLIENT_SECRET; Users = @{}; Down = $false
        Faults = $Faults; CrashAt = $CrashAt; Calls = [System.Collections.Generic.List[string]]::new() }
    foreach ($u in $Users) { $kc.Users[$u] = $Old.KC_DEV_USER_PASSWORD }
    $envFile = Join-Path $Work "$([guid]::NewGuid()).env"
    Set-Content -LiteralPath $envFile -Value @('# fichero de prueba', 'POSTGRES_PASSWORD=NoSeToca-12345678901234567',
        "KC_BOOTSTRAP_ADMIN_PASSWORD=$($Old.KC_BOOTSTRAP_ADMIN_PASSWORD)", "KC_DEV_USER_PASSWORD=$($Old.KC_DEV_USER_PASSWORD)",
        "KC_REPORTS_CLIENT_SECRET=$($Old.KC_REPORTS_CLIENT_SECRET)", 'KC_TENANT_DEMO_ID=00000000-0000-4000-8000-000000000001')
    $journal = Join-Path $Work "$([guid]::NewGuid()).journal"
    $fault = { param([string]$Name) $kc.Faults.ContainsKey($Name) -and $kc.Faults[$Name] }.GetNewClosure()
    $ops = @{
        TestAdmin = { param($p) $kc.Calls.Add('TestAdmin'); (-not $kc.Down) -and $p -ceq $kc.Admin -and -not ($p -ceq $New.KC_BOOTSTRAP_ADMIN_PASSWORD -and (& $fault 'LoginNewAdmin')) }.GetNewClosure()
        TestUser = { param($u, $p) (-not $kc.Down) -and $kc.Users[$u] -ceq $p -and -not ($p -ceq $New.KC_DEV_USER_PASSWORD -and (& $fault "LoginNew:$u")) }.GetNewClosure()
        TestClient = { param($c, $p) (-not $kc.Down) -and $kc.Client -ceq $p -and -not ($p -ceq $New.KC_REPORTS_CLIENT_SECRET -and (& $fault 'LoginNewClient')) }.GetNewClosure()
        OpenAdmin = { param($p) if ($kc.Down -or $p -cne $kc.Admin) { throw 'Keycloak admin token for admin -> HTTP 401 (expected 200)' }; @{ Ok = $true } }.GetNewClosure()
        SetUserPassword = { param($s, $u, $p)
            if ($kc.Down) { throw 'no connection' }
            $kc.Users[$u] = $p; $kc.Calls.Add("SetUser:$u")
            if ((& $fault "SetUser:$u") -and $p -ceq $New.KC_DEV_USER_PASSWORD) { throw "password of $u -> HTTP 500 (expected 204)" } }.GetNewClosure()
        SetClientSecret = { param($s, $c, $p)
            if ($kc.Down) { throw 'no connection' }
            if (& $fault 'SetClientNoApply') { throw "secret of $c -> HTTP 500 (expected 204)" }
            $kc.Client = $p; $kc.Calls.Add('SetClient')
            if ((& $fault 'SetClient') -and $p -ceq $New.KC_REPORTS_CLIENT_SECRET) { throw "secret of $c -> HTTP 500 (expected 204)" } }.GetNewClosure()
        SetAdminPassword = { param($s, $p)
            if ($kc.Down) { throw 'no connection' }
            $kc.Admin = $p; $kc.Calls.Add('SetAdmin')
            if ((& $fault 'SetAdmin') -and $p -ceq $New.KC_BOOTSTRAP_ADMIN_PASSWORD) { throw 'password of the master admin -> HTTP 500 (expected 204)' } }.GetNewClosure()
        WriteEnv = { param($Path, $Values)
            if ((& $fault 'WriteEnvError') -and $Values['KC_DEV_USER_PASSWORD'] -ceq $New.KC_DEV_USER_PASSWORD) { throw 'disk full' }
            if ((& $fault 'WriteEnvCorrupt') -and $Values['KC_DEV_USER_PASSWORD'] -ceq $New.KC_DEV_USER_PASSWORD) {
                Set-Int37DotEnvValues $Path @{ KC_DEV_USER_PASSWORD = 'basura-escrita-a-medias' }; return }
            if ($kc.CrashAt -eq 'env-midwrite' -and $Values['KC_DEV_USER_PASSWORD'] -ceq $New.KC_DEV_USER_PASSWORD) {
                Set-Content -LiteralPath "$Path.int37tmp" -Value 'KC_DEV_USER_PASSWORD=a-medias'
                throw (New-Int37SimulatedCrash 'process died while writing the .env') }
            Set-Int37DotEnvValues $Path $Values }.GetNewClosure()
        WriteJournal = { param($Path, $Values)
            if ((& $fault 'JournalFirst') -and $Values['STATE'] -eq 'PREPARED') { throw 'cannot write journal' }
            Set-Content -LiteralPath $Path -Value @($Values.Keys | Sort-Object | ForEach-Object { "$_=$($Values[$_])" }) }.GetNewClosure()
        Checkpoint = { param($Step)
            if ($kc.CrashAt -eq $Step) { throw (New-Int37SimulatedCrash "process died after $Step") }
            if ((& $fault 'DownAfterClientFailure') -and $Step -eq 'client:nexora-reports-dev') { } }.GetNewClosure()
        Log = { param($m) }
    }
    $plan = New-Int37RotationPlan -Users $Users -Client 'nexora-reports-dev' -Admin ([bool]$AdminInPlan)
    return @{ Kc = $kc; Env = $envFile; Journal = $journal; Ops = $ops; Plan = $plan }
}

function Invoke-Rotation($w) {
    $current = @{}; $next = @{}   # (PowerShell variables are case-insensitive: not $old/$new)
    foreach ($k in $w.Plan.Keys) { $current[$k] = $Old[$k]; $next[$k] = $New[$k] }
    return Invoke-Int37Rotation -Plan $w.Plan -Old $current -New $next -AdminPassword $Old.KC_BOOTSTRAP_ADMIN_PASSWORD `
        -EnvFile $w.Env -JournalFile $w.Journal -Ops $w.Ops
}

<# Keycloak and .env agree, every credential authenticates, and the journal (if any) holds no secret. #>
function Test-Consistent($w, [string]$Expect) {
    $env = Read-Int37DotEnv $w.Env
    $want = if ($Expect -eq 'NEW') { $New } else { $Old }
    $adminWant = if ($Expect -eq 'NEW' -and $w.Plan.Admin) { $New.KC_BOOTSTRAP_ADMIN_PASSWORD } else { $Old.KC_BOOTSTRAP_ADMIN_PASSWORD }
    $ok = ($env['KC_DEV_USER_PASSWORD'] -ceq $want.KC_DEV_USER_PASSWORD) -and ($env['KC_REPORTS_CLIENT_SECRET'] -ceq $want.KC_REPORTS_CLIENT_SECRET) -and
        ($env['KC_BOOTSTRAP_ADMIN_PASSWORD'] -ceq $adminWant) -and ($w.Kc.Admin -ceq $adminWant) -and ($w.Kc.Client -ceq $want.KC_REPORTS_CLIENT_SECRET) -and
        (@($Users | Where-Object { $w.Kc.Users[$_] -cne $want.KC_DEV_USER_PASSWORD }).Count -eq 0) -and
        ($env['POSTGRES_PASSWORD'] -eq 'NoSeToca-12345678901234567') -and ($env['KC_TENANT_DEMO_ID'] -eq '00000000-0000-4000-8000-000000000001')
    if (Test-Path $w.Journal) {
        $text = Get-Content -Raw -LiteralPath $w.Journal
        $ok = $ok -and -not (Test-Int37RotationPending $w.Journal) -and -not ($AllSecrets | Where-Object { $text.Contains($_) })
    }
    return [bool]$ok
}

function Get-Error([scriptblock]$Action) { try { & $Action | Out-Null; return '' } catch { return $_.Exception.Message } }
function Test-NoSecret([string]$Text) { return -not ($AllSecrets | Where-Object { $Text.Contains($_) }) }

# ------------------------------------------------------------------------------------------------ success
$w = New-World -AdminInPlan
$r = Invoke-Rotation $w
Check 'éxito con administrador: Keycloak y .env con valores nuevos, diario COMPLETED sin secretos' ($r.State -eq 'COMPLETED' -and (Test-Consistent $w 'NEW'))
$order = @($w.Kc.Calls | Where-Object { $_ -like 'Set*' })
Check 'éxito: orden usuarios -> cliente -> administrador (el último)' ($order[-1] -eq 'SetAdmin' -and $order[-2] -eq 'SetClient' -and $order.Count -eq 7) ($order -join ',')
Check 'éxito: el resto del .env no cambia' ((Get-Content $w.Env)[0] -eq '# fichero de prueba')
$w = New-World
$r = Invoke-Rotation $w
Check 'éxito sin administrador: el administrador no cambia' ($r.State -eq 'COMPLETED' -and (Test-Consistent $w 'NEW') -and $w.Kc.Admin -ceq $Old.KC_BOOTSTRAP_ADMIN_PASSWORD)

# ------------------------------------------------------------------------------------------------ preconditions
$w = New-World -AdminInPlan
$w.Kc.Users['viewer.demo'] = 'cambiada-a-mano'
$e = Get-Error { Invoke-Rotation $w }
Check 'precondición: .env distinto de Keycloak -> no toca nada ni escribe diario' ($e -like 'Precondition: user viewer.demo*' -and -not (Test-Path $w.Journal) -and $w.Kc.Client -ceq $Old.KC_REPORTS_CLIENT_SECRET)
$w = New-World -AdminInPlan
Set-Content -LiteralPath $w.Journal -Value 'STATE=APPLYING'
$e = Get-Error { Invoke-Rotation $w }
Check 'precondición: diario pendiente -> exige -Recover' ($e -like 'A previous rotation did not finish*' -and $w.Kc.Users['platform.admin'] -ceq $Old.KC_DEV_USER_PASSWORD)
$w = New-World -AdminInPlan @{ JournalFirst = $true }
$e = Get-Error { Invoke-Rotation $w }
Check 'fallo al escribir el diario: no se cambia nada' ($e -eq 'cannot write journal' -and $w.Kc.Users['platform.admin'] -ceq $Old.KC_DEV_USER_PASSWORD -and $w.Kc.Admin -ceq $Old.KC_BOOTSTRAP_ADMIN_PASSWORD)

# ------------------------------------------------------------------------------------------------ failure at every stage -> automatic rollback
$stages = [ordered]@{
    'usuario 3 de 5 (cambio aplicado y error)' = @{ 'SetUser:viewer.demo' = $true }
    'verificación de un usuario nuevo' = @{ 'LoginNew:auditor.demo' = $true }
    'secreto del cliente (aplicado y error)' = @{ SetClient = $true }
    'verificación del cliente nuevo' = @{ LoginNewClient = $true }
    'cliente que rechaza siempre el cambio (HTTP 500, nada aplicado)' = @{ SetClientNoApply = $true }
    'administrador (aplicado y error)' = @{ SetAdmin = $true }
    'verificación del administrador nuevo' = @{ LoginNewAdmin = $true }
    'escritura del .env (error)' = @{ WriteEnvError = $true }
    'escritura del .env (contenido incorrecto)' = @{ WriteEnvCorrupt = $true }
}
foreach ($name in $stages.Keys) {
    $w = New-World -AdminInPlan $stages[$name]
    $e = Get-Error { Invoke-Rotation $w }
    $j = Read-Int37RotationJournal $w.Journal
    Check "fallo en $name -> reversión automática completa" ($e -like '*Rolled back*' -and $j['STATE'] -eq 'ROLLED_BACK' -and (Test-Consistent $w 'OLD')) $e
    Check "fallo en $name -> el error no contiene secretos" (Test-NoSecret $e)
}

# ------------------------------------------------------------------------------------------------ rollback also fails -> -Recover
$w = New-World -AdminInPlan @{ SetClient = $true }
$w.Ops.Checkpoint = { param($Step) }
$down = $w.Kc
$w.Ops.SetClientSecret = { param($s, $c, $p) $down.Client = $p; if ($p -ceq $New.KC_REPORTS_CLIENT_SECRET) { $down.Down = $true; throw 'connection reset' } }.GetNewClosure()
$e = Get-Error { Invoke-Rotation $w }
$j = Read-Int37RotationJournal $w.Journal
Check 'Keycloak cae durante el cambio y la reversión: diario ROLLBACK_FAILED con lo necesario' ($e -like '*automatic rollback also failed*run Update-Int37Credentials.ps1 -Recover*' -and $j['STATE'] -eq 'ROLLBACK_FAILED' -and (Test-NoSecret $e))
$w.Kc.Down = $false
$w.Ops.SetClientSecret = { param($s, $c, $p) $down.Client = $p }.GetNewClosure()
$rr = Invoke-Int37RotationRecovery -JournalFile $w.Journal -Ops $w.Ops
Check 'Keycloak vuelve: -Recover deja todo en los valores anteriores y el diario sin secretos' ($rr.Direction -eq 'OLD' -and (Test-Consistent $w 'OLD'))

# ------------------------------------------------------------------------------------------------ process dies at every checkpoint -> -Recover
$crashes = @('prepared') + @($Users | ForEach-Object { "user:$_" }) + @('client:nexora-reports-dev', 'admin', 'applied', 'env-midwrite', 'env')
foreach ($c in $crashes) {
    $w = New-World -AdminInPlan -CrashAt $c
    $e = Get-Error { Invoke-Rotation $w }
    $pending = Test-Int37RotationPending $w.Journal
    $rr = Invoke-Int37RotationRecovery -JournalFile $w.Journal -Ops $w.Ops
    $expect = if ($c -eq 'env') { 'NEW' } else { 'OLD' }
    Check "el proceso muere en '$c' -> diario pendiente y -Recover deja todo en $expect" ($pending -and $rr.Direction -eq $expect -and (Test-Consistent $w $expect)) "$e / $($rr.Direction)"
}
$again = Invoke-Int37RotationRecovery -JournalFile $w.Journal -Ops $w.Ops
Check '-Recover repetido no hace nada' ($again.State -eq 'NOTHING')

# ------------------------------------------------------------------------------------------------ recovery without admin access
$w = New-World -AdminInPlan -CrashAt 'admin'
$null = Get-Error { Invoke-Rotation $w }
$w.Kc.Admin = 'otra-cambiada-fuera-del-proceso'
$e = Get-Error { Invoke-Int37RotationRecovery -JournalFile $w.Journal -Ops $w.Ops }
Check 'sin acceso de administrador con ninguna contraseña conocida -> instrucción de recuperación manual' ($e -like 'No administrative access*manual procedure*' -and (Test-Int37RotationPending $w.Journal) -and (Test-NoSecret $e))

Remove-Item -LiteralPath $Work -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "`n$($script:passed) PASS, $($script:failures) FAIL"
if ($script:failures -gt 0) { exit 1 }
