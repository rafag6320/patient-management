$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
foreach($pair in @(@('patient-service/src/main/proto/billing_service.proto','billing-service/src/main/proto/billing_service.proto'),@('patient-service/src/main/proto/patient_event.proto','analytics-service/src/main/proto/patient_event.proto'))) {
    $left=[IO.File]::ReadAllText((Join-Path $root $pair[0])).Replace("`r`n","`n")
    $right=[IO.File]::ReadAllText((Join-Path $root $pair[1])).Replace("`r`n","`n")
    if($left -cne $right){throw "Contratos divergentes: $($pair -join ' / ')"}
}
Write-Output 'OK: contratos Protobuf de billing y eventos idénticos entre servicios.'
