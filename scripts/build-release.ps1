#requires -Version 7.0
param([Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}$')][string]$Version)
$ErrorActionPreference='Stop'
if($Version -eq 'latest'){throw 'Usar una versión explícita, nunca latest.'}
$root=Split-Path $PSScriptRoot -Parent
$output=Join-Path $root '.verification/release';New-Item -ItemType Directory -Force $output | Out-Null
$revision=(& git -C $root rev-parse HEAD).Trim();if($LASTEXITCODE -ne 0){throw 'Se requiere referencia Git.'}
$dirty=!!(& git -C $root status --porcelain)
$files=& git -C $root ls-files --cached --others --exclude-standard
$context=Join-Path $output ('context-'+[guid]::NewGuid().ToString('N').Substring(0,8));New-Item -ItemType Directory -Force $context | Out-Null
$hasher=[Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
try {
    foreach($file in ($files | Sort-Object -Unique)) {
        if($file -notmatch '^((patient-service|auth-service|billing-service|analytics-service|api-gateway)/(src/|pom\.xml$|Dockerfile$)|docker/ReadinessCheck\.java$|docker/certs/global-bundle\.pem$|\.dockerignore$)'){continue}
        $path=Join-Path $root $file
        if(Test-Path -LiteralPath $path -PathType Leaf) {
            $content=[IO.File]::ReadAllBytes($path)
            $snapshot=Join-Path $context $file;New-Item -ItemType Directory -Force (Split-Path $snapshot -Parent) | Out-Null;[IO.File]::WriteAllBytes($snapshot,$content)
            $hasher.AppendData([Text.Encoding]::UTF8.GetBytes($file+"`0"+$content.Length+"`0"));$hasher.AppendData($content)
        }
    }
    $sourceHash=[Convert]::ToHexString($hasher.GetHashAndReset()).ToLowerInvariant()
} finally {$hasher.Dispose()}
$images=@()
foreach($name in @('patient-service','auth-service','billing-service','analytics-service','api-gateway')) {
    $tag="patient-management/${name}:$Version"
    & docker build -f (Join-Path $context "$name/Dockerfile") -t $tag --label "org.opencontainers.image.revision=$revision" --label "pm.source.sha256=$sourceHash" $context
    if($LASTEXITCODE -ne 0){throw "Falló imagen $name"}
    $imageId=(& docker image inspect $tag --format '{{.Id}}').Trim()
    if($LASTEXITCODE -ne 0){throw "No se pudo verificar imagen $name"}
    $images+=@{service=$name;localTag=$tag;imageId=$imageId}
}
@{version=$Version;gitRevision=$revision;workingTreeDirty=$dirty;sourceSha256=$sourceHash;images=$images;published=$false} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'release.json') -Encoding utf8
Write-Output "OK: cinco imágenes locales versionadas y manifiesto en $output; sin publicar."
