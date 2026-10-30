$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$output=Join-Path $projectRoot '.verification/infrastructure/cdk.out'
& "$PSScriptRoot/maven.ps1" -Module infrastructure verify exec:java "-Dpm.synth.directory=$output"
if(!(Test-Path (Join-Path $output 'patient-management-candidate.template.json'))){throw 'Template AWS no generado.'}
Write-Output "OK: síntesis local para revisión en $output; no se ejecutó ningún despliegue."
