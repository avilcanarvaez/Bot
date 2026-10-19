param(
    [string]$Jdk = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $root "scripts\find-jdk.ps1")
$Jdk = Resolve-Jdk -Explicit $Jdk
if (-not $Jdk) {
    throw "No se encontro un JDK (javac). Define JAVA_HOME o pasa -Jdk <ruta>."
}

$javac = Join-Path $Jdk "bin\javac.exe"
$classes = Join-Path $root "out\classes"
$testClasses = Join-Path $root "out\test-classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
New-Item -ItemType Directory -Force -Path $testClasses | Out-Null

$mainSources = @(Get-ChildItem (Join-Path $root "src\main\java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName })
$testSources = @(Get-ChildItem (Join-Path $root "src\test\java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName })

Write-Host "Compilando $($mainSources.Count) archivos principales..."
& $javac -encoding UTF-8 -Xlint:none -d $classes -cp "$root\lib\*" @mainSources
if ($LASTEXITCODE -ne 0) { throw "Fallo la compilacion principal." }

Write-Host "Compilando $($testSources.Count) archivos de prueba..."
& $javac -encoding UTF-8 -Xlint:none -d $testClasses -cp "$classes;$root\lib\*" @testSources
if ($LASTEXITCODE -ne 0) { throw "Fallo la compilacion de pruebas." }

Write-Host "Build OK."
Write-Host "  clases:      $classes"
Write-Host "  test-classes:$testClasses"
