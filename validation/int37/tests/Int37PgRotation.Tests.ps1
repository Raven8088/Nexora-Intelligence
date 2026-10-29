#Requires -Version 7.0
<#
.SYNOPSIS
  Automated tests of the PostgreSQL rotation (AUT-1b, Int37Rotation.psm1): SCRAM-SHA-256 verifier, preconditions
  (local recovery path, meaningful verification, current values), success, a failure at every stage, failed
  rollback, process death at every checkpoint (including after a role change and before the .env) and -Recover.
  Offline: PostgreSQL is an in-memory fake; the .env and journal are temporary files.

.EXAMPLE
  pwsh -NoProfile -File ./tests/Int37PgRotation.Tests.ps1      # exit code 0 = all pass
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

# ------------------------------------------------------------------------------------------------ SCRAM
# Reference: same salt/iterations computed independently (Python hashlib) — StoredKey equals RFC 7677's example.
$v = ConvertTo-Int37ScramVerifier 'pencil' ([Convert]::FromBase64String('W22ZaJ0SNY7soEsUEjb6gQ==')) 4096
Check 'SCRAM-SHA-256: verificador idéntico a la referencia (RFC 7677)' ($v -ceq 'SCRAM-SHA-256$4096:W22ZaJ0SNY7soEsUEjb6gQ==$WG5d8oPm3OtcPnkdi4Uo7BkeZkBFzpcXkuLmtbsT4qY=:wfPLwcE6nTWhTAmQ7tl2KeoiWGPlZqQxSrmfPwDl2dU=') $v
$a = ConvertTo-Int37ScramVerifier 'Secreto-de-prueba-123'; $b = ConvertTo-Int37ScramVerifier 'Secreto-de-prueba-123'
Check 'SCRAM: sal aleatoria (dos verificadores distintos) y sin la contraseña en claro' ($a -cne $b -and -not $a.Contains('Secreto-de-prueba-123'))
$e = try { ConvertTo-Int37ScramVerifier 'contraseña-ñ' | Out-Null; '' } catch { $_.Exception.Message }
Check 'SCRAM: rechaza caracteres no ASCII' ($e -like 'SCRAM: only printable ASCII*')

# ------------------------------------------------------------------------------------------------ fake world
$Items = @(
    @{ Key = 'POSTGRES_PASSWORD'; Role = 'postgres'; Database = 'nexora_semantic'; Superuser = $true },
    @{ Key = 'SEMANTIC_DB_APP_PASSWORD'; Role = 'nexora_semantic_app'; Database = 'nexora_semantic'; Superuser = $false },
    @{ Key = 'SEMANTIC_DB_OWNER_PASSWORD'; Role = 'nexora_semantic_owner'; Database = 'nexora_semantic'; Superuser = $false })
$Old = @{ POSTGRES_PASSWORD = 'f1x8'; SEMANTIC_DB_APP_PASSWORD = 'f1x8'; SEMANTIC_DB_OWNER_PASSWORD = 'f1x8' }
$New = @{ POSTGRES_PASSWORD = 'NewSuperuser-aaaaaaaaaaaaaaaaaaaa1'; SEMANTIC_DB_APP_PASSWORD = 'NewApp-bbbbbbbbbbbbbbbbbbbbbbbbbbb2'
    SEMANTIC_DB_OWNER_PASSWORD = 'NewOwner-ccccccccccccccccccccccccc3' }
$AllSecrets = @($New.Values)   # the old value is 4 characters: too short to search reliably in texts
$Work = Join-Path ([IO.Path]::GetTempPath()) "int37-pg-tests-$PID"
New-Item -ItemType Directory -Force -Path $Work | Out-Null

function New-World([hashtable]$Faults = @{}, [string]$CrashAt = '') {
    $pg = @{ Roles = @{}; Down = $false; Faults = $Faults; CrashAt = $CrashAt; Order = [System.Collections.Generic.List[string]]::new()
        LocalTrust = $true; VerificationTrusts = $false; Verifiers = [System.Collections.Generic.List[string]]::new() }
    foreach ($i in $Items) { $pg.Roles[$i.Role] = $Old[$i.Key] }
    $envFile = Join-Path $Work "$([guid]::NewGuid()).env"
    Set-Content -LiteralPath $envFile -Value @('# fichero de prueba', "POSTGRES_PASSWORD=$($Old.POSTGRES_PASSWORD)",
        'SEMANTIC_DB_OWNER_USER=nexora_semantic_owner', "SEMANTIC_DB_OWNER_PASSWORD=$($Old.SEMANTIC_DB_OWNER_PASSWORD)",
        'SEMANTIC_DB_APP_USER=nexora_semantic_app', "SEMANTIC_DB_APP_PASSWORD=$($Old.SEMANTIC_DB_APP_PASSWORD)", 'KC_DEV_USER_PASSWORD=no-se-toca')
    $journal = Join-Path $Work "$([guid]::NewGuid()).journal"
    $fault = { param([string]$Name) $pg.Faults.ContainsKey($Name) -and $pg.Faults[$Name] }.GetNewClosure()
    $ops = @{
        TestLocal = { if ($pg.Down -or -not $pg.LocalTrust) { @{ Ok = $false; Detail = 'psql exit 2' } } else { @{ Ok = $true; Detail = 'superuser=on local-trust-rules=1' } } }.GetNewClosure()
        TestLogin = { param($Role, $Db, $Password)
            if ($pg.Down) { return $false }
            if ($pg.VerificationTrusts -eq $true -or $pg.VerificationTrusts -eq $Role) { return $true }
            if ($Password -ceq $New[($Items | Where-Object { $_.Role -eq $Role }).Key] -and (& $fault "LoginNew:$Role")) { return $false }
            return $pg.Roles[$Role] -ceq $Password }.GetNewClosure()
        SetPassword = { param($Role, $Password)
            if ($pg.Down) { throw 'psql exit 2' }
            $isNew = $Password -ceq $New[($Items | Where-Object { $_.Role -eq $Role }).Key]
            if ($isNew -and (& $fault "AlterNoApply:$Role")) { throw "ALTER ROLE $Role failed (psql exit 3)" }
            $pg.Verifiers.Add((ConvertTo-Int37ScramVerifier $Password))   # what the real operation would send
            $pg.Roles[$Role] = $Password; $pg.Order.Add($Role)
            if ($isNew -and (& $fault "Alter:$Role")) { throw "ALTER ROLE $Role failed (psql exit 3)" }
            if ($isNew -and (& $fault "DownAfter:$Role")) { $pg.Down = $true; throw 'connection lost' } }.GetNewClosure()
        WriteEnv = { param($Path, $Values)
            $isNew = $Values['SEMANTIC_DB_APP_PASSWORD'] -ceq $New.SEMANTIC_DB_APP_PASSWORD
            if ($isNew -and (& $fault 'WriteEnvError')) { throw 'disk full' }
            if ($isNew -and (& $fault 'WriteEnvCorrupt')) { Set-Int37DotEnvValues $Path @{ SEMANTIC_DB_APP_PASSWORD = 'a-medias' }; return }
            if ($isNew -and $pg.CrashAt -eq 'env-midwrite') { Set-Content -LiteralPath "$Path.int37tmp" -Value 'x'; throw (New-Int37SimulatedCrash 'died writing the .env') }
            Set-Int37DotEnvValues $Path $Values }.GetNewClosure()
        WriteJournal = { param($Path, $Values)
            if ((& $fault 'JournalFirst') -and $Values['STATE'] -eq 'PREPARED') { throw 'cannot write journal' }
            Set-Content -LiteralPath $Path -Value @($Values.Keys | Sort-Object | ForEach-Object { "$_=$($Values[$_])" }) }.GetNewClosure()
        Checkpoint = { param($Step) if ($pg.CrashAt -eq $Step) { throw (New-Int37SimulatedCrash "died after $Step") } }.GetNewClosure()
        Log = { param($m) }
    }
    $plan = New-Int37PgPlan -Target 'Semantic' -Container 'nexora-semantic-dev-postgres-1' -Superuser 'postgres' -Items $Items
    return @{ Pg = $pg; Env = $envFile; Journal = $journal; Ops = $ops; Plan = $plan }
}

function Invoke-Pg($w) { Invoke-Int37PgRotation -Plan $w.Plan -Old $Old -New $New -EnvFile $w.Env -JournalFile $w.Journal -Ops $w.Ops }

function Test-Consistent($w, [string]$Expect) {
    $env = Read-Int37DotEnv $w.Env
    $want = if ($Expect -eq 'NEW') { $New } else { $Old }
    $ok = $true
    foreach ($i in $Items) { $ok = $ok -and ($env[$i.Key] -ceq $want[$i.Key]) -and ($w.Pg.Roles[$i.Role] -ceq $want[$i.Key]) }
    $ok = $ok -and $env['KC_DEV_USER_PASSWORD'] -eq 'no-se-toca' -and (Get-Content $w.Env)[0] -eq '# fichero de prueba'
    if (Test-Path $w.Journal) {
        $text = Get-Content -Raw -LiteralPath $w.Journal
        $ok = $ok -and -not (Test-Int37RotationPending $w.Journal) -and -not ($AllSecrets | Where-Object { $text.Contains($_) })
    }
    return [bool]$ok
}
function Get-Error([scriptblock]$Action) { try { & $Action | Out-Null; return '' } catch { return $_.Exception.Message } }
function Test-NoSecret([string]$Text) { return -not ($AllSecrets | Where-Object { $Text.Contains($_) }) }

# ------------------------------------------------------------------------------------------------ plan and success
$w = New-World
Check 'plan: roles de aplicación y propietario primero, superusuario el último' (($w.Plan.Items | ForEach-Object { $_.Role }) -join ',' -eq 'nexora_semantic_app,nexora_semantic_owner,postgres')
$r = Invoke-Pg $w
Check 'éxito: roles y .env con valores nuevos, diario COMPLETED sin secretos' ($r.State -eq 'COMPLETED' -and (Test-Consistent $w 'NEW'))
Check 'éxito: orden real de ALTER ROLE (superusuario el último)' (($w.Pg.Order -join ',') -eq 'nexora_semantic_app,nexora_semantic_owner,postgres') ($w.Pg.Order -join ',')
Check 'éxito: al servidor solo llegan verificadores SCRAM, nunca la contraseña' (@($w.Pg.Verifiers | Where-Object { $_ -notlike 'SCRAM-SHA-256$4096:*' -or -not (Test-NoSecret $_) }).Count -eq 0)
$e = Get-Error { New-Int37PgPlan -Target 'X' -Container 'c' -Superuser 's' -Items @(@{ Key = 'K'; Role = 'bad"; drop'; Database = 'd'; Superuser = $false }) }
Check 'plan: rechaza nombres de rol no válidos (sin inyección SQL)' ($e -like 'Invalid role or database name*')

# ------------------------------------------------------------------------------------------------ preconditions
$w = New-World; $w.Pg.LocalTrust = $false
$e = Get-Error { Invoke-Pg $w }
Check 'precondición: sin acceso local sin contraseña (vía de recuperación) -> no toca nada' ($e -like 'Precondition: no passwordless superuser access*' -and -not (Test-Path $w.Journal) -and (Test-Consistent $w 'OLD'))
$w = New-World; $w.Pg.VerificationTrusts = $true
$e = Get-Error { Invoke-Pg $w }
Check 'precondición: la vía de verificación acepta una contraseña falsa (trust) -> se niega' ($e -like '*accepts a wrong password*' -and -not (Test-Path $w.Journal))
$w = New-World; $w.Pg.VerificationTrusts = 'postgres'
$e = Get-Error { Invoke-Pg $w }
Check 'precondición: trust solo para el superusuario (regla pg_hba por usuario) -> se niega' ($e -like '*accepts a wrong password for postgres*' -and -not (Test-Path $w.Journal) -and $w.Pg.Order.Count -eq 0)
$w = New-World; $w.Pg.Roles['nexora_semantic_owner'] = 'cambiada-a-mano'
$e = Get-Error { Invoke-Pg $w }
Check 'precondición: .env distinto de la base -> no toca nada' ($e -like 'Precondition: role nexora_semantic_owner*' -and -not (Test-Path $w.Journal) -and $w.Pg.Order.Count -eq 0)
$w = New-World; Set-Content -LiteralPath $w.Journal -Value @('STATE=APPLYING', 'TYPE=keycloak')
$e = Get-Error { Invoke-Pg $w }
Check 'precondición: diario pendiente (de cualquier rotación) -> exige -Recover' ($e -like 'A previous rotation did not finish*' -and $w.Pg.Order.Count -eq 0)
$w = New-World @{ JournalFirst = $true }
$e = Get-Error { Invoke-Pg $w }
Check 'fallo al escribir el diario: no se cambia nada' ($e -eq 'cannot write journal' -and $w.Pg.Order.Count -eq 0)

# ------------------------------------------------------------------------------------------------ failure at every stage -> automatic rollback
$stages = [ordered]@{
    'ALTER del rol de aplicación (aplicado y error)' = @{ 'Alter:nexora_semantic_app' = $true }
    'ALTER del propietario rechazado (nada aplicado)' = @{ 'AlterNoApply:nexora_semantic_owner' = $true }
    'verificación TCP del propietario nuevo' = @{ 'LoginNew:nexora_semantic_owner' = $true }
    'ALTER del superusuario (aplicado y error)' = @{ 'Alter:postgres' = $true }
    'verificación TCP del superusuario nuevo' = @{ 'LoginNew:postgres' = $true }
    'escritura del .env (error)' = @{ WriteEnvError = $true }
    'escritura del .env (contenido incorrecto)' = @{ WriteEnvCorrupt = $true }
}
foreach ($name in $stages.Keys) {
    $w = New-World $stages[$name]
    $e = Get-Error { Invoke-Pg $w }
    $j = Read-Int37RotationJournal $w.Journal
    Check "fallo en $name -> reversión automática completa" ($e -like '*Rolled back*' -and $j['STATE'] -eq 'ROLLED_BACK' -and (Test-Consistent $w 'OLD')) $e
    Check "fallo en $name -> el error no contiene secretos" (Test-NoSecret $e)
}

# ------------------------------------------------------------------------------------------------ rollback fails -> -Recover
$w = New-World @{ 'DownAfter:nexora_semantic_owner' = $true }
$e = Get-Error { Invoke-Pg $w }
$j = Read-Int37RotationJournal $w.Journal
Check 'el contenedor cae tras cambiar el propietario: reversión imposible, diario ROLLBACK_FAILED' ($e -like '*automatic rollback also failed*-Recover*' -and $j['STATE'] -eq 'ROLLBACK_FAILED' -and (Test-NoSecret $e))
$w.Pg.Down = $false
$rr = Invoke-Int37PgRecovery -JournalFile $w.Journal -Ops $w.Ops
Check 'el contenedor vuelve: -Recover deja roles y .env con los valores anteriores' ($rr.Direction -eq 'OLD' -and (Test-Consistent $w 'OLD'))

# ------------------------------------------------------------------------------------------------ process dies at every checkpoint -> -Recover
$crashes = @('prepared', 'role:nexora_semantic_app', 'role:nexora_semantic_owner', 'role:postgres', 'applied', 'env-midwrite', 'env')
foreach ($c in $crashes) {
    $w = New-World -CrashAt $c
    $null = Get-Error { Invoke-Pg $w }
    $pending = Test-Int37RotationPending $w.Journal
    $desync = $c -like 'role:*' -and ((Read-Int37DotEnv $w.Env)['SEMANTIC_DB_APP_PASSWORD'] -cne $w.Pg.Roles['nexora_semantic_app'])
    $rr = Invoke-Int37PgRecovery -JournalFile $w.Journal -Ops $w.Ops
    $expect = if ($c -eq 'env') { 'NEW' } else { 'OLD' }
    Check "el proceso muere en '$c' -> diario pendiente y -Recover deja todo en $expect" ($pending -and $rr.Direction -eq $expect -and (Test-Consistent $w $expect))
    if ($c -eq 'role:nexora_semantic_app') {
        Check "caso documentado: contraseña cambiada en la base y .env sin actualizar (desincronizado) -> -Recover lo resincroniza" ($desync -and (Test-Consistent $w 'OLD'))
    }
}
$again = Invoke-Int37PgRecovery -JournalFile $w.Journal -Ops $w.Ops
Check '-Recover repetido no hace nada' ($again.State -eq 'NOTHING')

# ------------------------------------------------------------------------------------------------ recovery needs the local path
$w = New-World -CrashAt 'role:nexora_semantic_owner'
$null = Get-Error { Invoke-Pg $w }
$w.Pg.LocalTrust = $false
$e = Get-Error { Invoke-Int37PgRecovery -JournalFile $w.Journal -Ops $w.Ops }
Check '-Recover sin acceso local: se niega y el diario sigue pendiente (procedimiento manual)' ($e -like 'Precondition: no passwordless superuser access*' -and (Test-Int37RotationPending $w.Journal))
$kc = New-World; Set-Content -LiteralPath $kc.Journal -Value @('STATE=APPLYING', 'TYPE=keycloak')
$e = Get-Error { Invoke-Int37PgRecovery -JournalFile $kc.Journal -Ops $kc.Ops }
Check '-Recover PostgreSQL no toca un diario de Keycloak' ($e -like '*keycloak rotation*')
$pgj = New-World -CrashAt 'prepared'; $null = Get-Error { Invoke-Pg $pgj }
$e = Get-Error { Invoke-Int37RotationRecovery -JournalFile $pgj.Journal -Ops @{ TestAdmin = {}; TestUser = {}; TestClient = {}; OpenAdmin = {}; SetUserPassword = {}; SetClientSecret = {}; SetAdminPassword = {} } }
Check '-Recover de Keycloak no toca un diario de PostgreSQL' ($e -like '*postgres rotation*')

Remove-Item -LiteralPath $Work -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "`n$($script:passed) PASS, $($script:failures) FAIL"
if ($script:failures -gt 0) { exit 1 }
