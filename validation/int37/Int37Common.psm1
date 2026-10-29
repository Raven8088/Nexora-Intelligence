#Requires -Version 7.0
<#
  Shared helpers for the INT-3.7 preparation scripts (local demo integration).

  Rules enforced here:
  - Secrets are read from environment variables, from the local secret store (outside every repository) or from
    the git-ignored docker/.env files of Semantic and Reports. They are never printed, logged or written to a
    versionable file.
  - Tokens are never printed: only a summary of selected claims.
#>

Set-StrictMode -Version Latest

$script:Marker = @{ Key = 'nexora.created-by'; Value = 'INT-3.7' }
$script:UserMarkerDomain = 'nexora-int37.invalid'
$script:CanonicalUuid = '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
$script:ZeroUuid = '00000000-0000-0000-0000-000000000000'
$script:NexoraRoles = @('VIEWER', 'EDITOR', 'APPROVER', 'STEWARD', 'AUDITOR', 'REPORTS_CLIENT', 'AI_AGENT',
    'PLATFORM_ADMIN', 'REPORT_RUNNER')

function Get-Int37Marker { return $script:Marker }
function Get-Int37UserMarkerDomain { return $script:UserMarkerDomain }
function Get-Int37ZeroUuid { return $script:ZeroUuid }

function Test-Int37CanonicalUuid([string]$Value) {
    return [bool]($Value -cmatch $script:CanonicalUuid)
}

# ------------------------------------------------------------------------------------------------ local home

<# Local folder for INT-3.7 state, generated configuration, secrets and reports. Never inside a repository. #>
function Get-Int37Home {
    $configured = [Environment]::GetEnvironmentVariable('NEXORA_INT37_HOME')
    if ($configured) { return $configured }
    return (Join-Path $HOME '.nexora/int37')
}

function Initialize-Int37Home {
    $dir = Get-Int37Home
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    Set-Int37OwnerOnly $dir -Directory
    New-Item -ItemType Directory -Force -Path (Join-Path $dir 'out') | Out-Null
    return $dir
}

<# Restricts a file or folder to the current user (Windows ACL). Elsewhere it uses chmod. #>
function Set-Int37OwnerOnly([string]$Path, [switch]$Directory) {
    if ($IsWindows) {
        $grant = if ($Directory) { "$($env:USERNAME):(OI)(CI)F" } else { "$($env:USERNAME):F" }
        & icacls $Path /inheritance:r /grant:r $grant | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not restrict the permissions of $Path" }
    } else {
        & chmod $(if ($Directory) { '700' } else { '600' }) $Path
    }
}

function Get-Int37SecretsFile { return (Join-Path (Get-Int37Home) 'secrets.env') }
function Get-Int37StateFile { return (Join-Path (Get-Int37Home) 'state.json') }

# ------------------------------------------------------------------------------------------------ .env files

function Read-Int37DotEnv([string]$Path) {
    $values = @{}
    if ($Path -and (Test-Path $Path)) {
        foreach ($line in Get-Content -LiteralPath $Path) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
                $values[$Matches[1]] = $Matches[2].Trim('"').Trim("'")
            }
        }
    }
    return $values
}

<# Sets KEY=value in a .env file, keeping every other line. The value is never printed. #>
function Set-Int37DotEnvValue([string]$Path, [string]$Key, [string]$Value) {
    Set-Int37DotEnvValues $Path @{ $Key = $Value }
}

<#
  Sets several KEY=value pairs in ONE replacement of the file: the new content is written to "<file>.int37tmp" and
  then moved over the original, so a reader sees either the old file or the new one, never a mix. Other lines,
  comments and order are kept. Values are never printed.
#>
function Set-Int37DotEnvValues([string]$Path, [hashtable]$Values) {
    foreach ($k in $Values.Keys) {
        if ($k -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { throw "Invalid key $k" }
        if ("$($Values[$k])" -match '[\r\n]') { throw "Invalid value for $k" }
    }
    $lines = [System.Collections.Generic.List[string]]::new()
    $written = @{}
    if (Test-Path $Path) {
        foreach ($line in Get-Content -LiteralPath $Path) {
            $m = [regex]::Match($line, '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=')
            if ($m.Success -and $Values.ContainsKey($m.Groups[1].Value)) {
                $k = $m.Groups[1].Value
                if (-not $written.ContainsKey($k)) { $lines.Add("$k=$($Values[$k])"); $written[$k] = $true }
            } else {
                $lines.Add($line)
            }
        }
    }
    foreach ($k in ($Values.Keys | Sort-Object)) { if (-not $written.ContainsKey($k)) { $lines.Add("$k=$($Values[$k])") } }
    $tmp = "$Path.int37tmp"
    [IO.File]::WriteAllLines($tmp, $lines, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $tmp -Destination $Path -Force
}

# ------------------------------------------------------------------------------------------------ secrets

<# Random secret from the OS cryptographic generator: letters and digits only (safe in .env, URLs and YAML). #>
function New-Int37Secret([int]$Length = 32) {
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $chars = [char[]]::new($Length)
    for ($i = 0; $i -lt $Length; $i++) {
        $chars[$i] = $alphabet[[System.Security.Cryptography.RandomNumberGenerator]::GetInt32($alphabet.Length)]
    }
    return [string]::new($chars)
}

<#
  Value of a secret: environment variable first, then the given .env hashtables in order. Throws with the NAME
  only when it is missing.
#>
function Get-Int37Secret([string]$Name, [hashtable[]]$Sources = @()) {
    $fromEnv = [Environment]::GetEnvironmentVariable($Name)
    if ($fromEnv) { return $fromEnv }
    foreach ($source in $Sources) {
        if ($source -and $source.ContainsKey($Name) -and $source[$Name]) { return $source[$Name] }
    }
    throw "Secret $Name not found (environment variable, secret store or .env file)."
}

function Read-Int37SecretStore { return (Read-Int37DotEnv (Get-Int37SecretsFile)) }

<#
  Quality of a set of secrets, by name only: minimum length and no value shared between two names.
  Returns a list of problems (strings without any secret value).
#>
function Test-Int37SecretSet([hashtable]$Secrets, [int]$MinLength = 24) {
    $problems = [System.Collections.Generic.List[string]]::new()
    $names = @($Secrets.Keys | Sort-Object)
    foreach ($n in $names) {
        if (-not $Secrets[$n]) { $problems.Add("$n is missing"); continue }
        if ($Secrets[$n].Length -lt $MinLength) { $problems.Add("$n is shorter than $MinLength characters") }
    }
    for ($i = 0; $i -lt $names.Count; $i++) {
        for ($j = $i + 1; $j -lt $names.Count; $j++) {
            $a = $Secrets[$names[$i]]; $b = $Secrets[$names[$j]]
            if ($a -and $b -and $a -ceq $b) { $problems.Add("$($names[$i]) and $($names[$j]) share the same value") }
        }
    }
    return , $problems
}

# ------------------------------------------------------------------------------------------------ state

function Read-Int37State {
    $file = Get-Int37StateFile
    if (Test-Path $file) { return (Get-Content -Raw -LiteralPath $file | ConvertFrom-Json -AsHashtable) }
    return @{}
}

function Write-Int37State([hashtable]$State) {
    Initialize-Int37Home | Out-Null
    $State['updatedAt'] = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    $State | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Get-Int37StateFile) -Encoding utf8
}

# ------------------------------------------------------------------------------------------------ HTTP

function Invoke-Int37Http {
    param([string]$Method = 'GET', [string]$Url, [string]$Token, $Body, [string]$RawBody,
          [hashtable]$Headers = @{}, [string]$ContentType = 'application/json', [hashtable]$Form,
          [int]$TimeoutSec = 120)
    $h = @{} + $Headers
    if ($Token) { $h['Authorization'] = "Bearer $Token" }
    $params = @{ Method = $Method; Uri = $Url; Headers = $h; SkipHttpErrorCheck = $true; TimeoutSec = $TimeoutSec }
    if ($Form) {
        $params['Body'] = $Form
        $params['ContentType'] = 'application/x-www-form-urlencoded'
    } elseif ($PSBoundParameters.ContainsKey('RawBody')) {
        $params['Body'] = [Text.Encoding]::UTF8.GetBytes($RawBody)
        $params['ContentType'] = $ContentType
    } elseif ($null -ne $Body) {
        $params['Body'] = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $Body -Depth 50 -Compress))
        $params['ContentType'] = $ContentType
    } elseif ($Method -in 'POST', 'PUT') {
        $params['Body'] = [byte[]]@()
        $params['ContentType'] = $ContentType
    }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-WebRequest @params
    } catch {
        return [pscustomobject]@{ Status = 0; Text = ''; Json = $null; Headers = @{}; Millis = $watch.ElapsedMilliseconds
            Error = $_.Exception.GetType().Name }
    }
    $text = if ($r.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($r.Content) } else { [string]$r.Content }
    $json = $null
    if ($text -and ($text.TrimStart().StartsWith('{') -or $text.TrimStart().StartsWith('['))) {
        try { $json = $text | ConvertFrom-Json -Depth 100 -AsHashtable -NoEnumerate } catch { $json = $null }
    }
    return [pscustomobject]@{ Status = [int]$r.StatusCode; Text = $text; Json = $json; Headers = $r.Headers
        Millis = $watch.ElapsedMilliseconds; Error = $null }
}

<# Throws when the status is not expected. The error shows a short, sanitized part of the body, never a token. #>
function Assert-Int37Status([object]$Response, [int[]]$Status, [string]$What) {
    if ($Status -notcontains $Response.Status) {
        $detail = if ($Response.Status -eq 0) { "no connection ($($Response.Error))" } else {
            $code = if ($Response.Json -is [System.Collections.IDictionary] -and $Response.Json.Contains('code')) {
                " code=$($Response.Json['code'])" } else { '' }
            "HTTP $($Response.Status)$code"
        }
        throw "$What -> $detail (expected $($Status -join '/'))"
    }
    return $Response
}

function Get-Int37HeaderValue([object]$Response, [string]$Name) {
    if (-not $Response.Headers) { return $null }
    foreach ($k in $Response.Headers.Keys) {
        if ($k -ieq $Name) { return (@($Response.Headers[$k]) -join ',') }
    }
    return $null
}

# ------------------------------------------------------------------------------------------------ tokens

function Get-Int37PasswordToken([string]$KeycloakUrl, [string]$Realm, [string]$Client, [string]$User,
                                [string]$Password) {
    $r = Invoke-Int37Http -Method POST -Url "$KeycloakUrl/realms/$Realm/protocol/openid-connect/token" -Form @{
        grant_type = 'password'; client_id = $Client; username = $User; password = $Password }
    return (Assert-Int37Status $r 200 "token for $User via $Client").Json['access_token']
}

function Get-Int37ClientToken([string]$KeycloakUrl, [string]$Realm, [string]$Client, [string]$Secret) {
    $r = Invoke-Int37Http -Method POST -Url "$KeycloakUrl/realms/$Realm/protocol/openid-connect/token" -Form @{
        grant_type = 'client_credentials'; client_id = $Client; client_secret = $Secret }
    return (Assert-Int37Status $r 200 "service token for $Client").Json['access_token']
}

# ------------------------------------------------------------------------------------------------ admin session

<#
  Keycloak admin session (master realm, admin-cli). The admin access token is short-lived (Keycloak's default for
  the master realm is 60 s), so a long run must renew it:
  - the token is renewed when less than SafetySeconds (15 s) remain, using the expires_in of the token response;
  - an admin request answered 401 is retried at most ONCE with a freshly issued token (a 401 means Keycloak rejected
    the request without executing it); a second 401 is returned to the caller unchanged, so it fails as before.
  The token and the password live only in hidden members / a closure: they are never printed, logged or put in an
  error message. Http and Now can be replaced in tests.
#>
class Int37AdminSession {
    [string]$TokenUrl
    [string]$User
    [string]$Client
    [int]$SafetySeconds
    [datetime]$ExpiresAt = [datetime]::MinValue
    [int]$TokensIssued = 0
    [int]$Retries = 0
    # Token and password are only reachable through closures: not even ConvertTo-Json or Format-List can show them.
    hidden [scriptblock]$ReadToken
    hidden [scriptblock]$WriteToken
    hidden [scriptblock]$GetPassword
    hidden [scriptblock]$Http
    hidden [scriptblock]$Now
    [string] ToString() { return "Int37AdminSession(user=$($this.User), expiresAt=$($this.ExpiresAt.ToString('o')), tokensIssued=$($this.TokensIssued))" }
}

function New-Int37AdminSession {
    param([Parameter(Mandatory = $true)][string]$KeycloakUrl, [Parameter(Mandatory = $true)][string]$Password,
          [string]$User = 'admin', [string]$Client = 'admin-cli', [int]$SafetySeconds = 15,
          [scriptblock]$Http, [scriptblock]$Now)
    $session = [Int37AdminSession]::new()
    $session.TokenUrl = "$KeycloakUrl/realms/master/protocol/openid-connect/token"
    $session.User = $User
    $session.Client = $Client
    $session.SafetySeconds = $SafetySeconds
    $secret = $Password
    $session.GetPassword = { $secret }.GetNewClosure()
    $vault = @{ Token = $null }
    $session.ReadToken = { $vault.Token }.GetNewClosure()
    $session.WriteToken = { param([string]$Value) $vault.Token = $Value }.GetNewClosure()
    $session.Http = if ($Http) { $Http } else { { param([hashtable]$Request) Invoke-Int37Http @Request } }
    $session.Now = if ($Now) { $Now } else { { [datetime]::UtcNow } }
    return $session
}

<# Current admin token; renews it when missing, forced, or within SafetySeconds of expiry. #>
function Get-Int37AdminToken([Parameter(Mandatory = $true)][Int37AdminSession]$Session, [switch]$Force) {
    $now = & $Session.Now
    if ($Force -or -not (& $Session.ReadToken) -or $now -ge $Session.ExpiresAt.AddSeconds(-$Session.SafetySeconds)) {
        $r = & $Session.Http @{ Method = 'POST'; Url = $Session.TokenUrl; Form = @{
            grant_type = 'password'; client_id = $Session.Client; username = $Session.User
            password = (& $Session.GetPassword) } }
        Assert-Int37Status $r 200 "Keycloak admin token for $($Session.User)" | Out-Null
        $token = if ($r.Json -is [System.Collections.IDictionary]) { $r.Json['access_token'] } else { $null }
        $expiresIn = if ($r.Json -is [System.Collections.IDictionary]) { $r.Json['expires_in'] } else { $null }
        if (-not $token) { throw 'Keycloak admin token response without access_token' }
        if (-not ($expiresIn -is [int] -or $expiresIn -is [long]) -or $expiresIn -le 0) {
            throw 'Keycloak admin token response without a valid expires_in'
        }
        & $Session.WriteToken $token
        $Session.ExpiresAt = $now.AddSeconds([double]$expiresIn)
        $Session.TokensIssued++
    }
    return (& $Session.ReadToken)
}

<#
  Admin REST request with the session's token. Same result object as Invoke-Int37Http. On HTTP 401 the token is
  renewed and the request is sent once more; never more than one retry.
#>
function Invoke-Int37Admin {
    param([Parameter(Mandatory = $true)][Int37AdminSession]$Session, [string]$Method = 'GET',
          [Parameter(Mandatory = $true)][string]$Url, $Body)
    $request = @{ Method = $Method; Url = $Url }
    if ($PSBoundParameters.ContainsKey('Body')) { $request['Body'] = $Body }
    $request['Token'] = Get-Int37AdminToken $Session
    $r = & $Session.Http $request
    if ($r.Status -eq 401) {
        $Session.Retries++
        $request['Token'] = Get-Int37AdminToken $Session -Force
        $r = & $Session.Http $request
    }
    return $r
}

<# Claims of a JWT (payload only, signature NOT verified: for diagnostics; the services verify it). #>
function ConvertFrom-Int37Jwt([string]$Token) {
    $parts = $Token.Split('.')
    if ($parts.Count -ne 3) { throw 'Not a JWT' }
    $p = $parts[1].Replace('-', '+').Replace('_', '/')
    switch ($p.Length % 4) { 2 { $p += '==' } 3 { $p += '=' } }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p)) | ConvertFrom-Json -AsHashtable
}

function Get-Int37ClaimList([hashtable]$Claims, [string]$Name) {
    if (-not $Claims.ContainsKey($Name) -or $null -eq $Claims[$Name]) { return @() }
    return @($Claims[$Name] | ForEach-Object { "$_" })
}

<# Printable summary: issuer, audiences, principal type, tenant and Nexora roles. Never the token or sub. #>
function Format-Int37Claims([hashtable]$Claims) {
    $aud = (Get-Int37ClaimList $Claims 'aud') -join ','
    $roles = (Get-Int37ClaimList $Claims 'roles' | Where-Object { $script:NexoraRoles -contains $_ } | Sort-Object) -join ','
    return "iss=$($Claims['iss']) aud=[$aud] principal_type=$($Claims['principal_type']) tenant=$($Claims['tenant']) roles=[$roles]"
}

# ------------------------------------------------------------------------------------------------ output

function Write-Int37Step([string]$Text) { Write-Host "`n== $Text" -ForegroundColor Cyan }
function Write-Int37Ok([string]$Text) { Write-Host "[OK]    $Text" -ForegroundColor Green }
function Write-Int37Warn([string]$Text) { Write-Host "[AVISO] $Text" -ForegroundColor Yellow }
function Write-Int37Fail([string]$Text) { Write-Host "[FALLO] $Text" -ForegroundColor Red }

<# Rows of a Reports or Intelligence result as objects keyed by column (Reports: name, Intelligence: key). #>
function ConvertTo-Int37KeyedRows($Columns, $Rows) {
    $keys = @($Columns | ForEach-Object { if ($_.Contains('key')) { $_['key'] } else { $_['name'] } })
    $out = [System.Collections.Generic.List[object]]::new()
    foreach ($row in @($Rows)) {
        $o = [ordered]@{}
        for ($i = 0; $i -lt $keys.Count; $i++) { $o[$keys[$i]] = $row[$i] }
        $out.Add($o)
    }
    return , $out
}

<# Compares result rows with the expected rows (ordered, same column keys). Returns '' when equal, else a reason. #>
function Compare-Int37Rows($Columns, $Rows, [hashtable]$Expected) {
    $actual = ConvertTo-Int37KeyedRows $Columns $Rows
    $want = $Expected['rows']
    if ($actual.Count -ne $want.Count) { return "rows: expected $($want.Count), got $($actual.Count)" }
    $cols = $Expected['columns']
    for ($i = 0; $i -lt $want.Count; $i++) {
        for ($c = 0; $c -lt $cols.Count; $c++) {
            $key = $cols[$c]
            if (-not $actual[$i].Contains($key)) { return "column $key missing" }
            $a = $actual[$i][$key]; $e = $want[$i][$c]
            if ("$a" -cne "$e" -or (($null -eq $a) -ne ($null -eq $e))) {
                $show = { param($v) if ($null -eq $v) { '<null>' } else { "'$v'" } }
                return "row $($i + 1), column ${key}: expected $(& $show $e), got $(& $show $a)"
            }
        }
    }
    return ''
}

Export-ModuleMember -Function *-Int37*
