param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$DesktopDir = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $DesktopDir
$FrontendDir = Join-Path $ProjectRoot "frontend"
$BackendDir = Join-Path $ProjectRoot "backend"
$BuildDir = Join-Path $DesktopDir ".build"
$ReleaseDir = Join-Path $DesktopDir "release"
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
Write-Host "Node: $(& $Node --version)"
Write-Host "npm:  $(& $Npm --version)"
Write-Host "Java: $JavaVersionLine"
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
if (-not (Test-Path -LiteralPath (Join-Path $JreDir "legal"))) {
    throw "jlink 运行时缺少 legal 目录，许可证文件不完整。"
}
Invoke-Native $BundledJava @("-version") $DesktopDir

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
Invoke-Native $Builder @("--win", "portable", "--publish", "never") $DesktopDir

if (-not (Test-Path -LiteralPath $Artifact)) {
    throw "electron-builder 未生成预期 portable EXE：$Artifact"
}
if ((Get-Item -LiteralPath $Artifact).Length -lt 50MB) {
    throw "portable EXE 体积异常，疑似未包含 JRE/JAR：$Artifact"
}

Write-Step "校验发布目录与内置资源"
$UnpackedResources = Join-Path $ReleaseDir "win-unpacked\resources"
$Required = @(
    (Join-Path $UnpackedResources "app.asar"),
    (Join-Path $UnpackedResources "app\bearcoding-backend.jar"),
    (Join-Path $UnpackedResources "jre\bin\java.exe"),
    (Join-Path $UnpackedResources "jre\release")
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
    desktopVersion = $DesktopVersion
    backendArtifact = "$BackendArtifactId-$BackendVersion"
    jarPath = $BackendJar
    jarSha256 = $JarSha256
    jlinkModules = $Modules
    javaVersion = $JavaVersionLine
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
