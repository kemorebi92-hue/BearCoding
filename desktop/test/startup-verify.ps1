param(
    [string]$DataDir = "",
    [int]$CdpPort = 0
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# 某些自动化宿主会设置 ELECTRON_RUN_AS_NODE=1，这会让 Electron 以 Node 模式启动。
Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue

$DesktopDir = Split-Path -Parent $PSScriptRoot
$ElectronExe = Join-Path $DesktopDir "node_modules\electron\dist\electron.exe"
$SmokeScript = Join-Path $PSScriptRoot "electron-smoke.cjs"
if (-not (Test-Path -LiteralPath $ElectronExe)) {
    throw "Electron 未安装：$ElectronExe。请先运行 desktop\build-portable.ps1 或 npm ci。"
}

$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $DataDir) {
    $DataDir = Join-Path $DesktopDir "test-user-data\phaseb-$Stamp"
}
$DataDir = [System.IO.Path]::GetFullPath($DataDir)
$ProfileDir = Join-Path $DataDir "electron-profile"
$StdoutLog = Join-Path $DataDir "electron.stdout.log"
$StderrLog = Join-Path $DataDir "electron.stderr.log"
$BackendLog = Join-Path $DataDir "logs\backend.log"
$SummaryPath = Join-Path $DataDir "startup-summary.json"
New-Item -ItemType Directory -Path $DataDir -Force | Out-Null
New-Item -ItemType Directory -Path $ProfileDir -Force | Out-Null

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

function Get-BearCodingJava([string]$Root) {
    return Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object {
            $_.CommandLine -and
            $_.CommandLine.Contains($Root) -and
            $_.CommandLine -match '--server\.port=(\d+)'
        } |
        Select-Object -First 1
}

function Invoke-HttpStatus([string]$Url) {
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        return [int]$response.StatusCode
    }
    catch {
        if ($_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }
        return 0
    }
}

if ($CdpPort -le 0) {
    $CdpPort = Get-FreeTcpPort
}

$JavaTemp = Join-Path $DataDir "tmp"
New-Item -ItemType Directory -Path $JavaTemp -Force | Out-Null
$OldTemp = $env:TEMP
$OldTmp = $env:TMP
$env:BEARCODING_DEV_DATA_DIR = $DataDir
$env:TEMP = $JavaTemp
$env:TMP = $JavaTemp
$ElectronArgs = @(
    ".",
    "--remote-debugging-port=$CdpPort",
    "--user-data-dir=`"$ProfileDir`""
)

$Electron = $null
$JavaPid = $null
try {
    $Electron = Start-Process -FilePath $ElectronExe -ArgumentList $ElectronArgs `
        -WorkingDirectory $DesktopDir -PassThru `
        -RedirectStandardOutput $StdoutLog -RedirectStandardError $StderrLog
    Remove-Item Env:BEARCODING_DEV_DATA_DIR
    $env:TEMP = $OldTemp
    $env:TMP = $OldTmp

    $Deadline = (Get-Date).AddSeconds(120)
    $BackendPort = $null
    while ((Get-Date) -lt $Deadline) {
        $Electron.Refresh()
        if ($Electron.HasExited) {
            throw "Electron 提前退出（退出码 $($Electron.ExitCode)）。日志：$StdoutLog / $StderrLog"
        }

        $Java = Get-BearCodingJava $DataDir
        if ($Java) {
            $JavaPid = [int]$Java.ProcessId
            if ($Java.CommandLine -match '--server\.port=(\d+)') {
                $BackendPort = [int]$Matches[1]
            }
            if ($BackendPort) {
                $chatStatus = Invoke-HttpStatus "http://127.0.0.1:$BackendPort/chat"
                if ($chatStatus -eq 200) {
                    break
                }
            }
        }
        Start-Sleep -Seconds 2
    }

    if (-not $BackendPort) {
        throw "未发现 BearCoding Java 后端端口。日志：$BackendLog"
    }
    if ($chatStatus -ne 200) {
        throw "Vue 页面未返回 200，实际 $chatStatus。日志：$BackendLog"
    }

    $Chat = Invoke-WebRequest -Uri "http://127.0.0.1:$BackendPort/chat" -UseBasicParsing -TimeoutSec 10
    if ($Chat.Content -notmatch 'id="app"') {
        throw "Vue 页面未包含 #app 挂载点。"
    }

    $NoTokenStatus = Invoke-HttpStatus "http://127.0.0.1:$BackendPort/ai/models"
    if ($NoTokenStatus -ne 401) {
        throw "无 Token 的 /ai/models 未返回 401，实际 $NoTokenStatus。"
    }

    $SmokeOutput = & node $SmokeScript $CdpPort inspect 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "Electron CDP 冒烟失败：`n$SmokeOutput"
    }
    $Smoke = $SmokeOutput | ConvertFrom-Json
    if ([int]$Smoke.apiStatus -ne 200) {
        throw "渲染进程同源 API 未带 Token 成功，实际 $($Smoke.apiStatus)。输出：`n$SmokeOutput"
    }
    if ($Smoke.apiBody.Trim() -ne "[]") {
        throw "隔离数据目录中 /ai/models 预期为空数组，实际：$($Smoke.apiBody)"
    }
    if ($Smoke.title -ne "BearCoding") {
        throw "页面标题异常：$($Smoke.title)"
    }
    if ($Smoke.bodyText -notmatch "请先配置模型") {
        throw "无模型时未显示「请先配置模型」。页面文本：`n$($Smoke.bodyText)"
    }

    $BackendLogText = if (Test-Path -LiteralPath $BackendLog) {
        Get-Content -LiteralPath $BackendLog -Raw
    } else {
        ""
    }
    if ($BackendLogText -notmatch "桌面鉴权已启用") {
        throw "backend.log 未显示桌面鉴权已启用。"
    }
    if ($BackendLogText -match "BEARCODING_LOCAL_TOKEN=") {
        throw "backend.log 出现令牌环境变量赋值，疑似泄露。"
    }

    $SecondStdout = Join-Path $DataDir "second-instance.stdout.log"
    $SecondStderr = Join-Path $DataDir "second-instance.stderr.log"
    $Second = Start-Process -FilePath $ElectronExe -ArgumentList $ElectronArgs `
        -WorkingDirectory $DesktopDir -PassThru `
        -RedirectStandardOutput $SecondStdout -RedirectStandardError $SecondStderr
    Start-Sleep -Seconds 5
    $Second.Refresh()
    $SecondExited = $Second.HasExited
    if (-not $SecondExited) {
        Stop-Process -Id $Second.Id -Force
    }

    $JavaCount = @(Get-BearCodingJava $DataDir).Count
    if ($JavaCount -ne 1) {
        throw "单实例检查失败：当前 BearCoding Java 进程数为 $JavaCount。"
    }

    $CloseOutput = & node $SmokeScript $CdpPort close 2>&1 | Out-String
    Start-Sleep -Seconds 2
    $CloseDeadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $CloseDeadline) {
        $Electron.Refresh()
        $JavaStill = Get-BearCodingJava $DataDir
        if ($Electron.HasExited -and -not $JavaStill) {
            break
        }
        Start-Sleep -Seconds 1
    }
    $Electron.Refresh()
    $JavaStill = Get-BearCodingJava $DataDir
    $ElectronExited = $Electron.HasExited
    $JavaExited = -not $JavaStill
    if (-not $ElectronExited) {
        throw "关闭窗口后 Electron 未退出。"
    }
    if (-not $JavaExited) {
        throw "关闭窗口后 Java 子进程未清理。PID=$($JavaStill.ProcessId)"
    }

    $Summary = [ordered]@{
        checkedAt = (Get-Date).ToString("o")
        dataDir = $DataDir
        cdpPort = $CdpPort
        backendPort = $BackendPort
        electronPid = $Electron.Id
        javaPid = $JavaPid
        chatStatus = $chatStatus
        vueMountFound = $true
        noTokenApiStatus = $NoTokenStatus
        tokenInjectedApiStatus = [int]$Smoke.apiStatus
        tokenInjectedApiBody = $Smoke.apiBody
        modelPromptFound = $true
        desktopAuthLogFound = $true
        singleInstanceExited = $SecondExited
        singleJavaProcess = ($JavaCount -eq 1)
        electronExited = $ElectronExited
        javaExited = $JavaExited
        smokeTitle = $Smoke.title
        smokeHref = $Smoke.href
        smokeUserAgent = $Smoke.userAgent
        bodyText = $Smoke.bodyText
    }
    $Summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $SummaryPath -Encoding UTF8
    Write-Output ($Summary | ConvertTo-Json -Depth 4)
    exit 0
}
catch {
    Write-Error $_
    $env:TEMP = $OldTemp
    $env:TMP = $OldTmp
    if ($Electron) {
        $Electron.Refresh()
        if (-not $Electron.HasExited) {
            Stop-Process -Id $Electron.Id -Force -ErrorAction SilentlyContinue
        }
    }
    if ($JavaPid) {
        & taskkill /PID $JavaPid /T /F 2>$null | Out-Null
    }
    exit 1
}
