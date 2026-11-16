param(
    [string]$DataDir = "",
    [int]$BackendPort = 0,
    [int]$MockPort = 0,
    [string]$UnpackedDir = ""
)

# 便携版核心任务验收（不启动 Electron）：
#   用 release/win-unpacked 里那份「将要打进 portable EXE」的 JRE + JAR，
#   配一个受控的 OpenAI 兼容测试模型（test/mock-model-server.cjs），
#   在隔离数据目录里跑一次「读文件 → 改 Java 文件 → 自动编译校验 → 展示结果」。
#
# 它验证的是便携包里的 Java 运行时（含 javac）和 Spring Boot 后端，
# 不覆盖 Electron 窗口层；窗口层见 test/startup-verify.ps1。

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$DesktopDir = Split-Path -Parent $PSScriptRoot
if ($UnpackedDir) {
    $UnpackedDir = [System.IO.Path]::GetFullPath($UnpackedDir)
} else {
    $UnpackedDir = Join-Path $DesktopDir "release\win-unpacked"
}
$Java = Join-Path $UnpackedDir "resources\jre\bin\java.exe"
$Javac = Join-Path $UnpackedDir "resources\jre\bin\javac.exe"
$Jar = Join-Path $UnpackedDir "resources\app\bearcoding-backend.jar"
$MockServer = Join-Path $PSScriptRoot "mock-model-server.cjs"
if (-not (Test-Path -LiteralPath $Java)) { throw "缺少打包 JRE：$Java" }
if (-not (Test-Path -LiteralPath $Javac)) { throw "打包 JRE 里没有 javac.exe，自动编译校验必然失效：$Javac" }
if (-not (Test-Path -LiteralPath $Jar)) { throw "缺少打包 JAR：$Jar" }
if (-not (Test-Path -LiteralPath $MockServer)) { throw "缺少受控测试模型：$MockServer" }

$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $DataDir) {
    $DataDir = Join-Path $DesktopDir "test-user-data\portable-core-$Stamp"
}
$DataDir = [System.IO.Path]::GetFullPath($DataDir)
$WorkspaceDir = Join-Path $DataDir "workspace"
$DemoFile = Join-Path $WorkspaceDir "src\main\java\Demo.java"
$StdoutLog = Join-Path $DataDir "java.stdout.log"
$StderrLog = Join-Path $DataDir "java.stderr.log"
$MockLog = Join-Path $DataDir "mock-model.log"
$SummaryPath = Join-Path $DataDir "core-task-summary.json"
New-Item -ItemType Directory -Path $DataDir -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $WorkspaceDir "src\main\java") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $DataDir "logs") -Force | Out-Null

# 隔离的测试工作区：一个能编译的最小 Java 文件
$DemoSource = @'
public class Demo {
    public static void main(String[] args) {
        System.out.println("Hello, World");
    }
}
'@
# 不写 BOM：javac -encoding UTF-8 会把 BOM 当成非法字符
[System.IO.File]::WriteAllText($DemoFile, $DemoSource, [System.Text.UTF8Encoding]::new($false))

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}
if ($BackendPort -le 0) { $BackendPort = Get-FreeTcpPort }
if ($MockPort -le 0) { $MockPort = Get-FreeTcpPort }

$TokenBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($TokenBytes)
$Token = -join ($TokenBytes | ForEach-Object { $_.ToString("x2") })
$Headers = @{ "X-BearCoding-Token" = $Token }
$BaseUrl = "http://127.0.0.1:$BackendPort"

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null
    )
    $params = @{
        Uri         = "$BaseUrl$Path"
        Method      = $Method
        Headers     = $Headers
        TimeoutSec  = 300
        ErrorAction = "Stop"
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 8 -Compress
        $params["Body"] = [System.Text.Encoding]::UTF8.GetBytes($json)
        $params["ContentType"] = "application/json; charset=utf-8"
    }
    $resp = Invoke-WebRequest @params
    # Invoke-WebRequest 在响应未声明 charset 时按 ISO-8859-1 解码，中文会变乱码；
    # 这里直接对原始字节按 UTF-8 解码。
    $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    return ($text | ConvertFrom-Json)
}

$MockProcess = $null
$JavaProcess = $null
$OldTemp = $env:TEMP
$OldTmp = $env:TMP
$JavaTemp = Join-Path $DataDir "tmp"
New-Item -ItemType Directory -Path $JavaTemp -Force | Out-Null

try {
    # ---------- 1. 受控测试模型 ----------
    $MockProcess = Start-Process -FilePath "node.exe" `
        -ArgumentList @($MockServer, "--port", "$MockPort", "--log", $MockLog) `
        -WorkingDirectory $DataDir -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $DataDir "mock.stdout.log") `
        -RedirectStandardError (Join-Path $DataDir "mock.stderr.log")
    $MockDeadline = (Get-Date).AddSeconds(30)
    $MockReady = $false
    while ((Get-Date) -lt $MockDeadline) {
        try {
            $h = Invoke-WebRequest -Uri "http://127.0.0.1:$MockPort/health" -UseBasicParsing -TimeoutSec 3
            if ($h.StatusCode -eq 200) { $MockReady = $true; break }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $MockReady) { throw "受控测试模型未就绪（端口 $MockPort）。" }

    # ---------- 2. 便携包里的 Java 后端 ----------
    $env:JINGJING_DATA_DIR = $DataDir
    $env:BEARCODING_LOCAL_TOKEN = $Token
    $env:TEMP = $JavaTemp
    $env:TMP = $JavaTemp
    $JavaArgs = @(
        "-Dfile.encoding=UTF-8",
        "-jar", $Jar,
        "--server.port=$BackendPort",
        "--server.address=127.0.0.1",
        "--app.auth.mode=desktop",
        "--app.data.mode=desktop"
    )
    $JavaProcess = Start-Process -FilePath $Java -ArgumentList $JavaArgs `
        -WorkingDirectory $DataDir -PassThru `
        -RedirectStandardOutput $StdoutLog -RedirectStandardError $StderrLog
    Remove-Item Env:JINGJING_DATA_DIR
    Remove-Item Env:BEARCODING_LOCAL_TOKEN
    $env:TEMP = $OldTemp
    $env:TMP = $OldTmp

    $Deadline = (Get-Date).AddSeconds(150)
    $ChatStatus = 0
    while ((Get-Date) -lt $Deadline) {
        $JavaProcess.Refresh()
        if ($JavaProcess.HasExited) { throw "Java 提前退出（$($JavaProcess.ExitCode)）。见 $StdoutLog / $StderrLog" }
        try {
            $r = Invoke-WebRequest -Uri "$BaseUrl/chat" -UseBasicParsing -TimeoutSec 5
            $ChatStatus = [int]$r.StatusCode
            if ($ChatStatus -eq 200) { break }
        } catch {
            $ChatStatus = 0
        }
        Start-Sleep -Seconds 2
    }
    if ($ChatStatus -ne 200) { throw "后端未在限定时间内提供 /chat。" }

    # ---------- 3. 配置受控模型 ----------
    $model = Invoke-Api -Method POST -Path "/ai/models" -Body @{
        provider           = "acceptance-mock"
        baseUrl            = "http://127.0.0.1:$MockPort/v1"
        apiKey             = "acceptance-only-not-a-real-key"
        modelName          = "bearcoding-acceptance-mock"
        contextWindowTokens = 64000
        defaultModel       = $true
    }
    if (-not $model.id) { throw "模型配置未创建成功：$($model | ConvertTo-Json -Compress)" }

    # ---------- 4. 绑定隔离工作区 ----------
    $conversationId = [guid]::NewGuid().ToString()
    $bind = Invoke-Api -Method POST -Path "/ai/workspace/bind" -Body @{
        conversationId = $conversationId
        path           = $WorkspaceDir
    }
    if ($bind.PSObject.Properties.Name -contains "error") { throw "绑定工作区失败：$($bind.error)" }

    # ---------- 4b. 权限模式 ----------
    # bind 会先建 conversation 行（默认 ASK），而 /ai/chat 的 touch 只在首次 INSERT 时写权限模式，
    # 所以这里显式改成 WORKSPACE_EDIT，否则 editFile 会走审批、自动化测试里必然拿不到结果。
    $modeResp = Invoke-Api -Method PUT -Path "/ai/conversations/$conversationId/permission-mode" -Body @{
        permissionMode = "WORKSPACE_EDIT"
    }
    $modeNow = $modeResp.permissionMode

    # ---------- 5. 跑核心任务 ----------
    $taskMessage = "请读取 src/main/java/Demo.java，把输出内容改成 Hello, BearCoding v3.0.0，改完后确认工作区能编译通过，最后把改完的代码贴给我。"
    $chat = Invoke-Api -Method POST -Path "/ai/chat" -Body @{
        message         = $taskMessage
        conversationId  = $conversationId
        permissionMode  = "WORKSPACE_EDIT"
        modelConfigId   = $model.id
    }

    $messages = Invoke-Api -Method GET -Path "/ai/conversations/$conversationId/messages"
    $allText = ($messages | ForEach-Object { "$($_.role): $($_.content)" }) -join "`n"

    # ---------- 6. 断言 ----------
    $finalFile = Get-Content -LiteralPath $DemoFile -Raw
    $fileChanged = $finalFile -match [regex]::Escape('Hello, BearCoding v3.0.0')
    $oldTextGone = $finalFile -notmatch [regex]::Escape('Hello, World')

    $verifyLogText = if (Test-Path -LiteralPath $StdoutLog) { Get-Content -LiteralPath $StdoutLog -Raw -Encoding UTF8 } else { "" }
    $compileOk = ($allText -match "编译通过（javac 退出码 0") -or ($verifyLogText -match "\[VerifyCompile\] ran=true success=true")
    $verifyCompileLine = (($verifyLogText -split "`r?`n") | Where-Object { $_ -match "\[VerifyCompile\]" } | Select-Object -Last 1)
    $javacNotFound = $allText -match "未找到 javac"
    $readToolUsed = $allText -match "readFile|Demo\.java"

    # 用打包 JRE 里的 javac 独立复核一次：证明编译校验用的就是随包运行时
    $verifyOutDir = Join-Path $DataDir "verify-classes"
    New-Item -ItemType Directory -Path $verifyOutDir -Force | Out-Null
    & $Javac -encoding UTF-8 -d $verifyOutDir $DemoFile 2>&1 | Out-Null
    $standaloneJavacExit = $LASTEXITCODE
    & $Java "-cp" $verifyOutDir "Demo" 2>&1 | Out-File -FilePath (Join-Path $DataDir "verify-run.txt") -Encoding UTF8
    $runOutput = (Get-Content -LiteralPath (Join-Path $DataDir "verify-run.txt") -Raw).Trim()

    $stdoutText = if (Test-Path -LiteralPath $StdoutLog) { Get-Content -LiteralPath $StdoutLog -Raw } else { "" }
    $backendLog = Join-Path $DataDir "logs\backend.log"
    $backendLogText = if (Test-Path -LiteralPath $backendLog) { Get-Content -LiteralPath $backendLog -Raw } else { "" }
    $tokenInLogs = ($stdoutText -match [regex]::Escape($Token)) -or ($backendLogText -match [regex]::Escape($Token))

    $summary = [ordered]@{
        checkedAt             = (Get-Date).ToString("o")
        dataDir               = $DataDir
        workspace             = $WorkspaceDir
        backendPort           = $BackendPort
        mockModelPort         = $MockPort
        java                  = $Java
        javac                 = $Javac
        jar                   = $Jar
        conversationId        = $conversationId
        permissionMode        = $modeNow
        chatResponseAction    = $chat.action
        chatResponseMessage   = $chat.message
        chatToolCalls         = @($chat.toolCalls).Count
        chatNeedsApproval     = $chat.needsApproval
        messageCount          = @($messages).Count
        fileChanged           = $fileChanged
        oldTextGone           = $oldTextGone
        readToolUsed          = $readToolUsed
        autoCompileVerified   = $compileOk
        verifyCompileLogLine  = $verifyCompileLine
        javacMissingReported  = $javacNotFound
        bundledJavacExit      = $standaloneJavacExit
        bundledJavaRunOutput  = $runOutput
        tokenInLogs           = $tokenInLogs
        finalFileContent      = $finalFile
    }
    $summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $SummaryPath -Encoding UTF8
    Write-Output "--- 核心任务验收摘要 ---"
    Write-Output ("permissionMode        = " + $modeNow)
    Write-Output ("fileChanged           = " + $fileChanged)
    Write-Output ("oldTextGone           = " + $oldTextGone)
    Write-Output ("readToolUsed          = " + $readToolUsed)
    Write-Output ("autoCompileVerified   = " + $compileOk)
    Write-Output ("verifyCompileLogLine  = " + $verifyCompileLine)
    Write-Output ("bundledJavacExit      = " + $standaloneJavacExit)
    Write-Output ("bundledJavaRunOutput  = " + $runOutput)
    Write-Output ("tokenInLogs           = " + $tokenInLogs)
    Write-Output ("summaryJson           = " + $SummaryPath)

    if (-not $fileChanged) { throw "Demo.java 没有被改成 Hello, BearCoding v3.0.0。" }
    if (-not $oldTextGone) { throw "Demo.java 仍包含旧的 Hello, World。" }
    if (-not $compileOk) { throw "没有看到「编译通过（javac 退出码 0」的自动编译校验结果。" }
    if ($javacNotFound) { throw "自动编译校验报告找不到 javac，说明便携运行时的 javac 不可用。" }
    if ($standaloneJavacExit -ne 0) { throw "打包 JRE 的 javac 独立编译失败（退出码 $standaloneJavacExit）。" }
    if ($runOutput -notmatch "Hello, BearCoding v3.0.0") { throw "打包 JRE 运行编译产物输出不符：$runOutput" }
    if ($tokenInLogs) { throw "后端日志里出现了桌面令牌明文。" }
    exit 0
}
catch {
    Write-Error $_
    exit 1
}
finally {
    $env:TEMP = $OldTemp
    $env:TMP = $OldTmp
    Remove-Item Env:JINGJING_DATA_DIR -ErrorAction SilentlyContinue
    Remove-Item Env:BEARCODING_LOCAL_TOKEN -ErrorAction SilentlyContinue
    if ($JavaProcess) {
        $JavaProcess.Refresh()
        if (-not $JavaProcess.HasExited) {
            & taskkill /PID $JavaProcess.Id /T /F 2>$null | Out-Null
        }
    }
    if ($MockProcess) {
        $MockProcess.Refresh()
        if (-not $MockProcess.HasExited) {
            & taskkill /PID $MockProcess.Id /T /F 2>$null | Out-Null
        }
    }
}

