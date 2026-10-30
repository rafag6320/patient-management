$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$template=Join-Path $projectRoot '.verification/infrastructure/cdk.out/patient-management-emulation.template.json'
if(!(Test-Path $template)){throw 'Ejecutar scripts/synth.ps1 antes de emular.'}
$name='pm-emulation-'+[guid]::NewGuid().ToString('N').Substring(0,8)
try {
    & docker run -d --name $name -p 127.0.0.1::4566 -e SERVICES=cloudformation,secretsmanager -e EAGER_SERVICE_LOADING=1 localstack/localstack:4.14.0
    if($LASTEXITCODE -ne 0){throw 'No se pudo crear LocalStack aislado.'}
    $ready=$false
    for($i=0;$i -lt 60;$i++) {
        & docker exec $name awslocal secretsmanager list-secrets *> $null
        if($LASTEXITCODE -eq 0){$ready=$true;break};Start-Sleep -Seconds 1
    }
    if(!$ready){throw 'LocalStack no disponible; revisar edición/imagen.'}
    & docker cp $template "${name}:/tmp/emulation.template.json"
    if($LASTEXITCODE -ne 0){throw 'No se pudo copiar el template local.'}
    & docker exec $name awslocal cloudformation deploy --stack-name $name --template-file /tmp/emulation.template.json
    if($LASTEXITCODE -ne 0){throw 'Falló CloudFormation local.'}
    $arn=(& docker exec $name awslocal cloudformation describe-stacks --stack-name $name --query 'Stacks[0].Outputs[0].OutputValue' --output text).Trim()
    if($LASTEXITCODE -ne 0){throw 'No se pudo inspeccionar el resultado local.'}
    $value=(& docker exec $name awslocal secretsmanager get-secret-value --secret-id $arn --query SecretString --output text).Trim()
    if($LASTEXITCODE -ne 0 -or $value -ne 'synthetic-emulation-only'){throw 'El secreto sintético no coincide.'}
    Write-Output 'OK: LocalStack 4.14.0, CloudFormation y Secrets Manager; RDS/MSK/ECS no se presentan como emulados.'
} finally {
    & docker logs $name *> "$projectRoot/.verification/localstack-emulation.log"
    & docker rm -f $name
}
