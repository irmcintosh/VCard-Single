param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'

$jdk = 'C:\Users\Ian12724\.jdks\jbr-21.0.11'
$gradle = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\android\gradlew.bat'))

if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    throw "JDK not found: $jdk"
}

if (-not (Test-Path $gradle)) {
    throw "Gradle wrapper not found: $gradle"
}

$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:Path
$gradleExitCode = 1

try {
    $env:JAVA_HOME = $jdk
    $env:Path = "$jdk\bin;$previousPath"

    Push-Location (Split-Path -Parent $gradle)
    try {
        & $gradle @GradleArgs
        $gradleExitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:Path = $previousPath
}

exit $gradleExitCode