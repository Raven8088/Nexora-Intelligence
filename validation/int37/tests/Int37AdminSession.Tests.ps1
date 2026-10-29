#Requires -Version 7.0
<#
.SYNOPSIS
  Automated tests of the Keycloak admin session (Int37Common.psm1): expiry, renewal, single retry on 401 and
  persistent failure. Offline: HTTP and the clock are replaced by fakes; nothing is sent to any server.

.EXAMPLE
  pwsh -NoProfile -File ./tests/Int37AdminSession.Tests.ps1      # exit code 0 = all pass
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '../Int37Common.psm1') -Force

$script:failures = 0
$script:passed = 0
function Check([string]$Name, [bool]$Condition, [string]$Detail = '') {
    if ($Condition) { $script:passed++; Write-Host "[PASS] $Name" -ForegroundColor Green }
    else { $script:failures++; Write-Host "[FAIL] $Name $Detail" -ForegroundColor Red }
}

$Password = 'P4ssw0rd-de-prueba-que-no-debe-aparecer'
$TokenUrl = 'http://kc.test/realms/master/protocol/openid-connect/token'

<#
  Fake Keycloak: issues tokens "tok-1", "tok-2"... with expires_in = $ExpiresIn; an admin request is accepted when
  its token is in $Valid. $Script:log records every call (method, url, token) for assertions.
#>
function New-Fake([int]$ExpiresIn = 60, [scriptblock]$AdminBehaviour, [scriptblock]$NowFn) {
    $state = @{ Issued = 0; Valid = [System.Collections.Generic.HashSet[string]]::new(); Log = [System.Collections.Generic.List[object]]::new()
        ExpiresIn = $ExpiresIn; Behaviour = $AdminBehaviour; TokenStatus = 200; NowFn = $NowFn; IssuedAt = @{} }
    $http = {
        param([hashtable]$Request)
        $state.Log.Add([pscustomobject]@{ Method = $Request['Method']; Url = $Request['Url']; Token = $Request['Token']
            HasBody = $Request.ContainsKey('Body'); Body = $Request['Body'] })
        if ($Request['Url'] -eq $TokenUrl) {
            if ($state.TokenStatus -ne 200) { return [pscustomobject]@{ Status = $state.TokenStatus; Text = ''; Json = @{ error = 'invalid_grant' }; Headers = @{}; Millis = 1; Error = $null } }
            if ($Request['Form']['password'] -ne $Password) { return [pscustomobject]@{ Status = 401; Text = ''; Json = @{ error = 'invalid_grant' }; Headers = @{}; Millis = 1; Error = $null } }
            $state.Issued++
            $t = "tok-$($state.Issued)"
            [void]$state.Valid.Add($t)
            if ($state.NowFn) { $state.IssuedAt[$t] = & $state.NowFn }
            $json = @{ access_token = $t; token_type = 'Bearer' }
            if ($null -ne $state.ExpiresIn) { $json['expires_in'] = [long]$state.ExpiresIn }
            return [pscustomobject]@{ Status = 200; Text = ''; Json = $json; Headers = @{}; Millis = 1; Error = $null }
        }
        $status = & $state.Behaviour $Request $state
        return [pscustomobject]@{ Status = $status; Text = ''; Json = @{}; Headers = @{}; Millis = 1; Error = $null }
    }.GetNewClosure()
    return @{ State = $state; Http = $http }
}

$acceptValid = { param($Request, $state) if ($state.Valid.Contains($Request['Token'])) { 200 } else { 401 } }
function Clock([datetime]$Start) {
    $c = @{ Now = $Start }
    return @{ Clock = $c; Now = { $c.Now }.GetNewClosure() }
}
$t0 = [datetime]::new(2026, 10, 28, 12, 0, 0, [DateTimeKind]::Utc)

# ------------------------------------------------------------------------------------------------ 1. reuse
$f = New-Fake 60 $acceptValid; $c = Clock $t0
$s = New-Int37AdminSession -KeycloakUrl 'http://kc.test' -Password $Password -Http $f.Http -Now $c.Now
$r1 = Invoke-Int37Admin -Session $s -Url 'http://kc.test/admin/realms/nexora/roles'
$c.Clock.Now = $t0.AddSeconds(30)
$r2 = Invoke-Int37Admin -Session $s -Url 'http://kc.test/admin/realms/nexora/roles'
Check 'reutiliza el token mientras queda más de 15 s' ($r1.Status -eq 200 -and $r2.Status -eq 200 -and $s.TokensIssued -eq 1)
Check 'caducidad calculada con expires_in' ($s.ExpiresAt -eq $t0.AddSeconds(60))

# ------------------------------------------------------------------------------------------------ 2. renewal by margin
$c.Clock.Now = $t0.AddSeconds(44)            # 16 s left -> still valid
$null = Invoke-Int37Admin -Session $s -Url 'http://kc.test/admin/realms/nexora/roles'
Check 'con 16 s restantes no renueva' ($s.TokensIssued -eq 1)
$c.Clock.Now = $t0.AddSeconds(45)            # exactly 15 s left -> renew
$r = Invoke-Int37Admin -Session $s -Url 'http://kc.test/admin/realms/nexora/roles'
Check 'con 15 s restantes renueva antes de enviar' ($s.TokensIssued -eq 2 -and $r.Status -eq 200 -and $f.State.Log[-1].Token -eq 'tok-2' -and $s.Retries -eq 0)
Check 'nueva caducidad desde la renovación' ($s.ExpiresAt -eq $t0.AddSeconds(105))

# ------------------------------------------------------------------------------------------------ 3. expiry (long run)
# The fake server rejects a token 60 s after issuing it, like Keycloak's master realm.
$expiring = { param($Request, $state)
    $t = $Request['Token']
    if ($state.Valid.Contains($t) -and (& $state.NowFn) -lt $state.IssuedAt[$t].AddSeconds($state.ExpiresIn)) { 200 } else { 401 } }
$c = Clock $t0
$f = New-Fake 60 $expiring $c.Now
# Control: the previous behaviour (one token for the whole run) fails after 60 s, as seen on Windows.
$single = Get-Int37AdminToken (New-Int37AdminSession -KeycloakUrl 'http://kc.test' -Password $Password -Http $f.Http -Now $c.Now)
$old = foreach ($i in 0..40) {
    $c.Clock.Now = $t0.AddSeconds(2.2 * $i)
    (& $f.Http @{ Method = 'GET'; Url = 'http://kc.test/admin/realms/nexora/users'; Token = $single }).Status
}
$firstFail = [array]::IndexOf(@($old), 401)
Check 'control (comportamiento anterior): un solo token falla con 401 tras ~60 s' ($firstFail -gt 0 -and $firstFail * 2.2 -ge 60) "primer 401 en la petición $firstFail"
$c = Clock $t0
$f = New-Fake 60 $expiring $c.Now
$s = New-Int37AdminSession -KeycloakUrl 'http://kc.test' -Password $Password -Http $f.Http -Now $c.Now
$statuses = foreach ($i in 0..40) {           # 41 requests, 2.2 s apart: 88 s, longer than one token
    $c.Clock.Now = $t0.AddSeconds(2.2 * $i)
    (Invoke-Int37Admin -Session $s -Url 'http://kc.test/admin/realms/nexora/users').Status
}
Check 'corrección: ejecución de 88 s con todas las respuestas 200' (@($statuses | Where-Object { $_ -ne 200 }).Count -eq 0)
Check 'corrección: 2 tokens por renovación anticipada, sin reintentos' ($s.TokensIssued -eq 2 -and $s.Retries -eq 0) "issued=$($s.TokensIssued) retries=$($s.Retries)"

# ------------------------------------------------------------------------------------------------ 4. single retry on 401
$f = New-Fake 300 { param($Request, $state) if ($Request['Token'] -eq 'tok-1') { 401 } else { 200 } }  # tok-1 revoked server-side
$c = Clock $t0
$s = New-Int37AdminSession -KeycloakUrl 'http://kc.test' -Password $Password -Http $f.Http -Now $c.Now
$r = Invoke-Int37Admin -Session $s -Method POST -Url 'http://kc.test/admin/realms/nexora/roles' -Body @{ name = 'X' }
$admin = @($f.State.Log | Where-Object { $_.Url -ne $TokenUrl })
Check '401 inesperado: un reintento con token nuevo y éxito' ($r.Status -eq 200 -and $s.Retries -eq 1 -and $s.TokensIssued -eq 2 -and $admin.Count -eq 2 -and $admin[1].Token -eq 'tok-2')
Check 'el reintento conserva método y cuerpo' ($admin[1].Method -eq 'POST' -and $admin[1].HasBody -and $admin[1].Body['name'] -eq 'X')

# ------------------------------------------------------------------------------------------------ 5. persistent failure
$f = New-Fake 300 { param($Request, $state) 401 }
$c = Clock $t0
$s = New-Int37AdminSession -KeycloakUrl 'http://kc.test' -Password $Password -Http $f.Http -Now $c.Now
$r = Invoke-Int37Admin -Session $s -Url 'http://kc.test/admin/realms/nexora/users?username=e2e.runner&exact=true'
$admin = @($f.State.Log | Where-Object { $_.Url -ne $TokenUrl })
Check '401 persistente: exactamente 2 envíos y 2 tokens (sin bucle)' ($r.Status -eq 401 -and $admin.Count -eq 2 -and $s.TokensIssued -eq 2 -and $s.Retries -eq 1)
$message = try { Assert-Int37Status $r 200 'user e2e.runner' | Out-Null; '' } catch { $_.Exception.Message }
Check '401 persistente: el error es el mismo de siempre' ($message -eq 'user e2e.runner -> HTTP 401 (expected 200)') $message

# ------------------------------------------------------------------------------------------------ 6. other statuses are not retried
foreach ($code in 403, 404, 500) {
    $f = New-Fake 300 ({ param($Request, $state) $code }.GetNewClosure())
    $s = New-Int37AdminSession -KeycloakUrl 'http://kc.test' -Password $Password -Http $f.Http -Now (Clock $t0).Now
    $r = Invoke-Int37Admin -Session $s -Url 'http://kc.test/admin/realms/nexora/users'
    $admin = @($f.State.Log | Where-Object { $_.Url -ne $TokenUrl })
    Check "HTTP $code se devuelve sin reintentar" ($r.Status -eq $code -and $admin.Count -eq 1 -and $s.Retries -eq 0)
}

# ------------------------------------------------------------------------------------------------ 7. methods without body
$f = New-Fake 300 $acceptValid
$s = New-Int37AdminSession -KeycloakUrl 'http://kc.test' -Password $Password -Http $f.Http -Now (Clock $t0).Now
$null = Invoke-Int37Admin -Session $s -Method PUT -Url 'http://kc.test/admin/realms/nexora/users/1/groups/2'
Check 'PUT sin cuerpo no añade Body (comportamiento previo)' (-not $f.State.Log[-1].HasBody -and $f.State.Log[-1].Method -eq 'PUT')

# ------------------------------------------------------------------------------------------------ 8. token endpoint errors
$f = New-Fake 300 $acceptValid; $f.State.TokenStatus = 401
$s = New-Int37AdminSession -KeycloakUrl 'http://kc.test' -Password $Password -Http $f.Http -Now (Clock $t0).Now
$message = try { Get-Int37AdminToken $s | Out-Null; '' } catch { $_.Exception.Message }
Check 'contraseña de admin rechazada: error claro al inicio' ($message -eq 'Keycloak admin token for admin -> HTTP 401 (expected 200)') $message
$f = New-Fake 300 $acceptValid; $f.State.ExpiresIn = $null
$s = New-Int37AdminSession -KeycloakUrl 'http://kc.test' -Password $Password -Http $f.Http -Now (Clock $t0).Now
$message = try { Get-Int37AdminToken $s | Out-Null; '' } catch { $_.Exception.Message }
Check 'respuesta sin expires_in: se rechaza' ($message -eq 'Keycloak admin token response without a valid expires_in') $message

# ------------------------------------------------------------------------------------------------ 9. no exposure
$f = New-Fake 60 $acceptValid
$s = New-Int37AdminSession -KeycloakUrl 'http://kc.test' -Password $Password -Http $f.Http -Now (Clock $t0).Now
$null = Get-Int37AdminToken $s
$shown = ($s | Format-List * | Out-String) + ($s | Format-Table | Out-String) + "$s" + ($s | ConvertTo-Json -Depth 3 -WarningAction SilentlyContinue)
Check 'la sesión no muestra el token' (-not $shown.Contains('tok-1'))
Check 'la sesión no muestra la contraseña' (-not $shown.Contains($Password))

# ------------------------------------------------------------------------------------------------ 10. script wiring
# Invoke-Int37Keycloak.ps1 must reach the admin API only through the session (static check of the source).
$source = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '../Invoke-Int37Keycloak.ps1')
Check 'Invoke-Int37Keycloak.ps1 ya no usa un token de administrador fijo' (-not ($source -cmatch '\$script:adminToken|Get-AdminToken'))
Check 'ninguna llamada directa a la API de administración fuera de Invoke-Admin' (([regex]::Matches($source, '"\$AdminUrl')).Count -eq 2 -and -not ($source -match 'Invoke-Int37Http[^\r\n]*\$AdminUrl'))
Check 'la sesión no comparte nombre con la URL de administración (variables sin distinción de mayúsculas)' (-not ($source -match '\$script:admin\b'))
Check '-Inspect, -Inventory, -Apply, -Verify, -Revert y -SharedReportsAudience abren la sesión renovable' (([regex]::Matches($source, '(?m)^\s+Open-AdminSession\s*$')).Count -eq 6)
# audience fix (INT-3.7): the verifier keeps forbidding nexora-reports for humans; the shared mapper is never deleted
Check '-Verify sigue exigiendo que demo.editor y demo.approver NO lleven aud nexora-reports' ($source.Contains("'HUMAN' `$tenant @(`$u.Role) `$not @('nexora-semantic') @('nexora-reports')"))
Check '-SharedReportsAudience no borra mappers (solo los desactiva con PUT)' (-not ($source -match "Method DELETE[^\r\n]*protocol-mappers"))

Write-Host "`n$($script:passed) PASS, $($script:failures) FAIL"
if ($script:failures -gt 0) { exit 1 }
