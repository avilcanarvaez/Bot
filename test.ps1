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
& (Join-Path $root "build.ps1") -Jdk $Jdk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$java = Join-Path $Jdk "bin\java.exe"
$classPath = "$root\out\classes;$root\out\test-classes;$root\lib\jna-5.14.0.jar;$root\lib\jna-platform-5.14.0.jar"

Write-Host "Ejecutando pruebas JUnit..."
& $java -jar (Join-Path $root "lib\junit-platform-console-standalone-1.11.4.jar") `
    execute --class-path $classPath --scan-class-path --details=tree --disable-banner
exit $LASTEXITCODE
