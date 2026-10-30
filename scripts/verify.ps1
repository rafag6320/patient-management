param([switch]$WithContexts)
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/check-contracts.ps1"
$modules = 'patient-service','auth-service','billing-service','analytics-service','api-gateway'
foreach ($module in $modules) {
    $arguments = @('clean','verify')
    if ($WithContexts) { $arguments += '-Pisolated-tests' }
    & "$PSScriptRoot/maven.ps1" -Module $module @arguments
}
# Los tests HTTP externos quedan excluidos en la ejecución normal.
& "$PSScriptRoot/maven.ps1" -Module integration-tests clean verify
