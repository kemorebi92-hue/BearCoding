param(
    [string]$Exe = "",
    [string]$DataDir = "",
    [int]$CdpPort = 0,
    [switch]$NoSandbox,
    [switch]$DefaultProfile
)

# 成品 EXE 的窗口验收：直接启动 release 里那个 portable 构建产出的可执行文件
# （默认用 release\win-unpacked\BearCoding.exe，与 portable EXE 解包后完全相同），
# 覆盖：窗口显示、模型配置页、桌面令牌注入、无令牌拒绝、单实例、退出清理、
#      数据目录、导航白名单。
#
# 隔离手段：APPDATA / LOCALAPPDATA / TEMP 全部指向本次测试目录，
# 因此不会读写使用者真实的 %LOCALAPPDATA%\BearCoding。
#
# 注意：如果被测 EXE 位于低完整性工作区（带 Low Mandatory Level），Windows 会以
# Low integrity 启动它，导致 NSIS 临时文件错误或 Electron 初始化失败。请把 EXE 复制到
# 普通目录，或使用 build-portable.ps1 -OutputDir 指向普通目录后再验收。
# -NoSandbox 只用于定位，不是发布验收的前提；默认沙箱应在普通目录下通过。

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue

$DesktopDir = Split-Path -Parent $PSScriptRoot
if (-not $Exe) {
    $Exe = Join-Path $DesktopDir "release\win-unpacked\BearCoding.exe"
}
if (-not (Test-Path -LiteralPath $Exe)) { throw "缺少可执行文件：$Exe" }
$Exe = [System.IO.Path]::GetFullPath($Exe)

$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $DataDir) {
    $DataDir = Join-Path $DesktopDir "test-user-data\pkgwindow-$Stamp"
}
$DataDir = [System.IO.Path]::GetFullPath($DataDir)
$AppDataDir = Join-Path $DataDir "appdata"
$LocalAppDataDir = Join-Path $DataDir "localappdata"
$TempDir = Join-Path $DataDir "tmp"
$SummaryPath = Join-Path $DataDir "window-summary.json"
$Screenshot = Join-Path $DataDir "window-chat.png"
New-Item -ItemType Directory -Path $AppDataDir -Force | Out-Null
New-Item -ItemType Directory -Path $LocalAppDataDir -Force | Out-Null
New-Item -ItemType Directory -Path $TempDir -Force | Out-Null
# -DefaultProfile：不预建 Electron 用户数据目录、不传 --user-data-dir，用于默认启动验收。
# 非 -DefaultProfile：预建目录并显式传 --user-data-dir，仅用于历史对照。
$UserDataDir = Join-Path $AppDataDir "BearCoding"
if (-not $DefaultProfile) {
    New-Item -ItemType Directory -Path $UserDataDir -Force | Out-Null
}
$CdpScript = Join-Path $PSScriptRoot "window-cdp.cjs"
if (-not (Test-Path -LiteralPath $CdpScript)) { throw "缺少 CDP 脚本：$CdpScript" }

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}

function Invoke-HttpStatus([string]$Url) {
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        return [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

# 便携 EXE 会把自己解包到临时目录，所以不能按「可执行文件所在目录」找 Java 子进程；
# 改为「命令行含 bearcoding-backend.jar 且不是启动前就存在的 java.exe」。
$script:PreExistingJava = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    ForEach-Object { $_.ProcessId })

function Get-BearCodingJava() {
    return @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            $_.CommandLine -and
            $_.CommandLine.Contains("bearcoding-backend.jar") -and
            ($script:PreExistingJava -notcontains $_.ProcessId)
        })
}

if ($CdpPort -le 0) { $CdpPort = Get-FreeTcpPort }

$ExeArgs = @("--remote-debugging-port=$CdpPort")
if (-not $DefaultProfile) { $ExeArgs += "--user-data-dir=$UserDataDir" }
if ($NoSandbox) { $ExeArgs = @("--no-sandbox") + $ExeArgs }

$OldAppData = $env:APPDATA
$OldLocalAppData = $env:LOCALAPPDATA
$OldTemp = $env:TEMP
$OldTmp = $env:TMP

$App = $null
$Summary = $null
try {
    $env:APPDATA = $AppDataDir
    $env:LOCALAPPDATA = $LocalAppDataDir
    $env:TEMP = $TempDir
    $env:TMP = $TempDir

    $App = Start-Process -FilePath $Exe -ArgumentList $ExeArgs -PassThru `
        -RedirectStandardOutput (Join-Path $DataDir "app.stdout.log") `
        -RedirectStandardError (Join-Path $DataDir "app.stderr.log")

    $env:APPDATA = $OldAppData
    $env:LOCALAPPDATA = $OldLocalAppData
    $env:TEMP = $OldTemp
    $env:TMP = $OldTmp

    # ---------- 等待窗口 + 后端就绪 ----------
    $Deadline = (Get-Date).AddSeconds(150)
    $BackendPort = $null
    $ChatStatus = 0
    $CdpReady = $false
    while ((Get-Date) -lt $Deadline) {
        $App.Refresh()
        if ($App.HasExited) {
            throw "可执行文件提前退出（退出码 $($App.ExitCode)）。见 $DataDir\app.stderr.log"
        }
        if (-not $CdpReady) {
            try {
                $null = Invoke-WebRequest -Uri "http://127.0.0.1:$CdpPort/json/list" -UseBasicParsing -TimeoutSec 3
                $CdpReady = $true
            } catch { }
        }
        $javas = @(Get-BearCodingJava)
        if ($javas.Count -gt 0) {
            $cmdline = $javas[0].CommandLine
            if ($cmdline -match '--server\.port=(\d+)') {
                $BackendPort = [int]$Matches[1]
                $ChatStatus = Invoke-HttpStatus "http://127.0.0.1:$BackendPort/chat"
                if ($ChatStatus -eq 200 -and $CdpReady) { break }
            }
        }
        Start-Sleep -Seconds 2
    }
    if (-not $BackendPort) { throw "未发现随包 Java 后端端口。" }
    if ($ChatStatus -ne 200) { throw "后端 /chat 未返回 200（实际 $ChatStatus）。" }
    if (-not $CdpReady) { throw "CDP 端口 $CdpPort 未就绪，无法检查窗口。" }

    # 便携 EXE 先自解包再启动窗口，/json/list 可能短暂为空；等页面 target 真正出现
    $PageDeadline = (Get-Date).AddSeconds(90)
    $pageTarget = $null
    while ((Get-Date) -lt $PageDeadline) {
        $App.Refresh()
        if ($App.HasExited) { throw "可执行文件在窗口出现前退出（退出码 $($App.ExitCode)）。" }
        try {
            $targets = (Invoke-WebRequest -Uri "http://127.0.0.1:$CdpPort/json/list" -UseBasicParsing -TimeoutSec 3).Content | ConvertFrom-Json
            $pageTarget = @($targets) | Where-Object { $_.type -eq "page" -and $_.url -like "http://127.0.0.1:*" } | Select-Object -First 1
        } catch { $pageTarget = $null }
        if ($pageTarget) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $pageTarget) { throw "CDP 上始终没有出现 BearCoding 页面 target。" }
    # ---------- 窗口内容 / 令牌注入 ----------
    $InspectOutput = & node $CdpScript $CdpPort inspect 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "窗口检查失败：`n$InspectOutput" }
    $Inspect = $InspectOutput | ConvertFrom-Json

    $ShotOutput = & node $CdpScript $CdpPort screenshot $Screenshot 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "窗口截图失败：`n$ShotOutput" }

    # ---------- 无令牌拒绝 ----------
    $NoTokenStatus = Invoke-HttpStatus "http://127.0.0.1:$BackendPort/ai/models"
    $NoTokenStatusUser = Invoke-HttpStatus "http://127.0.0.1:$BackendPort/user/current"

    # ---------- 导航白名单 ----------
    $NavOutput = & node $CdpScript $CdpPort navigate-test 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "导航测试失败：`n$NavOutput" }
    $Nav = $NavOutput | ConvertFrom-Json

    # ---------- 单实例 ----------
    # 第二个实例用另一个空闲 CDP 端口，避免「端口占用」掩盖单实例锁的真实行为
    $SecondArgs = @("--remote-debugging-port=$(Get-FreeTcpPort)")
    if (-not $DefaultProfile) { $SecondArgs += "--user-data-dir=$UserDataDir" }
    if ($NoSandbox) { $SecondArgs = @("--no-sandbox") + $SecondArgs }
    $env:APPDATA = $AppDataDir
    $env:LOCALAPPDATA = $LocalAppDataDir
    $env:TEMP = $TempDir
    $env:TMP = $TempDir
    $Second = Start-Process -FilePath $Exe -ArgumentList $SecondArgs -PassThru `
        -RedirectStandardOutput (Join-Path $DataDir "second.stdout.log") `
        -RedirectStandardError (Join-Path $DataDir "second.stderr.log")
    $env:APPDATA = $OldAppData
    $env:LOCALAPPDATA = $OldLocalAppData
    $env:TEMP = $OldTemp
    $env:TMP = $OldTmp
    # 便携 EXE 的第二个实例要先自解包（约 200 MB）才会走到单实例锁，
    # 因此这里轮询等待，而不是固定睡几秒。
    $SecondDeadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $SecondDeadline) {
        $Second.Refresh()
        if ($Second.HasExited) { break }
        Start-Sleep -Seconds 2
    }
    $Second.Refresh()
    $SecondExited = $Second.HasExited
    if (-not $SecondExited) { Stop-Process -Id $Second.Id -Force -ErrorAction SilentlyContinue }
    $javaCount = @(Get-BearCodingJava).Count

    # ---------- 退出清理 ----------
    $null = & node $CdpScript $CdpPort close 2>&1 | Out-String
    $CloseDeadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $CloseDeadline) {
        $App.Refresh()
        $still = @(Get-BearCodingJava)
        if ($App.HasExited -and $still.Count -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    $App.Refresh()
    $still = @(Get-BearCodingJava)
    $AppExited = $App.HasExited
    $JavaExited = ($still.Count -eq 0)

    # ---------- 数据目录 / 令牌泄露 ----------
    $DataRoot = Join-Path $LocalAppDataDir "BearCoding"
    $Database = Join-Path $DataRoot "data\jingjing.db"
    $Secrets = Join-Path $DataRoot "secrets"
    $BackendLog = Join-Path $DataRoot "logs\backend.log"
    $LogText = ""
    foreach ($f in @($BackendLog,
                     (Join-Path $DataDir "app.stdout.log"),
                     (Join-Path $DataDir "app.stderr.log"))) {
        if (Test-Path -LiteralPath $f) { $LogText += (Get-Content -LiteralPath $f -Raw -Encoding UTF8) }
    }
    $tokenInLogs = $LogText -match 'BEARCODING_LOCAL_TOKEN=|X-BearCoding-Token:\s*[0-9a-f]{64}'
    $desktopAuthLog = $LogText -match "桌面鉴权已启用"

    $Summary = [ordered]@{
        checkedAt             = (Get-Date).ToString("o")
        exe                   = $Exe
        exeArgs               = ($ExeArgs -join " ")
        sandboxDisabled       = [bool]$NoSandbox
        defaultProfileMode    = [bool]$DefaultProfile
        dataDir               = $DataDir
        backendPort           = $BackendPort
        cdpPort               = $CdpPort
        appPid                = $App.Id
        windowTitle           = $Inspect.title
        windowHref            = $Inspect.href
        windowUserAgent       = $Inspect.userAgent
        tokenInjectedApiStatus = [int]$Inspect.apiStatus
        tokenInjectedApiBody  = $Inspect.apiBody
        modelStatusBody       = $Inspect.statusBody
        modelPromptFound      = ($Inspect.bodyText -match "请先配置模型|配置模型|选择模型")
        modelsNavFound        = [bool]$Inspect.hasModelsNav
        bodyText              = $Inspect.bodyText
        screenshot            = $Screenshot
        screenshotBytes       = (Get-Item -LiteralPath $Screenshot).Length
        noTokenApiStatus      = $NoTokenStatus
        noTokenUserStatus     = $NoTokenStatusUser
        navBlocked            = [bool]$Nav.blocked
        navBefore             = $Nav.before
        navAfter              = $Nav.after
        windowOpenDenied      = ($Nav.windowOpenResult -match 'null')
        secondInstanceExited  = $SecondExited
        javaProcessCountAfterSecondLaunch = $javaCount
        appExitedAfterClose   = $AppExited
        javaExitedAfterClose  = $JavaExited
        dataRoot              = $DataRoot
        sqliteCreated         = (Test-Path -LiteralPath $Database)
        secretsCreated        = (Test-Path -LiteralPath $Secrets)
        desktopAuthLogFound   = $desktopAuthLog
        tokenLeakInLogs       = $tokenInLogs
    }
    $Summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $SummaryPath -Encoding UTF8
    Write-Output ($Summary | ConvertTo-Json -Depth 6)

    if ([int]$Inspect.apiStatus -ne 200) { throw "渲染进程同源 API 未带令牌成功（$($Inspect.apiStatus)）。" }
    if ($Inspect.bodyText -notmatch "配置模型|选择模型") { throw "页面上找不到模型配置入口。" }
    if ($NoTokenStatus -ne 401) { throw "无令牌 /ai/models 未返回 401（实际 $NoTokenStatus）。" }
    if (-not $Nav.blocked) { throw "外部导航未被拦截：$($Nav.after)" }
    if (-not $SecondExited) { throw "第二个实例没有退出，单实例保护失效。" }
    if ($javaCount -ne 1) { throw "第二个实例启动后 Java 进程数为 $javaCount（期望 1）。" }
    if (-not $AppExited) { throw "关闭窗口后应用进程未退出。" }
    if (-not $JavaExited) { throw "关闭窗口后 Java 子进程未清理。" }
    if (-not (Test-Path -LiteralPath $Database)) { throw "数据目录未生成 SQLite：$Database" }
    if ($tokenInLogs) { throw "日志中出现令牌痕迹。" }
    exit 0
}
catch {
    Write-Error $_
    exit 1
}
finally {
    $env:APPDATA = $OldAppData
    $env:LOCALAPPDATA = $OldLocalAppData
    $env:TEMP = $OldTemp
    $env:TMP = $OldTmp
    if ($App) {
        $App.Refresh()
        if (-not $App.HasExited) { & taskkill /PID $App.Id /T /F 2>$null | Out-Null }
    }
    foreach ($j in (Get-BearCodingJava)) {
        & taskkill /PID $j.ProcessId /T /F 2>$null | Out-Null
    }
}

