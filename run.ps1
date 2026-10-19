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
& $java -cp "$root\out\classes;$root\lib\*" com.dragonshot.sim.App
