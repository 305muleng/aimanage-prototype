param(
    [switch]$Check,
    [switch]$Build,
    [switch]$Test,
    [switch]$Rag,
    [string]$Question
)

$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding

# 默认使用 DeepSeek 官方 Chat Completions 接口，可通过环境变量覆盖。
$modelDefaults = @{
    AI_BASE_URL = 'https://api.deepseek.com'
    AI_CHAT_PATH = '/chat/completions'
    AI_MODEL = 'deepseek-flash'
    EMBEDDING_BASE_URL = 'https://api.siliconflow.cn'
    EMBEDDING_PATH = '/v1/embeddings'
    EMBEDDING_MODEL = 'BAAI/bge-m3'
}
foreach ($name in $modelDefaults.Keys) {
    if (-not [Environment]::GetEnvironmentVariable($name)) {
        [Environment]::SetEnvironmentVariable($name, $modelDefaults[$name], 'Process')
    }
}
if (-not $env:AI_API_KEY -and $env:DEEPSEEK_API_KEY) {
    $env:AI_API_KEY = $env:DEEPSEEK_API_KEY
}
$ragRequested = $Rag -or ($env:RAG_ENABLED -eq 'true')

# 只影响当前 PowerShell 进程，不改系统 JAVA_HOME 或 PATH。
$jdkCandidates = @($env:AI_JAVA_HOME, $env:JAVA_HOME, 'D:\Java_JDK\jdk-17')
$selectedJdk = $null
foreach ($candidate in $jdkCandidates) {
    if (-not $candidate) { continue }
    $releaseFile = Join-Path $candidate 'release'
    if (Test-Path -LiteralPath $releaseFile) {
        $releaseText = Get-Content -LiteralPath $releaseFile -Raw
        if ($releaseText -match 'JAVA_VERSION="(\d+)') {
            if ([int]$Matches[1] -ge 17 -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))) {
                $selectedJdk = $candidate
                break
            }
        }
    }
}
if (-not $selectedJdk) { throw '需要 JDK 17 或以上，请设置 AI_JAVA_HOME 为 JDK 安装目录。' }
$env:JAVA_HOME = $selectedJdk
$env:MAVEN_OPTS = ($env:MAVEN_OPTS + ' -Dfile.encoding=UTF-8').Trim()
$javaExe = Join-Path $selectedJdk 'bin\java.exe'

$mavenExe = $null
if ($env:AI_MAVEN_HOME) {
    $candidate = Join-Path $env:AI_MAVEN_HOME 'bin\mvn.cmd'
    if (Test-Path -LiteralPath $candidate) { $mavenExe = $candidate }
}
if (-not $mavenExe) {
    $wrapperCache = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2\wrapper\dists'
    if (Test-Path -LiteralPath $wrapperCache) {
        $cachedMaven = Get-ChildItem -LiteralPath $wrapperCache -Recurse -File -Filter mvn.cmd |
            Where-Object { $_.FullName -match 'apache-maven-3\.9\.' } |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($cachedMaven) { $mavenExe = $cachedMaven.FullName }
    }
}
if (-not $mavenExe) {
    $pathMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($pathMaven) { $mavenExe = $pathMaven.Source }
}
if (-not $mavenExe) { throw '需要 Maven 3.6.3 或以上，请设置 AI_MAVEN_HOME。' }

Write-Host ('JDK: ' + $selectedJdk)
Write-Host ('Maven: ' + $mavenExe)
if ($Check) {
    & $javaExe -version
    & $mavenExe -version
    $keyState = if ($env:AI_API_KEY) { 'configured' } else { 'missing' }
    Write-Host ('API Key: ' + $keyState)
    Write-Host ('Model: ' + $env:AI_MODEL)
    Write-Host ('Endpoint: ' + $env:AI_BASE_URL.TrimEnd('/') + $env:AI_CHAT_PATH)
    Write-Host ('RAG requested: ' + $ragRequested)
    Write-Host ('Embedding model: ' + $env:EMBEDDING_MODEL)
    Write-Host ('Embedding endpoint: ' + $env:EMBEDDING_BASE_URL.TrimEnd('/') + $env:EMBEDDING_PATH)
    $embeddingKeyState = if ($env:EMBEDDING_API_KEY) { 'configured' } else { 'missing' }
    Write-Host ('Embedding API Key: ' + $embeddingKeyState)
    exit 0
}

Push-Location $PSScriptRoot
try {
    $repository = Join-Path $PSScriptRoot '.build\repository'
    $buildGoal = if ($Test) { 'test' } else { 'package' }
    & $mavenExe --batch-mode --no-transfer-progress -s '.mvn/settings.xml' "-Dmaven.repo.local=$repository" $buildGoal
    if ($LASTEXITCODE -ne 0) { throw ('构建失败，Maven 退出码：' + $LASTEXITCODE) }
    if ($Build -or $Test) { exit 0 }
    if (-not $env:AI_API_KEY) {
        $modelSecret = Read-Host '请在本机输入 DeepSeek API Key（隐藏输入）' -AsSecureString
        try {
            $env:AI_API_KEY = [System.Net.NetworkCredential]::new('', $modelSecret).Password
        } finally {
            $modelSecret.Dispose()
            Remove-Variable modelSecret
        }
        if ([string]::IsNullOrWhiteSpace($env:AI_API_KEY)) { throw 'API Key 不能为空。' }
    }
    $javaArgs = @('-Dfile.encoding=UTF-8', '-jar', 'target/aiguanli-ai-0.0.1-SNAPSHOT.jar')
    if ($ragRequested) {
        if (-not $env:EMBEDDING_API_KEY) {
            $embeddingSecret = Read-Host '请在本机输入向量模型平台的 API Key（默认硅基流动，隐藏输入）' -AsSecureString
            try {
                $env:EMBEDDING_API_KEY = [System.Net.NetworkCredential]::new('', $embeddingSecret).Password
            } finally {
                $embeddingSecret.Dispose()
                Remove-Variable embeddingSecret
            }
            if ([string]::IsNullOrWhiteSpace($env:EMBEDDING_API_KEY)) { throw '向量模型 API Key 不能为空。' }
        }
        $javaArgs += '--app.rag.enabled=true'
    }
    if ($Question) { $javaArgs += ('--question=' + $Question) }
    & $javaExe @javaArgs
    if ($LASTEXITCODE -ne 0) { throw ('程序退出码：' + $LASTEXITCODE) }
} finally {
    Pop-Location
}
