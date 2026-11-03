#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$files=@(& git -C $root ls-files --cached --others --exclude-standard)
if($LASTEXITCODE -ne 0){throw 'No se pudo enumerar el checkout.'}
$failures=[Collections.Generic.List[string]]::new()
foreach($relative in $files) {
    $path=Join-Path $root $relative
    if($relative.EndsWith('.ps1')) {
        $tokens=$null;$errors=$null
        $null=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
        foreach($error in $errors){$failures.Add("Sintaxis PowerShell: $relative línea $($error.Extent.StartLineNumber)")}
    }
    if($relative -match '/static/openapi/[^/]+\.json$') {
        try {
            $json=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
            if($json.openapi -ne '3.0.3' -or !$json.paths.Count){throw 'Catálogo incompleto'}
        } catch {$failures.Add("JSON OpenAPI inválido: $relative")}
    }
    if($relative.EndsWith('.md')) {
        $markdown=Get-Content -LiteralPath $path -Raw
        foreach($match in [regex]::Matches($markdown,'\[[^\]\r\n]*\]\(([^)\r\n]+)\)')) {
            $target=$match.Groups[1].Value.Trim('<','>')
            if($target -match '^(https?://|mailto:|#)'){continue}
            $target=[Uri]::UnescapeDataString(($target -split '[#?]',2)[0])
            if($target -and !(Test-Path -LiteralPath (Join-Path (Split-Path $path -Parent) $target))){$failures.Add("Enlace local roto: $relative -> $target")}
        }
        $fences=[regex]::Matches($markdown,'(?m)^```mermaid\s*$').Count
        $diagrams=[regex]::Matches($markdown,'(?ms)^```mermaid\s*\r?\n(.*?)^```\s*$')
        if($fences -ne $diagrams.Count){$failures.Add("Bloque Mermaid sin cerrar: $relative")}
        foreach($diagram in $diagrams){if($diagram.Groups[1].Value -notmatch '^\s*(flowchart|sequenceDiagram)\b'){$failures.Add("Tipo de diagrama no revisado: $relative")}}
    }
}
if($failures.Count){$failures | ForEach-Object {Write-Output $_};throw 'Falló la validación de documentación y scripts.'}
Write-Output 'OK: sintaxis PowerShell, JSON OpenAPI, enlaces locales y bloques Mermaid. Los diagramas requieren además revisión de su renderizado.'
