param(
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$DesktopDir = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $DesktopDir
$FrontendDir = Join-Path $ProjectRoot "frontend"
$BackendDir = Join-Path $ProjectRoot "backend"
$BuildDir = Join-Path $DesktopDir ".build"
if ($OutputDir) {
    if (-not [System.IO.Path]::IsPathRooted($OutputDir)) {
        $OutputDir = Join-Path $DesktopDir $OutputDir
    }
    $ReleaseDir = [System.IO.Path]::GetFullPath($OutputDir)
} else {
    $ReleaseDir = Join-Path $DesktopDir "release"
}
$StaticDir = Join-Path $BackendDir "src\main\resources\static"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "=== $Message ===" -ForegroundColor Cyan
}

function Invoke-Native([string]$File, [string[]]$Arguments, [string]$WorkingDirectory) {
    Push-Location $WorkingDirectory
    try {
        Write-Host "> $File $($Arguments -join ' ')" -ForegroundColor DarkGray
        & $File @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "命令失败（退出码 $LASTEXITCODE）：$File $($Arguments -join ' ')"
        }
    }
    finally {
        Pop-Location
    }
}

function Get-FileSha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

if ($env:OS -ne "Windows_NT") {
    throw "本脚本只支持 Windows。"
}

Write-Step "检查构建工具"
$Node = (Get-Command node.exe -ErrorAction Stop).Source
$Npm = (Get-Command npm.cmd -ErrorAction Stop).Source
$JdkHome = $env:JAVA_HOME
if (-not $JdkHome) {
    throw "未设置 JAVA_HOME，无法定位 jlink 和 Java 17。"
}
$JavaExe = Join-Path $JdkHome "bin\java.exe"
$JlinkExe = Join-Path $JdkHome "bin\jlink.exe"
if (-not (Test-Path -LiteralPath $JavaExe)) { throw "JAVA_HOME 下缺少 java.exe：$JavaExe" }
if (-not (Test-Path -LiteralPath $JlinkExe)) { throw "JAVA_HOME 下缺少 jlink.exe：$JlinkExe" }

$MavenWrapper = Join-Path $BackendDir "mvnw.cmd"
if (Test-Path -LiteralPath $MavenWrapper) {
    $Maven = $MavenWrapper
} else {
    $Maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
}

$JavaVersionLine = (& cmd.exe /c "`"$JavaExe`" -version 2>&1" | Select-Object -First 1)
$JdkReleasePath = Join-Path $JdkHome "release"
if (-not (Test-Path -LiteralPath $JdkReleasePath)) {
    throw "JAVA_HOME 下缺少 release 文件，无法记录 JDK 发行版信息：$JdkReleasePath"
}
$JdkRelease = @{}
foreach ($Line in Get-Content -LiteralPath $JdkReleasePath) {
    if ($Line -match '^([A-Z_]+)="?(.*?)"?$') { $JdkRelease[$Matches[1]] = $Matches[2] }
}
$JdkImplementor = $JdkRelease["IMPLEMENTOR"]
$JdkRuntimeVersion = $JdkRelease["JAVA_RUNTIME_VERSION"]
$JdkVersion = $JdkRelease["JAVA_VERSION"]
if (-not $JdkImplementor -or -not $JdkRuntimeVersion) {
    throw "无法从 $JdkReleasePath 解析 IMPLEMENTOR / JAVA_RUNTIME_VERSION。"
}
Write-Host "Node: $(& $Node --version)"
Write-Host "npm:  $(& $Npm --version)"
Write-Host "Java: $JavaVersionLine"
Write-Host "JDK:  $JdkImplementor $JdkRuntimeVersion（$JdkHome）"
Write-Host "jlink: $(& $JlinkExe --version)"
Write-Host "Maven: $Maven"

Write-Step "读取版本"
$DesktopPackage = Get-Content -LiteralPath (Join-Path $DesktopDir "package.json") -Raw | ConvertFrom-Json
$DesktopVersion = $DesktopPackage.version
[xml]$BackendPom = Get-Content -LiteralPath (Join-Path $BackendDir "pom.xml") -Raw
$BackendArtifactId = $BackendPom.project.artifactId
$BackendVersion = $BackendPom.project.version
if (-not $BackendArtifactId -or -not $BackendVersion) {
    throw "无法从 backend/pom.xml 解析 artifactId/version。"
}
$BackendJar = Join-Path $BackendDir "target\$BackendArtifactId-$BackendVersion.jar"
$Artifact = Join-Path $ReleaseDir "BearCoding-$DesktopVersion-portable.exe"
Write-Host "Desktop version: $DesktopVersion"
Write-Host "Backend artifact: $BackendArtifactId-$BackendVersion.jar"
Write-Host "Output dir: $ReleaseDir"

Write-Step "清理本阶段生成目录"
if (Test-Path -LiteralPath $BuildDir) {
    Remove-Item -LiteralPath $BuildDir -Recurse -Force
}
New-Item -ItemType Directory -Path $BuildDir -Force | Out-Null
New-Item -ItemType Directory -Path $ReleaseDir -Force | Out-Null
if (Test-Path -LiteralPath $Artifact) {
    Remove-Item -LiteralPath $Artifact -Force
}

Write-Step "安装前端依赖并构建 Vue"
Invoke-Native $Npm @("ci", "--prefer-offline", "--no-audit", "--no-fund") $FrontendDir
Invoke-Native $Npm @("run", "build") $FrontendDir
if (-not (Test-Path -LiteralPath (Join-Path $FrontendDir "dist\index.html"))) {
    throw "前端构建未生成 frontend/dist/index.html。"
}

Write-Step "把 Vue 静态资源复制进 Spring Boot"
New-Item -ItemType Directory -Path $StaticDir -Force | Out-Null
Get-ChildItem -LiteralPath $StaticDir -Force | Remove-Item -Recurse -Force
Copy-Item -Path (Join-Path $FrontendDir "dist\*") -Destination $StaticDir -Recurse -Force

Write-Step "构建 Spring Boot JAR"
Invoke-Native $Maven @("clean", "package", "-DskipTests") $BackendDir
if (-not (Test-Path -LiteralPath $BackendJar)) {
    throw "Maven 未生成预期 JAR：$BackendJar"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$Zip = [System.IO.Compression.ZipFile]::OpenRead($BackendJar)
try {
    $EntryNames = @($Zip.Entries | ForEach-Object { $_.FullName })
    if ($EntryNames -notcontains "BOOT-INF/classes/static/index.html") {
        throw "JAR 内缺少 BOOT-INF/classes/static/index.html，Vue 静态资源没有打进后端。"
    }
    if (-not ($EntryNames | Where-Object { $_ -match '^BOOT-INF/classes/static/js/app\..+\.js$' })) {
        throw "JAR 内缺少 Vue 的 app.*.js。"
    }
}
finally {
    $Zip.Dispose()
}

$JarSha256 = Get-FileSha256 $BackendJar
Write-Host "JAR SHA-256: $JarSha256"
Copy-Item -LiteralPath $BackendJar -Destination (Join-Path $BuildDir "bearcoding-backend.jar") -Force

Write-Step "生成 jlink Java Runtime"
# 模块集说明：
#   - 前 21 项是后端运行所需（Spring Boot / sqlite-jdbc / JNA / TLS）。
#   - java.compiler + jdk.compiler 是**必须**的：CodeVerifier 用
#     System.getProperty("java.home")/bin/javac.exe 做「改完 Java 文件自动编译校验」，
#     java.home 在便携模式里就是本 Runtime。缺 jdk.compiler 时 jre/bin/javac.exe 不存在，
#     CodeVerifier 会退化成 Result.unverified("未验证：未找到 javac…")，
#     即编译校验静默失效。下面的断言会拦住这种回归。
$Modules = @(
    "java.base",
    "java.datatransfer",
    "java.xml",
    "java.prefs",
    "java.desktop",
    "java.instrument",
    "java.logging",
    "java.management",
    "java.security.sasl",
    "java.naming",
    "java.net.http",
    "java.rmi",
    "java.scripting",
    "java.security.jgss",
    "java.transaction.xa",
    "java.sql",
    "java.compiler",
    "jdk.compiler",
    "jdk.charsets",
    "jdk.crypto.ec",
    "jdk.localedata",
    "jdk.management",
    "jdk.unsupported"
) -join ","
$JreDir = Join-Path $BuildDir "jre"
Invoke-Native $JlinkExe @(
    "--add-modules", $Modules,
    "--strip-debug",
    "--no-header-files",
    "--no-man-pages",
    "--compress=2",
    "--output", $JreDir
) $DesktopDir

$BundledJava = Join-Path $JreDir "bin\java.exe"
if (-not (Test-Path -LiteralPath $BundledJava)) {
    throw "jlink 未生成 jre/bin/java.exe。"
}
$BundledJavac = Join-Path $JreDir "bin\javac.exe"
if (-not (Test-Path -LiteralPath $BundledJavac)) {
    throw "jlink Runtime 缺少 jre/bin/javac.exe：CodeVerifier 的自动编译校验会失效。请确认模块集包含 java.compiler,jdk.compiler。"
}
if (-not (Test-Path -LiteralPath (Join-Path $JreDir "legal"))) {
    throw "jlink 运行时缺少 legal 目录，许可证文件不完整。"
}
if (-not (Test-Path -LiteralPath (Join-Path $JreDir "legal\java.base\LICENSE"))) {
    throw "jlink 运行时缺少 legal/java.base/LICENSE，运行时许可文件不完整。"
}
Invoke-Native $BundledJava @("-version") $DesktopDir
Invoke-Native $BundledJavac @("-version") $DesktopDir

Write-Step "安装 Electron 依赖并生成 portable EXE"
Invoke-Native $Npm @("ci", "--prefer-offline", "--no-audit", "--no-fund") $DesktopDir

$ElectronDir = Join-Path $DesktopDir "node_modules\electron"
$ElectronDist = Join-Path $ElectronDir "dist"
$ElectronExe = Join-Path $ElectronDist "electron.exe"
if (-not (Test-Path -LiteralPath $ElectronExe)) {
    $ElectronVersion = $DesktopPackage.devDependencies.electron
    $ChecksumsPath = Join-Path $ElectronDir "checksums.json"
    $ExpectedHash = $null
    if (Test-Path -LiteralPath $ChecksumsPath) {
        $Checksums = Get-Content -LiteralPath $ChecksumsPath -Raw | ConvertFrom-Json
        $ExpectedHash = $Checksums."electron-v$ElectronVersion-win32-x64.zip"
    }

    $CacheRoot = Join-Path $env:LOCALAPPDATA "electron\Cache"
    $CachedZip = $null
    if (Test-Path -LiteralPath $CacheRoot) {
        $CachedZip = Get-ChildItem -LiteralPath $CacheRoot -Recurse -File -Filter "electron-v$ElectronVersion-win32-x64.zip" |
            Select-Object -First 1
    }

    if ($CachedZip) {
        $ActualHash = Get-FileSha256 $CachedZip.FullName
        if ($ExpectedHash -and $ActualHash -ne $ExpectedHash) {
            throw "Electron 缓存压缩包校验失败：$($CachedZip.FullName)"
        }
        if (Test-Path -LiteralPath $ElectronDist) {
            Remove-Item -LiteralPath $ElectronDist -Recurse -Force
        }
        New-Item -ItemType Directory -Path $ElectronDist -Force | Out-Null
        [System.IO.Compression.ZipFile]::ExtractToDirectory($CachedZip.FullName, $ElectronDist)
        Set-Content -LiteralPath (Join-Path $ElectronDir "path.txt") -Value "electron.exe" -Encoding ASCII
        Write-Host "Electron binary extracted from verified cache: $($CachedZip.FullName)"
    }
    else {
        Invoke-Native $Node @("node_modules\electron\install.js") $DesktopDir
    }
}
if (-not (Test-Path -LiteralPath $ElectronExe)) {
    throw "Electron 二进制未就绪：$ElectronExe"
}
$ElectronVersionFile = Join-Path $ElectronDist "version"
if (Test-Path -LiteralPath $ElectronVersionFile) {
    $ActualElectronVersion = (Get-Content -LiteralPath $ElectronVersionFile -Raw).Trim()
    if ($ActualElectronVersion -ne $DesktopPackage.devDependencies.electron) {
        throw "Electron 二进制版本不一致：期望 $($DesktopPackage.devDependencies.electron)，实际 $ActualElectronVersion"
    }
}

$Builder = Join-Path $DesktopDir "node_modules\.bin\electron-builder.cmd"
if (-not (Test-Path -LiteralPath $Builder)) {
    throw "未找到 electron-builder：$Builder"
}
$env:CSC_IDENTITY_AUTO_DISCOVERY = "false"
Invoke-Native $Builder @("--win", "portable", "--publish", "never", "--config.directories.output=$ReleaseDir") $DesktopDir

if (-not (Test-Path -LiteralPath $Artifact)) {
    throw "electron-builder 未生成预期 portable EXE：$Artifact"
}
if ((Get-Item -LiteralPath $Artifact).Length -lt 50MB) {
    throw "portable EXE 体积异常，疑似未包含 JRE/JAR：$Artifact"
}

# 低完整性目录（例如某些沙箱工作区）会让生成的 EXE 继承 Low Mandatory Level。
# Windows 随后以 Low integrity 启动该 EXE，NSIS 无法写入普通用户的 %TEMP%，
# 会报 "Error writing temporary file"；Electron 也可能在初始化阶段异常退出。
# 这里直接拒绝这种产物，避免把“构建成功”误当成“可双击”。
$ArtifactAcl = (& icacls $Artifact 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) {
    throw "无法读取产物完整性标签：$Artifact`n$ArtifactAcl"
}
if ($ArtifactAcl -match 'Low Mandatory Level|S-1-16-4096') {
    throw ("生成的 EXE 带 Low Mandatory Level（低完整性）标签：" + $Artifact + "`n" +
        "Windows 会以 Low integrity 启动它，NSIS 无法写入普通 %TEMP%，会出现`n" +
        "`"Error writing temporary file. Make sure your temp folder is valid.`"。`n" +
        "请用 -OutputDir 指向普通目录（例如 D:\BearCoding-Release）重新构建，`n" +
        "或把成品复制到普通目录后再验收/上传；不要直接在低完整性工作区内双击。")
}

Write-Step "校验发布目录与内置资源"
$UnpackedResources = Join-Path $ReleaseDir "win-unpacked\resources"
$Required = @(
    (Join-Path $UnpackedResources "app.asar"),
    (Join-Path $UnpackedResources "app\bearcoding-backend.jar"),
    (Join-Path $UnpackedResources "jre\bin\java.exe"),
    (Join-Path $UnpackedResources "jre\bin\javac.exe"),
    (Join-Path $UnpackedResources "jre\release"),
    (Join-Path $UnpackedResources "jre\legal\java.base\LICENSE"),
    (Join-Path $UnpackedResources "licenses\LICENSE.txt"),
    (Join-Path $UnpackedResources "licenses\THIRD-PARTY-NOTICES.md"),
    (Join-Path $ReleaseDir "win-unpacked\LICENSE.electron.txt"),
    (Join-Path $ReleaseDir "win-unpacked\LICENSES.chromium.html")
)
foreach ($Path in $Required) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "发布目录缺少必要资源：$Path"
    }
}
$PackagedJarSha256 = Get-FileSha256 (Join-Path $UnpackedResources "app\bearcoding-backend.jar")
if ($PackagedJarSha256 -ne $JarSha256) {
    throw "发布目录内 JAR 与本次构建 JAR 哈希不一致。"
}

# 许可证文本必须是仓库里那一份，不能被旧文件顶替
$SourceLicenseSha256 = Get-FileSha256 (Join-Path $ProjectRoot "LICENSE")
$PackagedLicenseSha256 = Get-FileSha256 (Join-Path $UnpackedResources "licenses\LICENSE.txt")
if ($SourceLicenseSha256 -ne $PackagedLicenseSha256) {
    throw "发布目录内的 LICENSE.txt 与仓库 LICENSE 不一致。"
}
$SourceNoticesSha256 = Get-FileSha256 (Join-Path $ProjectRoot "THIRD-PARTY-NOTICES.md")
$PackagedNoticesSha256 = Get-FileSha256 (Join-Path $UnpackedResources "licenses\THIRD-PARTY-NOTICES.md")
if ($SourceNoticesSha256 -ne $PackagedNoticesSha256) {
    throw "发布目录内的 THIRD-PARTY-NOTICES.md 与仓库版本不一致。"
}

$Forbidden = Get-ChildItem -LiteralPath $ReleaseDir -Recurse -File -Force | Where-Object {
    $_.Name -match '\.(db|db-wal|db-shm|key|pem|p12|pfx|jks|keystore|log)$' -or
    $_.FullName -match '\\(secrets|logs|test-user-data)\\' -or
    $_.Name -eq 'search-credential.bin'
}
if ($Forbidden) {
    throw "发布目录包含疑似用户数据或凭证：`n$($Forbidden.FullName -join "`n")"
}

$ExeSha256 = Get-FileSha256 $Artifact
$BuildInfo = [ordered]@{
    builtAt = (Get-Date).ToString("o")
    outputDir = $ReleaseDir
    desktopVersion = $DesktopVersion
    backendArtifact = "$BackendArtifactId-$BackendVersion"
    jarPath = $BackendJar
    jarSha256 = $JarSha256
    jlinkModules = $Modules
    javaVersion = $JavaVersionLine
    javaHome = $JdkHome
    jdkImplementor = $JdkImplementor
    jdkRuntimeVersion = $JdkRuntimeVersion
    jdkVersion = $JdkVersion
    jdkReleaseFile = $JdkReleasePath
    nodeVersion = (& $Node --version)
    npmVersion = (& $Npm --version)
    electronVersion = $DesktopPackage.devDependencies.electron
    electronBuilderVersion = $DesktopPackage.devDependencies.'electron-builder'
    portableExe = $Artifact
    portableExeSha256 = $ExeSha256
}
$BuildInfo | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $BuildDir "build-info.json") -Encoding UTF8

Write-Step "构建完成"
Write-Host "Portable EXE: $Artifact" -ForegroundColor Green
Write-Host "EXE SHA-256:  $ExeSha256" -ForegroundColor Green
Write-Host "JAR SHA-256:  $JarSha256" -ForegroundColor Green
Write-Host "Build info:   $(Join-Path $BuildDir 'build-info.json')"
exit 0
