$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
if ([string]::IsNullOrWhiteSpace($env:AIMANAGE_DB_PASSWORD)) {
    throw 'Set AIMANAGE_DB_PASSWORD to the actual MySQL password before starting.'
}
$jar = Join-Path $projectRoot 'target/aimanage-prototype-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw 'Build the project with Maven before starting.'
}
$javaTemp = Join-Path $projectRoot 'target/java-tmp'
New-Item -ItemType Directory -Path $javaTemp -Force | Out-Null
$java = (Get-Command java.exe -ErrorAction Stop).Source
$javaArgs = @(
    "-Djdk.net.unixdomain.tmpdir=$javaTemp",
    "-Djava.io.tmpdir=$javaTemp",
    '-jar', 'target/aimanage-prototype-0.0.1-SNAPSHOT.jar',
    '--spring.profiles.active=mysql'
)
$appProcess = Start-Process -FilePath $java -ArgumentList $javaArgs -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput 'target/app.stdout.log' -RedirectStandardError 'target/app.stderr.log' -PassThru
Write-Output "Application PID: $($appProcess.Id); URL: http://localhost:8080"
