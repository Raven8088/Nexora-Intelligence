#Requires -Version 7.0
<#
  INT-3.7 (AUT-1): rotation of Keycloak development credentials IN PLACE, without recreating Keycloak.

  The engine is independent of HTTP: every action on Keycloak, on the .env file and on the journal is an operation
  of the $Ops table (see New-Int37RotationOps in Update-Int37Credentials.ps1, and the fakes in
  tests/Int37Rotation.Tests.ps1). Secrets are never printed, logged or put in an error message.

  Order (the master admin password always LAST):
    0. preconditions: no pending journal; every current credential authenticates (the .env matches Keycloak)
    1. journal PREPARED (old and new values, readable only by the current user)
    2. development users  -> each new password verified with a token
    3. service client     -> new secret verified with a token
    4. master admin       -> new password verified with a token and a new admin session
    5. .env replaced in ONE atomic write, then re-read and compared
    6. journal COMPLETED (secrets removed from it)
  Any failure before 6 triggers an automatic rollback (admin first, then client, then users, then .env). If the
  rollback cannot finish, or the process dies (power loss, closed window), the journal keeps what is needed for
  -Recover, which decides from the .env and from live logins whether to roll forward or back.
#>

Set-StrictMode -Version Latest
# Requires the functions of Int37Common.psm1 (import it first, as the scripts do). It is not imported here on
# purpose: a second copy of Int37Common would define a second Int37AdminSession type.

<# Thrown only by tests (Checkpoint) to emulate a process that dies: it bypasses the automatic rollback. #>
class Int37SimulatedCrash : System.Exception {
    Int37SimulatedCrash([string]$Message) : base($Message) {}
}

<# For tests only: the exception that emulates a dying process (see Checkpoint). #>
function New-Int37SimulatedCrash([string]$Message) { return [Int37SimulatedCrash]::new($Message) }

$script:SecretPrefixes = @('OLD_', 'NEW_')
$script:FinalStates = @('COMPLETED', 'ROLLED_BACK', 'RECOVERED')

function Get-Int37RotationJournalFile { return (Join-Path (Get-Int37Home) 'rotation-journal.env') }

function Read-Int37RotationJournal([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $j = Read-Int37DotEnv $Path
    if (-not $j.ContainsKey('STATE')) { return $null }
    return $j
}

<# True when a journal exists in a non-final state (a rotation may be half applied). #>
function Test-Int37RotationPending([string]$Path) {
    $j = Read-Int37RotationJournal $Path
    return [bool]($j -and $script:FinalStates -notcontains $j['STATE'])
}

function Get-Int37RotationDefaultOps {
    return @{
        WriteEnv = { param([string]$Path, [hashtable]$Values) Set-Int37DotEnvValues $Path $Values }
        WriteJournal = {
            param([string]$Path, [hashtable]$Values)
            $dir = Split-Path -Parent $Path
            if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
            if (-not (Test-Path $Path)) { New-Item -ItemType File -Path $Path | Out-Null; Set-Int37OwnerOnly $Path }
            $lines = foreach ($k in ($Values.Keys | Sort-Object)) { "$k=$($Values[$k])" }
            $tmp = "$Path.int37tmp"
            [IO.File]::WriteAllLines($tmp, [string[]]@($lines), [Text.UTF8Encoding]::new($false))
            Move-Item -LiteralPath $tmp -Destination $Path -Force
            Set-Int37OwnerOnly $Path
        }
        Checkpoint = { param([string]$Step) }
        Log = { param([string]$Message) Write-Int37Ok $Message }
    }
}

function Merge-Int37Ops([hashtable]$Ops) {
    $all = Get-Int37RotationDefaultOps
    foreach ($k in $Ops.Keys) { $all[$k] = $Ops[$k] }
    foreach ($required in 'TestAdmin', 'TestUser', 'TestClient', 'OpenAdmin', 'SetUserPassword', 'SetClientSecret',
            'SetAdminPassword', 'WriteEnv', 'WriteJournal', 'Checkpoint', 'Log') {
        if (-not $all.ContainsKey($required)) { throw "Rotation operation $required is missing" }
    }
    return $all
}

<# Journal without any secret: what is kept after a rotation ends (completed, rolled back or recovered). #>
function Get-Int37SanitizedJournal([hashtable]$Journal, [string]$State, [string]$Detail) {
    $clean = @{}
    foreach ($k in $Journal.Keys) {
        $isSecret = $false
        foreach ($p in $script:SecretPrefixes) { if ($k.StartsWith($p)) { $isSecret = $true } }
        if (-not $isSecret) { $clean[$k] = $Journal[$k] }
    }
    $clean['STATE'] = $State
    $clean['FINISHED_AT'] = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    if ($Detail) { $clean['DETAIL'] = ($Detail -replace '[\r\n=]', ' ') }
    return $clean
}

<#
  Plan of a rotation: which keys, which users and which client. Keys:
    Users  : usernames whose password is $UserKey (development users)
    Client : client id whose secret is $ClientKey
    Admin  : $true to rotate the master admin password ($AdminKey)
#>
function New-Int37RotationPlan([string[]]$Users, [string]$Client, [bool]$Admin,
                               [string]$UserKey = 'KC_DEV_USER_PASSWORD', [string]$ClientKey = 'KC_REPORTS_CLIENT_SECRET',
                               [string]$AdminKey = 'KC_BOOTSTRAP_ADMIN_PASSWORD') {
    $keys = [System.Collections.Generic.List[string]]::new()
    if ($Users.Count -gt 0) { $keys.Add($UserKey) }
    if ($Client) { $keys.Add($ClientKey) }
    if ($Admin) { $keys.Add($AdminKey) }
    return @{ Users = @($Users); Client = $Client; Admin = $Admin; UserKey = $UserKey; ClientKey = $ClientKey
        AdminKey = $AdminKey; Keys = @($keys) }
}

function Invoke-Int37Rotation {
    param([Parameter(Mandatory = $true)][hashtable]$Plan,
          [Parameter(Mandatory = $true)][hashtable]$Old,      # current values (from the .env)
          [Parameter(Mandatory = $true)][hashtable]$New,      # new values for $Plan.Keys
          [Parameter(Mandatory = $true)][string]$AdminPassword,  # current master admin password
          [Parameter(Mandatory = $true)][string]$EnvFile,
          [Parameter(Mandatory = $true)][string]$JournalFile,
          [Parameter(Mandatory = $true)][hashtable]$Ops)
    $ops = Merge-Int37Ops $Ops
    if (Test-Int37RotationPending $JournalFile) {
        throw 'A previous rotation did not finish (pending journal). Run Update-Int37Credentials.ps1 -Recover first.'
    }
    foreach ($k in $Plan.Keys) {
        if (-not $Old[$k] -or -not $New[$k]) { throw "Rotation of $k needs its current and new value" }
        if ($Old[$k] -ceq $New[$k]) { throw "New value of $k equals the current one" }
    }

    # 0. preconditions: the .env must match the live Keycloak, otherwise nothing is touched
    if (-not (& $ops.TestAdmin $AdminPassword)) { throw 'Precondition: the master admin password of the .env does not authenticate' }
    foreach ($u in $Plan.Users) {
        if (-not (& $ops.TestUser $u $Old[$Plan.UserKey])) { throw "Precondition: user $u does not authenticate with the current $($Plan.UserKey)" }
    }
    if ($Plan.Client -and -not (& $ops.TestClient $Plan.Client $Old[$Plan.ClientKey])) {
        throw "Precondition: client $($Plan.Client) does not authenticate with the current $($Plan.ClientKey)"
    }

    # 1. journal
    $journal = @{ STATE = 'PREPARED'; TYPE = 'keycloak'; ROTATION_ID = [guid]::NewGuid().ToString()
        STARTED_AT = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'); ENV_FILE = $EnvFile
        TARGETS = ($Plan.Keys -join ','); USERS = ($Plan.Users -join ','); CLIENT = "$($Plan.Client)"
        USER_KEY = $Plan.UserKey; CLIENT_KEY = $Plan.ClientKey; ADMIN_KEY = $Plan.AdminKey; STEP = 'prepared' }
    foreach ($k in $Plan.Keys) { $journal["OLD_$k"] = $Old[$k]; $journal["NEW_$k"] = $New[$k] }
    if (-not $Plan.Admin) { $journal["OLD_$($Plan.AdminKey)"] = $AdminPassword }   # admin access for -Recover
    & $ops.WriteJournal $JournalFile $journal
    & $ops.Checkpoint 'prepared'

    $applied = [System.Collections.Generic.List[hashtable]]::new()   # recorded BEFORE each change (intent)
    $envWritten = $false
    $adminBefore = $null
    $step = 'open-admin'
    try {
        $session = & $ops.OpenAdmin $AdminPassword
        $journal['STATE'] = 'APPLYING'; & $ops.WriteJournal $JournalFile $journal

        # 2. development users
        foreach ($u in $Plan.Users) {
            $step = "user:$u"
            $applied.Add(@{ Kind = 'user'; Name = $u })
            & $ops.SetUserPassword $session $u $New[$Plan.UserKey]
            & $ops.Checkpoint $step
        }
        foreach ($u in $Plan.Users) {
            $step = "verify-user:$u"
            if (-not (& $ops.TestUser $u $New[$Plan.UserKey])) { throw "user $u does not authenticate with the new password" }
        }
        if ($Plan.Users.Count -gt 0) { & $ops.Log "$($Plan.UserKey): $($Plan.Users.Count) usuarios actualizados y verificados" }

        # 3. service client
        if ($Plan.Client) {
            $step = "client:$($Plan.Client)"
            $applied.Add(@{ Kind = 'client'; Name = $Plan.Client })
            & $ops.SetClientSecret $session $Plan.Client $New[$Plan.ClientKey]
            & $ops.Checkpoint $step
            $step = "verify-client:$($Plan.Client)"
            if (-not (& $ops.TestClient $Plan.Client $New[$Plan.ClientKey])) { throw "client $($Plan.Client) does not authenticate with the new secret" }
            & $ops.Log "$($Plan.ClientKey): secreto de $($Plan.Client) actualizado y verificado"
        }

        # 4. master admin, always last; a fresh session is kept to undo the change if the new password fails
        if ($Plan.Admin) {
            $step = 'admin'
            $adminBefore = & $ops.OpenAdmin $AdminPassword
            $applied.Add(@{ Kind = 'admin'; Name = 'admin' })
            & $ops.SetAdminPassword $adminBefore $New[$Plan.AdminKey]
            & $ops.Checkpoint $step
            $step = 'verify-admin'
            if (-not (& $ops.TestAdmin $New[$Plan.AdminKey])) { throw 'the master admin does not authenticate with the new password' }
            $session = & $ops.OpenAdmin $New[$Plan.AdminKey]
            & $ops.Log "$($Plan.AdminKey): administrador de master actualizado y verificado"
        }
        $journal['STATE'] = 'APPLIED'; $journal['STEP'] = 'applied'; & $ops.WriteJournal $JournalFile $journal
        & $ops.Checkpoint 'applied'

        # 5. .env in one atomic replacement, then verified
        $step = 'env'
        $values = @{}
        foreach ($k in $Plan.Keys) { $values[$k] = $New[$k] }
        $envWritten = $true
        & $ops.WriteEnv $EnvFile $values
        & $ops.Checkpoint 'env'
        $step = 'verify-env'
        $now = Read-Int37DotEnv $EnvFile
        foreach ($k in $Plan.Keys) { if ($now[$k] -cne $New[$k]) { throw "the .env does not contain the new $k after writing it" } }

        # 6. done: the journal keeps no secret
        & $ops.WriteJournal $JournalFile (Get-Int37SanitizedJournal $journal 'COMPLETED' '')
        & $ops.Log ".env actualizado ($($Plan.Keys -join ', ')); diario sin secretos"
        return @{ State = 'COMPLETED'; Keys = $Plan.Keys }
    } catch {
        if ($_.Exception -is [Int37SimulatedCrash]) { throw }
        $failure = "$step : $($_.Exception.Message)"
        try {
            Undo-Int37Rotation -Plan $Plan -Old $Old -New $New -AdminPassword $AdminPassword -Applied $applied `
                -EnvWritten $envWritten -AdminBefore $adminBefore -EnvFile $EnvFile -Ops $ops
        } catch {
            if ($_.Exception -is [Int37SimulatedCrash]) { throw }
            $journal['STATE'] = 'ROLLBACK_FAILED'; $journal['STEP'] = $step
            try { & $ops.WriteJournal $JournalFile $journal } catch { }
            throw "Rotation failed at $failure. The automatic rollback also failed ($($_.Exception.Message)): run Update-Int37Credentials.ps1 -Recover."
        }
        & $ops.WriteJournal $JournalFile (Get-Int37SanitizedJournal $journal 'ROLLED_BACK' $failure)
        throw "Rotation failed at $failure. Rolled back: Keycloak and the .env keep the previous values."
    }
}

<# Automatic rollback: admin first (so the rest can use the old admin password), then client, users and .env. #>
function Undo-Int37Rotation {
    param([hashtable]$Plan, [hashtable]$Old, [hashtable]$New, [string]$AdminPassword,
          [System.Collections.Generic.List[hashtable]]$Applied, [bool]$EnvWritten, $AdminBefore,
          [string]$EnvFile, [hashtable]$Ops)
    $kinds = @($Applied | ForEach-Object { $_.Kind })
    if ($kinds -contains 'admin') {
        $s = $null
        if (& $Ops.TestAdmin $New[$Plan.AdminKey]) { $s = & $Ops.OpenAdmin $New[$Plan.AdminKey] }
        elseif ($AdminBefore) { $s = $AdminBefore }
        if (-not (& $Ops.TestAdmin $AdminPassword)) {
            if (-not $s) { throw 'no administrative session left to restore the master admin password' }
            & $Ops.SetAdminPassword $s $AdminPassword
        }
        if (-not (& $Ops.TestAdmin $AdminPassword)) { throw 'the previous master admin password could not be restored' }
    }
    if ($Applied.Count -gt 0) {
        $session = & $Ops.OpenAdmin $AdminPassword
        for ($i = $Applied.Count - 1; $i -ge 0; $i--) {
            $a = $Applied[$i]
            # An intent is recorded BEFORE each change, so the change may never have happened (for example a request
            # refused with HTTP 500): only credentials that no longer accept the old value are written back.
            switch ($a.Kind) {
                'client' { if (-not (& $Ops.TestClient $a.Name $Old[$Plan.ClientKey])) { & $Ops.SetClientSecret $session $a.Name $Old[$Plan.ClientKey] } }
                'user' { if (-not (& $Ops.TestUser $a.Name $Old[$Plan.UserKey])) { & $Ops.SetUserPassword $session $a.Name $Old[$Plan.UserKey] } }
            }
        }
        foreach ($a in $Applied) {
            if ($a.Kind -eq 'user' -and -not (& $Ops.TestUser $a.Name $Old[$Plan.UserKey])) { throw "user $($a.Name) could not be restored" }
            if ($a.Kind -eq 'client' -and -not (& $Ops.TestClient $a.Name $Old[$Plan.ClientKey])) { throw "client $($a.Name) could not be restored" }
        }
    }
    if ($EnvWritten) {
        $values = @{}
        foreach ($k in $Plan.Keys) { $values[$k] = $Old[$k] }
        & $Ops.WriteEnv $EnvFile $values
    }
    & $Ops.Log 'Reversión automática completada: Keycloak y .env con los valores anteriores'
}

<#
  Recovery after an interrupted rotation (also when the process died before the automatic rollback).
  Goal: the .env decides. If the .env already holds every NEW value, Keycloak is completed to NEW (roll forward);
  otherwise Keycloak and the .env are put back to the OLD values (roll back). Each credential is checked with a real
  login before and after, so the procedure is safe to repeat.
#>
function Invoke-Int37RotationRecovery {
    param([Parameter(Mandatory = $true)][string]$JournalFile, [Parameter(Mandatory = $true)][hashtable]$Ops)
    $ops = Merge-Int37Ops $Ops
    $j = Read-Int37RotationJournal $JournalFile
    if (-not $j) { return @{ State = 'NOTHING'; Detail = 'no rotation journal' } }
    if ($script:FinalStates -contains $j['STATE']) { return @{ State = 'NOTHING'; Detail = "journal already $($j['STATE'])" } }
    if ($j.ContainsKey('TYPE') -and $j['TYPE'] -ne 'keycloak') { throw "The pending journal is a $($j['TYPE']) rotation: use its own recovery" }

    $keys = @("$($j['TARGETS'])".Split(',', [StringSplitOptions]::RemoveEmptyEntries))
    $users = @("$($j['USERS'])".Split(',', [StringSplitOptions]::RemoveEmptyEntries))
    $client = "$($j['CLIENT'])"
    $envFile = $j['ENV_FILE']
    $adminKey = $j['ADMIN_KEY']
    $env = Read-Int37DotEnv $envFile
    $allNew = @($keys | Where-Object { $env[$_] -cne $j["NEW_$_"] }).Count -eq 0
    $direction = if ($allNew) { 'NEW' } else { 'OLD' }
    $goal = @{}
    foreach ($k in $keys) { $goal[$k] = $j["${direction}_$k"] }

    # administrative access: whichever admin password still works
    $adminCandidates = @()
    if ($keys -contains $adminKey) { $adminCandidates = @($goal[$adminKey], $j["NEW_$adminKey"], $j["OLD_$adminKey"]) }
    else { $adminCandidates = @($env[$adminKey], $j["OLD_$adminKey"]) }
    $working = $null
    foreach ($c in ($adminCandidates | Where-Object { $_ } | Select-Object -Unique)) { if (& $ops.TestAdmin $c) { $working = $c; break } }
    if (-not $working) { throw 'No administrative access with any known admin password: follow the manual procedure (README, "Recuperación").' }
    $session = & $ops.OpenAdmin $working
    if ($keys -contains $adminKey -and $working -cne $goal[$adminKey]) {
        & $ops.SetAdminPassword $session $goal[$adminKey]
        if (-not (& $ops.TestAdmin $goal[$adminKey])) { throw 'the master admin password could not be set to the recovery goal' }
        $session = & $ops.OpenAdmin $goal[$adminKey]
    }
    $userKey = $j['USER_KEY']; $clientKey = $j['CLIENT_KEY']
    foreach ($u in $users) {
        if (-not (& $ops.TestUser $u $goal[$userKey])) { & $ops.SetUserPassword $session $u $goal[$userKey] }
        if (-not (& $ops.TestUser $u $goal[$userKey])) { throw "user $u could not be recovered" }
    }
    if ($client) {
        if (-not (& $ops.TestClient $client $goal[$clientKey])) { & $ops.SetClientSecret $session $client $goal[$clientKey] }
        if (-not (& $ops.TestClient $client $goal[$clientKey])) { throw "client $client could not be recovered" }
    }
    $needsEnv = @($keys | Where-Object { $env[$_] -cne $goal[$_] }).Count -gt 0
    if ($needsEnv) { & $ops.WriteEnv $envFile $goal }
    $check = Read-Int37DotEnv $envFile
    foreach ($k in $keys) { if ($check[$k] -cne $goal[$k]) { throw "the .env does not contain the recovered $k" } }
    & $ops.WriteJournal $JournalFile (Get-Int37SanitizedJournal $j 'RECOVERED' "recovered to $direction values")
    & $ops.Log "Recuperación completada: valores $(if ($direction -eq 'NEW') { 'nuevos' } else { 'anteriores' }) en Keycloak y .env"
    return @{ State = 'RECOVERED'; Direction = $direction; Keys = $keys }
}


# ================================================================================================ PostgreSQL (AUT-1b)

<#
  SCRAM-SHA-256 verifier of a password, exactly as PostgreSQL stores it (RFC 5802/7677, PostgreSQL format
  "SCRAM-SHA-256$<iterations>:<salt>$<StoredKey>:<ServerKey>"). ALTER ROLE ... PASSWORD '<verifier>' sets the
  password WITHOUT the server ever receiving it in clear text (nothing to leak into a log, pg_stat_activity or an
  error message). Only printable ASCII is accepted (SASLprep is then the identity); generated secrets are alnum.
#>
function ConvertTo-Int37ScramVerifier([string]$Password, [byte[]]$Salt, [int]$Iterations = 4096) {
    if (-not $Password -or $Password -notmatch '^[\x21-\x7E]+$') { throw 'SCRAM: only printable ASCII passwords are supported' }
    if (-not $Salt) { $Salt = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(16) }
    $salted = [System.Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2([Text.Encoding]::UTF8.GetBytes($Password), $Salt,
        $Iterations, [System.Security.Cryptography.HashAlgorithmName]::SHA256, 32)
    $hmac = [System.Security.Cryptography.HMACSHA256]::new($salted)
    try {
        $clientKey = $hmac.ComputeHash([Text.Encoding]::ASCII.GetBytes('Client Key'))
        $serverKey = $hmac.ComputeHash([Text.Encoding]::ASCII.GetBytes('Server Key'))
    } finally { $hmac.Dispose() }
    $storedKey = [System.Security.Cryptography.SHA256]::HashData($clientKey)
    return 'SCRAM-SHA-256$' + $Iterations + ':' + [Convert]::ToBase64String($Salt) + '$' +
        [Convert]::ToBase64String($storedKey) + ':' + [Convert]::ToBase64String($serverKey)
}

<#
  Plan of a PostgreSQL rotation in ONE container and ONE .env. Items: @{ Key; Role; Database; Superuser }.
  Application and owner roles first, the container's superuser LAST.
#>
function New-Int37PgPlan([string]$Target, [string]$Container, [string]$Superuser, [object[]]$Items) {
    foreach ($i in $Items) {
        if ($i.Role -notmatch '^[a-z_][a-z0-9_]{0,62}$' -or $i.Database -notmatch '^[a-z_][a-z0-9_]{0,62}$') { throw "Invalid role or database name in the plan ($($i.Key))" }
    }
    $ordered = @($Items | Where-Object { -not $_.Superuser }) + @($Items | Where-Object { $_.Superuser })
    if (@($Items | Where-Object { $_.Superuser }).Count -gt 1) { throw 'Only one superuser per plan' }
    return @{ Target = $Target; Container = $Container; Superuser = $Superuser; Items = $ordered; Keys = @($ordered | ForEach-Object { $_.Key }) }
}

function Merge-Int37PgOps([hashtable]$Ops) {
    $all = Get-Int37RotationDefaultOps
    foreach ($k in $Ops.Keys) { $all[$k] = $Ops[$k] }
    foreach ($required in 'TestLocal', 'TestLogin', 'SetPassword', 'WriteEnv', 'WriteJournal', 'Checkpoint', 'Log') {
        if (-not $all.ContainsKey($required)) { throw "PostgreSQL rotation operation $required is missing" }
    }
    return $all
}

<#
  Preconditions shared by rotation and recovery:
  - passwordless administrative access through the container's LOCAL socket (the recovery path), as the superuser;
  - the TCP path used to verify passwords really checks them: a deliberately wrong password must be refused.
#>
function Assert-Int37PgAccess([hashtable]$Plan, [hashtable]$Ops) {
    $local = & $Ops.TestLocal
    if (-not $local.Ok) { throw "Precondition: no passwordless superuser access through the local socket of $($Plan.Container) ($($local.Detail))" }
    # every role is probed: a pg_hba rule may trust a single user or database
    foreach ($probe in $Plan.Items) {
        if (& $Ops.TestLogin $probe.Role $probe.Database ('wrong-' + (New-Int37Secret 24))) {
            throw "Precondition: the verification path of $($Plan.Container) accepts a wrong password for $($probe.Role) (trust authentication): password checks would be meaningless"
        }
    }
    return $local
}

function Invoke-Int37PgRotation {
    param([Parameter(Mandatory = $true)][hashtable]$Plan, [Parameter(Mandatory = $true)][hashtable]$Old,
          [Parameter(Mandatory = $true)][hashtable]$New, [Parameter(Mandatory = $true)][string]$EnvFile,
          [Parameter(Mandatory = $true)][string]$JournalFile, [Parameter(Mandatory = $true)][hashtable]$Ops)
    $ops = Merge-Int37PgOps $Ops
    if (Test-Int37RotationPending $JournalFile) {
        throw 'A previous rotation did not finish (pending journal). Run Update-Int37Credentials.ps1 -Recover first.'
    }
    foreach ($k in $Plan.Keys) {
        if (-not $Old[$k] -or -not $New[$k]) { throw "Rotation of $k needs its current and new value" }
        if ($Old[$k] -ceq $New[$k]) { throw "New value of $k equals the current one" }
    }
    # 0. preconditions: recovery path available, verification meaningful, every .env value authenticates
    $null = Assert-Int37PgAccess $Plan $ops
    foreach ($i in $Plan.Items) {
        if (-not (& $ops.TestLogin $i.Role $i.Database $Old[$i.Key])) { throw "Precondition: role $($i.Role) does not authenticate with the current $($i.Key)" }
    }

    # 1. journal
    $journal = @{ STATE = 'PREPARED'; TYPE = 'postgres'; ROTATION_ID = [guid]::NewGuid().ToString()
        STARTED_AT = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'); ENV_FILE = $EnvFile
        TARGET = $Plan.Target; CONTAINER = $Plan.Container; SUPERUSER = $Plan.Superuser; TARGETS = ($Plan.Keys -join ',')
        ITEMS = (@($Plan.Items | ForEach-Object { "$($_.Key):$($_.Role):$($_.Database):$([int][bool]$_.Superuser)" }) -join ','); STEP = 'prepared' }
    foreach ($k in $Plan.Keys) { $journal["OLD_$k"] = $Old[$k]; $journal["NEW_$k"] = $New[$k] }
    & $ops.WriteJournal $JournalFile $journal
    & $ops.Checkpoint 'prepared'

    $applied = [System.Collections.Generic.List[hashtable]]::new()
    $envWritten = $false
    $step = 'apply'
    try {
        $journal['STATE'] = 'APPLYING'; & $ops.WriteJournal $JournalFile $journal
        # 2. roles: application and owner first, superuser last; each verified with a real TCP login
        foreach ($i in $Plan.Items) {
            $step = "role:$($i.Role)"
            $applied.Add($i)
            & $ops.SetPassword $i.Role $New[$i.Key]
            & $ops.Checkpoint $step
            $step = "verify-role:$($i.Role)"
            if (-not (& $ops.TestLogin $i.Role $i.Database $New[$i.Key])) { throw "role $($i.Role) does not authenticate with the new password" }
            & $ops.Log "$($i.Key): rol $($i.Role) actualizado y verificado por TCP"
        }
        $journal['STATE'] = 'APPLIED'; $journal['STEP'] = 'applied'; & $ops.WriteJournal $JournalFile $journal
        & $ops.Checkpoint 'applied'

        # 3. .env in one atomic replacement, then verified
        $step = 'env'
        $values = @{}
        foreach ($k in $Plan.Keys) { $values[$k] = $New[$k] }
        $envWritten = $true
        & $ops.WriteEnv $EnvFile $values
        & $ops.Checkpoint 'env'
        $step = 'verify-env'
        $now = Read-Int37DotEnv $EnvFile
        foreach ($k in $Plan.Keys) { if ($now[$k] -cne $New[$k]) { throw "the .env does not contain the new $k after writing it" } }

        & $ops.WriteJournal $JournalFile (Get-Int37SanitizedJournal $journal 'COMPLETED' '')
        & $ops.Log ".env actualizado ($($Plan.Keys -join ', ')); diario sin secretos"
        return @{ State = 'COMPLETED'; Keys = $Plan.Keys }
    } catch {
        if ($_.Exception -is [Int37SimulatedCrash]) { throw }
        $failure = "$step : $($_.Exception.Message)"
        try {
            for ($n = $applied.Count - 1; $n -ge 0; $n--) {
                $i = $applied[$n]
                if (-not (& $ops.TestLogin $i.Role $i.Database $Old[$i.Key])) { & $ops.SetPassword $i.Role $Old[$i.Key] }
            }
            foreach ($i in $applied) {
                if (-not (& $ops.TestLogin $i.Role $i.Database $Old[$i.Key])) { throw "role $($i.Role) could not be restored" }
            }
            if ($envWritten) {
                $values = @{}
                foreach ($k in $Plan.Keys) { $values[$k] = $Old[$k] }
                & $ops.WriteEnv $EnvFile $values
            }
            & $ops.Log 'Reversión automática completada: roles y .env con los valores anteriores'
        } catch {
            if ($_.Exception -is [Int37SimulatedCrash]) { throw }
            $journal['STATE'] = 'ROLLBACK_FAILED'; $journal['STEP'] = $step
            try { & $ops.WriteJournal $JournalFile $journal } catch { }
            throw "Rotation failed at $failure. The automatic rollback also failed ($($_.Exception.Message)): run Update-Int37Credentials.ps1 -Recover."
        }
        & $ops.WriteJournal $JournalFile (Get-Int37SanitizedJournal $journal 'ROLLED_BACK' $failure)
        throw "Rotation failed at $failure. Rolled back: the roles and the .env keep the previous values."
    }
}

<# Plan of the PostgreSQL rotation recorded in a journal (for -Recover). #>
function Get-Int37PgPlanFromJournal([hashtable]$Journal) {
    $items = foreach ($entry in "$($Journal['ITEMS'])".Split(',', [StringSplitOptions]::RemoveEmptyEntries)) {
        $p = $entry.Split(':')
        @{ Key = $p[0]; Role = $p[1]; Database = $p[2]; Superuser = ($p[3] -eq '1') }
    }
    return New-Int37PgPlan -Target $Journal['TARGET'] -Container $Journal['CONTAINER'] -Superuser $Journal['SUPERUSER'] -Items @($items)
}

<#
  Recovery of an interrupted PostgreSQL rotation. The .env decides: every NEW value present -> roles completed to NEW;
  otherwise roles and .env back to OLD. Uses only the passwordless local superuser access (checked first) and
  verifies each role with a real TCP login. No journal -> nothing is done (manual procedure in the README).
#>
function Invoke-Int37PgRecovery {
    param([Parameter(Mandatory = $true)][string]$JournalFile, [Parameter(Mandatory = $true)][hashtable]$Ops)
    $ops = Merge-Int37PgOps $Ops
    $j = Read-Int37RotationJournal $JournalFile
    if (-not $j) { return @{ State = 'NOTHING'; Detail = 'no rotation journal' } }
    if ($script:FinalStates -contains $j['STATE']) { return @{ State = 'NOTHING'; Detail = "journal already $($j['STATE'])" } }
    if ($j['TYPE'] -ne 'postgres') { throw "The pending journal is a $($j['TYPE']) rotation: use its own recovery" }
    $plan = Get-Int37PgPlanFromJournal $j
    $null = Assert-Int37PgAccess $plan $ops
    $env = Read-Int37DotEnv $j['ENV_FILE']
    $allNew = @($plan.Keys | Where-Object { $env[$_] -cne $j["NEW_$_"] }).Count -eq 0
    $direction = if ($allNew) { 'NEW' } else { 'OLD' }
    $goal = @{}
    foreach ($k in $plan.Keys) { $goal[$k] = $j["${direction}_$k"] }
    foreach ($i in $plan.Items) {
        if (-not (& $ops.TestLogin $i.Role $i.Database $goal[$i.Key])) { & $ops.SetPassword $i.Role $goal[$i.Key] }
        if (-not (& $ops.TestLogin $i.Role $i.Database $goal[$i.Key])) { throw "role $($i.Role) could not be recovered" }
    }
    if (@($plan.Keys | Where-Object { $env[$_] -cne $goal[$_] }).Count -gt 0) { & $ops.WriteEnv $j['ENV_FILE'] $goal }
    $check = Read-Int37DotEnv $j['ENV_FILE']
    foreach ($k in $plan.Keys) { if ($check[$k] -cne $goal[$k]) { throw "the .env does not contain the recovered $k" } }
    & $ops.WriteJournal $JournalFile (Get-Int37SanitizedJournal $j 'RECOVERED' "recovered to $direction values")
    & $ops.Log "Recuperación completada ($($plan.Target)): valores $(if ($direction -eq 'NEW') { 'nuevos' } else { 'anteriores' }) en los roles y el .env"
    return @{ State = 'RECOVERED'; Direction = $direction; Keys = $plan.Keys }
}

Export-ModuleMember -Function *-Int37*
