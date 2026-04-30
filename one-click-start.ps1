param(
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

$AppHome = Split-Path -Parent $MyInvocation.MyCommand.Path
$JarName = "qian-xun-pro-wechat-http-demo-1.0.0.jar"
$JarPath = Join-Path $AppHome "target\$JarName"
$Port = 8989
$ContextPath = "/wechat"
$LogDir = Join-Path $AppHome "logs"

function Write-Step {
    param([string]$Message)
    Write-Output "[INFO] $Message"
}

function Find-Executable {
    param(
        [string[]]$Candidates,
        [string]$CommandName
    )

    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    $command = Get-Command $CommandName -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) {
        return $command.Source
    }

    return $null
}

function Get-LatestWriteTime {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        return [datetime]::MinValue
    }

    $item = Get-Item $Path
    if (-not $item.PSIsContainer) {
        return $item.LastWriteTime
    }

    $latest = Get-ChildItem -Path $Path -Recurse -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($latest) {
        return $latest.LastWriteTime
    }

    return $item.LastWriteTime
}

function Test-JarOutdated {
    if (-not (Test-Path $JarPath)) {
        return $true
    }

    $jarTime = (Get-Item $JarPath).LastWriteTime
    $latestSourceTime = @(
        Get-LatestWriteTime (Join-Path $AppHome "pom.xml")
        Get-LatestWriteTime (Join-Path $AppHome "src\main\java")
        Get-LatestWriteTime (Join-Path $AppHome "src\main\resources")
    ) | Sort-Object -Descending | Select-Object -First 1

    return $latestSourceTime -gt $jarTime
}

function Stop-ProjectProcessOnPort {
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1

    if (-not $listener) {
        return
    }

    $pidOnPort = [int]$listener.OwningProcess
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$pidOnPort" -ErrorAction SilentlyContinue
    $commandLine = if ($processInfo) { [string]$processInfo.CommandLine } else { "" }

    $isProjectProcess = $commandLine.Contains($JarName) -or $commandLine.Contains($AppHome)
    if (-not $isProjectProcess) {
        throw "Port $Port is occupied by another process. PID=$pidOnPort CommandLine=$commandLine"
    }

    Write-Step "Existing project process found on port $Port, stopping PID $pidOnPort..."
    Stop-Process -Id $pidOnPort -Force

    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 500
        $stillListening = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if (-not $stillListening) {
            Write-Step "Old process stopped."
            return
        }
    }

    throw "Old process PID $pidOnPort did not release port $Port in time."
}

function Wait-ServiceReady {
    param(
        [int]$ProcessId,
        [string]$OutLog,
        [string]$ErrLog
    )

    $healthUrl = "http://127.0.0.1:$Port$ContextPath/api/runtime-status"
    Write-Step "Waiting for service health check: $healthUrl"

    for ($i = 0; $i -lt 60; $i++) {
        $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
        if (-not $process) {
            throw "Service process exited before it became ready. Check logs: $OutLog $ErrLog"
        }

        try {
            $response = Invoke-RestMethod -Uri $healthUrl -Method Get -TimeoutSec 3
            if ($response -and $response.success -eq $true) {
                return $response
            }
        } catch {
            Start-Sleep -Seconds 1
        }
    }

    throw "Service did not become ready in 60 seconds. Check logs: $OutLog $ErrLog"
}

try {
    Write-Step "App home: $AppHome"
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

    $javaCandidates = @(
        $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" }),
        "D:\devtools\jdk\bin\java.exe",
        "$env:USERPROFILE\tools\jdk21\jdk-21.0.10+7\bin\java.exe",
        "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\java.exe"
    )
    $java = Find-Executable -Candidates $javaCandidates -CommandName "java.exe"
    if (-not $java) {
        throw "JDK 21 java.exe was not found. Please install JDK 21 or set JAVA_HOME."
    }
    Write-Step "Java: $java"

    $needBuild = (-not $NoBuild) -and (Test-JarOutdated)
    if ($needBuild) {
        Stop-ProjectProcessOnPort

        $mavenCandidates = @(
            $(if ($env:MAVEN_HOME) { Join-Path $env:MAVEN_HOME "bin\mvn.cmd" }),
            "$env:USERPROFILE\tools\maven\apache-maven-3.9.9\bin\mvn.cmd"
        )
        $maven = Find-Executable -Candidates $mavenCandidates -CommandName "mvn.cmd"
        if (-not $maven) {
            $maven = Find-Executable -Candidates @() -CommandName "mvn"
        }
        if (-not $maven) {
            throw "Maven was not found and the jar is missing/outdated. Please install Maven or run with an existing jar."
        }

        Write-Step "Jar is missing or older than source. Building package..."
        $env:PATH = "$(Split-Path -Parent $java);$(Split-Path -Parent $maven);$env:PATH"
        if (-not $env:MAVEN_OPTS) {
            $env:MAVEN_OPTS = "-Xms256m -Xmx512m"
        }

        $mavenArgs = @("-DskipTests", "package")
        $settingsPath = Join-Path $AppHome "settings-temp.xml"
        if (Test-Path $settingsPath) {
            $mavenArgs += @("-s", $settingsPath)
        }

        $buildProcess = Start-Process -FilePath $maven -ArgumentList $mavenArgs -WorkingDirectory $AppHome -NoNewWindow -Wait -PassThru
        if ($buildProcess.ExitCode -ne 0) {
            throw "Maven package failed with exit code $($buildProcess.ExitCode)."
        }
    } else {
        Write-Step "Jar is up to date, skipping build."
    }

    if (-not (Test-Path $JarPath)) {
        throw "Jar not found after build check: $JarPath"
    }

    Stop-ProjectProcessOnPort

    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $outLog = Join-Path $LogDir "service-$timestamp.out.log"
    $errLog = Join-Path $LogDir "service-$timestamp.err.log"

    $javaArgs = @(
        "-Ddebug=false",
        "-Dlogging.level.org.springframework=warn",
        "-Dlogging.level.org.springframework.web=warn",
        "-Dlogging.level.org.springframework.jdbc=warn",
        "-jar",
        $JarPath
    )

    Write-Step "Starting service..."
    $serviceProcess = Start-Process -FilePath $java -ArgumentList $javaArgs -WorkingDirectory $AppHome `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden -PassThru

    $health = Wait-ServiceReady -ProcessId $serviceProcess.Id -OutLog $outLog -ErrLog $errLog

    Write-Output ""
    Write-Output "[OK] Service is running."
    Write-Output "[OK] PID      : $($serviceProcess.Id)"
    Write-Output "[OK] URL      : http://127.0.0.1:$Port$ContextPath/api/runtime-status"
    Write-Output "[OK] Source   : Redis queue wechat_messages"
    Write-Output "[OK] Logs     : $outLog"
    Write-Output "[OK] Status   : raw=$($health.data.rawOrderCount), success=$($health.data.parseSuccessCount), failed=$($health.data.parseFailedCount), buffer=$($health.data.orderBuffer)"
    exit 0
} catch {
    Write-Output ""
    Write-Output "[ERROR] $($_.Exception.Message)"
    exit 1
}
