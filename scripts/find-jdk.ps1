# Resuelve un JDK (con javac) de forma portable entre equipos.
# Uso desde cualquier script:
#   . (Join-Path $root "scripts\find-jdk.ps1")
#   $Jdk = Resolve-Jdk -Explicit $Jdk

function Resolve-Jdk {
    param([string]$Explicit)

    function Test-Jdk([string]$Path) {
        if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
        return (Test-Path (Join-Path $Path "bin\javac.exe"))
    }

    if (Test-Jdk $Explicit)      { return $Explicit }
    if (Test-Jdk $env:JAVA_HOME) { return $env:JAVA_HOME }

    $candidates = New-Object System.Collections.Generic.List[string]

    $patterns = @(
        "C:\Program Files\Java\jdk*",
        "C:\Program Files\Eclipse Adoptium\jdk*",
        "C:\Program Files\Microsoft\jdk*",
        "C:\Program Files\Amazon Corretto\jdk*",
        "C:\Program Files\Zulu\zulu*",
        "C:\Program Files\BellSoft\LibericaJDK*",
        "C:\Program Files (x86)\Java\jdk*",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk*",
        "$env:LOCALAPPDATA\Programs\Microsoft\jdk*",
        "$env:LOCALAPPDATA\Programs\Java\jdk*",
        "$env:USERPROFILE\.jdks\*",
        "$env:USERPROFILE\Downloads\*jdk*",
        "$env:USERPROFILE\scoop\apps\openjdk\current",
        "$env:ProgramData\chocolatey\lib\*\tools\jdk*"
    )

    foreach ($pattern in $patterns) {
        Get-ChildItem -Path $pattern -Directory -ErrorAction SilentlyContinue |
            ForEach-Object {
                $candidates.Add($_.FullName)
                Get-ChildItem -Path $_.FullName -Directory -ErrorAction SilentlyContinue |
                    ForEach-Object { $candidates.Add($_.FullName) }
            }
    }

    # Si `java` está en PATH, derivar el JAVA_HOME y comprobar que sea JDK.
    $javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCmd) {
        $candidates.Add((Split-Path (Split-Path $javaCmd.Source -Parent) -Parent))
    }

    foreach ($candidate in $candidates) {
        if (Test-Jdk $candidate) { return $candidate }
    }
    return $null
}
