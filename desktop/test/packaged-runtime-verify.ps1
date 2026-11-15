param(
    [string]$DataDir = "",
    [int]$Port = 0
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$DesktopDir = Split-Path -Parent $PSScriptRoot
$Java = Join-Path $DesktopDir "release\win-unpacked\resources\jre\bin\java.exe"
$Jar = Join-Path $DesktopDir "release\win-unpacked\resources\app\bearcoding-backend.jar"
$FrontendScript = Join-Path $PSScriptRoot "frontend-smoke.cjs"
if (-not (Test-Path -LiteralPath $Java)) { throw "缺少打包 JRE：$Java" }
if (-not (Test-Path -LiteralPath $Jar)) { throw "缺少打包 JAR：$Jar" }

$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $DataDir) {
    $DataDir = Join-Path $DesktopDir "test-user-data\phaseb-packaged-runtime-$Stamp"
}
$DataDir = [System.IO.Path]::GetFullPath($DataDir)
$LogDir = Join-Path $DataDir "logs"
$StdoutLog = Join-Path $DataDir "java.stdout.log"
$StderrLog = Join-Path $DataDir "java.stderr.log"
$SummaryPath = Join-Path $DataDir "runtime-summary.json"
New-Item -ItemType Directory -Path $DataDir -Force | Out-Null
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}

function Invoke-HttpStatus([string]$Url, [string]$Token = "") {
    $headers = @{}
    if ($Token) { $headers["X-BearCoding-Token"] = $Token }
    try {
        $response = Invoke-WebRequest -Uri $Url -Headers $headers -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        return [int]$response.StatusCode
    }
    catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

if ($Port -le 0) { $Port = Get-FreeTcpPort }
$TokenBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($TokenBytes)
$Token = -join ($TokenBytes | ForEach-Object { $_.ToString("x2") })

$JavaTemp = Join-Path $DataDir "tmp"
New-Item -ItemType Directory -Path $JavaTemp -Force | Out-Null
$OldTemp = $env:TEMP
$OldTmp = $env:TMP
$env:JINGJING_DATA_DIR = $DataDir
$env:BEARCODING_LOCAL_TOKEN = $Token
$env:TEMP = $JavaTemp
$env:TMP = $JavaTemp
$JavaArgs = @(
    "-Dfile.encoding=UTF-8",
    "-jar", $Jar,
    "--server.port=$Port",
    "--server.address=127.0.0.1",
    "--app.auth.mode=desktop",
    "--app.data.mode=desktop"
)
$JavaProcess = $null
try {
    $JavaProcess = Start-Process -FilePath $Java -ArgumentList $JavaArgs `
        -WorkingDirectory $DataDir -PassThru `
        -RedirectStandardOutput $StdoutLog -RedirectStandardError $StderrLog
    Remove-Item Env:JINGJING_DATA_DIR
    Remove-Item Env:BEARCODING_LOCAL_TOKEN
    $env:TEMP = $OldTemp
    $env:TMP = $OldTmp

    $Deadline = (Get-Date).AddSeconds(120)
    $ChatStatus = 0
    while ((Get-Date) -lt $Deadline) {
        $JavaProcess.Refresh()
        if ($JavaProcess.HasExited) {
            throw "Java 提前退出（退出码 $($JavaProcess.ExitCode)）。日志：$StdoutLog / $StderrLog"
        }
        $ChatStatus = Invoke-HttpStatus "http://127.0.0.1:$Port/chat"
        if ($ChatStatus -eq 200) { break }
        Start-Sleep -Seconds 2
    }
    if ($ChatStatus -ne 200) { throw "打包 JRE/JAR 未能提供 /chat，状态 $ChatStatus。" }

    $Chat = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/chat" -UseBasicParsing -TimeoutSec 10
    if ($Chat.Content -notmatch 'id="app"') { throw "打包页面缺少 #app 挂载点。" }

    $NoTokenStatus = Invoke-HttpStatus "http://127.0.0.1:$Port/ai/models"
    if ($NoTokenStatus -ne 401) { throw "无 Token API 未返回 401，实际 $NoTokenStatus。" }

    $TokenStatus = Invoke-HttpStatus "http://127.0.0.1:$Port/ai/models" $Token
    if ($TokenStatus -ne 200) { throw "带 Token API 未返回 200，实际 $TokenStatus。" }

    $StatusResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/ai/models/status" `
        -Headers @{ "X-BearCoding-Token" = $Token } -UseBasicParsing -TimeoutSec 10
    $Status = $StatusResponse.Content | ConvertFrom-Json

    $Listeners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop
    $NonLoopback = @($Listeners | Where-Object { $_.LocalAddress -notin @("127.0.0.1", "::1") })
    if ($NonLoopback.Count -ne 0) { throw "后端监听了非回环地址：$($NonLoopback.LocalAddress -join ', ')" }

    $Database = Join-Path $DataDir "data\jingjing.db"
    $Secrets = Join-Path $DataDir "secrets"
    if (-not (Test-Path -LiteralPath $Database)) { throw "隔离数据目录未生成 SQLite 数据库：$Database" }
    if (-not (Test-Path -LiteralPath $Secrets)) { throw "隔离数据目录未生成 secrets 目录：$Secrets" }

    $StdoutText = Get-Content -LiteralPath $StdoutLog -Raw
    if ($StdoutText -match [regex]::Escape($Token)) { throw "Java 标准输出包含桌面令牌明文。" }

    $FrontendOutput = & node $FrontendScript $Port $Token $DataDir 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "Vue 页面冒烟失败（退出码 $LASTEXITCODE）：`n$FrontendOutput"
    }
    $Frontend = $FrontendOutput | ConvertFrom-Json
    if (-not $Frontend.promptFound) {
        throw "Vue 页面未显示「请先配置模型」。页面文本：`n$($Frontend.bodyText)"
    }
    if ([int]$Frontend.apiStatus -ne 200) {
        throw "浏览器同源 API 未带 Token 成功，实际 $($Frontend.apiStatus)。"
    }

    Stop-Process -Id $JavaProcess.Id -Force
    $ExitDeadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $ExitDeadline) {
        $JavaProcess.Refresh()
        if ($JavaProcess.HasExited) { break }
        Start-Sleep -Milliseconds 500
    }
    $JavaProcess.Refresh()
    if (-not $JavaProcess.HasExited) { throw "Java 进程未退出。" }

    $Summary = [ordered]@{
        checkedAt = (Get-Date).ToString("o")
        dataDir = $DataDir
        port = $Port
        java = $Java
        jar = $Jar
        chatStatus = $ChatStatus
        noTokenApiStatus = $NoTokenStatus
        tokenApiStatus = $TokenStatus
        modelConfigured = $Status.modelConfigured
        systemModelAvailable = $Status.systemModelAvailable
        hasUserModels = $Status.hasUserModels
        loopbackOnly = ($NonLoopback.Count -eq 0)
        sqliteCreated = (Test-Path -LiteralPath $Database)
        secretsCreated = (Test-Path -LiteralPath $Secrets)
        tokenNotInStdout = ($StdoutText -notmatch [regex]::Escape($Token))
        frontendTitle = $Frontend.title
        frontendPromptFound = $Frontend.promptFound
        frontendApiStatus = [int]$Frontend.apiStatus
        frontendConsoleErrors = $Frontend.consoleErrors
        frontendPageErrors = $Frontend.pageErrors
        frontendScreenshot = $Frontend.screenshot
        javaExited = $JavaProcess.HasExited
    }
    $Summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $SummaryPath -Encoding UTF8
    Write-Output ($Summary | ConvertTo-Json -Depth 4)
    exit 0
}
catch {
    Write-Error $_
    $env:TEMP = $OldTemp
    $env:TMP = $OldTmp
    if ($JavaProcess) {
        $JavaProcess.Refresh()
        if (-not $JavaProcess.HasExited) {
            & taskkill /PID $JavaProcess.Id /T /F 2>$null | Out-Null
        }
    }
    exit 1
}
