#requires -Version 7.0
param([Parameter(Mandatory)][uri]$GatewayUri)
$ErrorActionPreference='Stop'
if($GatewayUri.Scheme -ne 'http' -or $GatewayUri.Host -notin @('127.0.0.1','localhost') -or
   $GatewayUri.Port -notin @(34004,44004) -or $GatewayUri.UserInfo -or $GatewayUri.Query -or
   $GatewayUri.Fragment -or $GatewayUri.AbsolutePath -ne '/') {
    throw 'Functional readiness requires an explicit isolated loopback gateway.'
}
$origin=$GatewayUri.GetLeftPart([UriPartial]::Authority)
$deadline=[DateTime]::UtcNow.AddSeconds(90)
$attempts=0
do {
    $attempts++
    try {
        $login=Invoke-WebRequest "$origin/api/auth/login" -Method Post -ContentType 'application/json' `
            -Body '{"email":"testuser@test.com","password":"password123"}' -SkipHttpErrorCheck `
            -TimeoutSec 12 -MaximumRedirection 0 -Verbose:$false
    } catch {throw 'Readiness login transport failed; no request or credential was logged.'}
    if([int]$login.StatusCode -eq 200) {
        $token=($login.Content | ConvertFrom-Json).token
        if(!$token){throw 'Readiness login returned no token.'}
        try {
            $patients=Invoke-WebRequest "$origin/api/patients" -Headers @{Authorization="Bearer $token"} `
                -SkipHttpErrorCheck -TimeoutSec 12 -MaximumRedirection 0 -Verbose:$false
        } catch {throw 'Readiness patient transport failed; no token was logged.'}
        finally {$token=$null;$login=$null}
        if([int]$patients.StatusCode -eq 200) {
            Write-Output "OK: gateway/auth functional readiness after $attempts probe(s); scenario assertions remain strict."
            return
        }
        if([int]$patients.StatusCode -notin @(502,503)){throw "Unexpected patient readiness HTTP $([int]$patients.StatusCode)."}
    } elseif([int]$login.StatusCode -notin @(502,503)) {
        throw "Unexpected login readiness HTTP $([int]$login.StatusCode)."
    }
    Start-Sleep -Milliseconds 500
} while([DateTime]::UtcNow -lt $deadline)
throw 'Functional gateway/auth readiness timed out; scenario tests were not started.'
