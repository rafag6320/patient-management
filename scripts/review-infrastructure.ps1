param([string]$BaselineTemplate)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$template=Join-Path $root '.verification/infrastructure/cdk.out/patient-management-candidate.template.json'
if(!(Test-Path $template)){throw 'Ejecutar scripts/synth.ps1 primero.'}
$data=Get-Content $template -Raw | ConvertFrom-Json -AsHashtable
$counts=$data.Resources.Values | Group-Object Type | Sort-Object Name | ForEach-Object {"$($_.Name): $($_.Count)"}
$report=@('AWS candidate: revisión local, sin consultar ni modificar AWS.','',"SHA256: $((Get-FileHash $template).Hash)",'','Recursos:')+$counts+@('','Parámetros requeridos:')+@($data.Parameters.Keys | Sort-Object)
if($BaselineTemplate) {
    if(!(Test-Path -LiteralPath $BaselineTemplate)){throw 'Baseline no encontrado.'}
    $difference=& git diff --no-index -- $BaselineTemplate $template
    if($LASTEXITCODE -gt 1){throw 'No se pudo comparar el template.'}
    $difference | Set-Content (Join-Path $root '.verification/infrastructure/review.diff') -Encoding utf8
    $report+='Comparación local con baseline suministrado: review.diff'
} else {
    $report+='Sin baseline desplegado: el template se presenta como nueva infraestructura. El diff contra AWS debe ejecutarse tras elegir cuenta/región y antes de desplegar.'
}
$report | Set-Content (Join-Path $root '.verification/infrastructure/review.txt') -Encoding utf8
$report
