param(
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

# 1. Build JAR
if (-not $NoBuild) {
    Write-Host "=== Building JAR ===" -ForegroundColor Cyan
    $env:Path = "D:\MyDocument\depend\Java21\bin;D:\MyDocument\depend\apache-maven-3.6.3-bin\apache-maven-3.6.3\bin;$env:Path"
    $env:JAVA_HOME = "D:\MyDocument\depend\Java21"
    mvn clean package -DskipTests -q
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
}

# 2. Find JAR
$jarPath = Get-ChildItem -Path "$ProjectRoot\target\*.jar" | Where-Object { $_.Name -notlike "*.jar.original" } | Select-Object -First 1
if (-not $jarPath) { throw "No JAR found in target/" }
Write-Host "JAR: $($jarPath.Name)" -ForegroundColor Cyan

# 3. Create staging dir with only the JAR
$stageDir = "$ProjectRoot\target\staging"
New-Item -ItemType Directory -Path $stageDir -Force | Out-Null
Copy-Item $jarPath.FullName -Destination "$stageDir\$($jarPath.Name)" -Force

# 4. Find jpackage
$jpackage = Get-Command "jpackage" -ErrorAction SilentlyContinue
if (-not $jpackage) {
    $jpackage = Get-ChildItem -Path "$env:JAVA_HOME\bin\jpackage.exe" -ErrorAction SilentlyContinue
}
if (-not $jpackage) { throw "jpackage not found." }

# 5. Create output dir
$outputDir = "$ProjectRoot\dist"
if (Test-Path $outputDir) { Remove-Item -Path $outputDir -Recurse -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

# 6. Run jpackage
Write-Host "=== Packaging EXE ===" -ForegroundColor Cyan
& $jpackage.Source `
    --input $stageDir `
    --main-jar $jarPath.Name `
    --main-class org.springframework.boot.loader.launch.JarLauncher `
    --name "HanimeDownloader" `
    --type "exe" `
    --app-version "1.0.0" `
    --java-options "-Xmx512m" `
    --java-options "-Djava.awt.headless=true" `
    --dest $outputDir `
    --vendor "HanimeDownloader" `
    --win-console `
    --win-dir-chooser `
    --win-menu `
    --win-shortcut `
    --win-per-user-install `
    --verbose

if ($LASTEXITCODE -eq 0) {
    $installer = Get-ChildItem -Path $outputDir -Filter "*.exe" | Select-Object -First 1
    Write-Host "=== SUCCESS ===" -ForegroundColor Green
    Write-Host "Installer: $($installer.FullName)" -ForegroundColor Green
    Write-Host "Size: $([math]::Round($installer.Length/1MB, 1)) MB" -ForegroundColor Green
} else {
    Write-Host "=== FAILED ===" -ForegroundColor Red
}
