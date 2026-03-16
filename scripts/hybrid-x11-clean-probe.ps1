param(
    [ValidateSet("stock", "hybrid", "internal-hybrid")]
    [string]$Variant = "stock",

    [ValidateSet(
        "inspect",
        "glxgears-windowed",
        "glxgears-fullscreen",
        "glxgears-virgl-windowed",
        "glxgears-virgl-fullscreen"
    )]
    [string]$Mode = "inspect",

    [string]$ProbeShape = "launcher-faithful",

    [int]$TimeoutSeconds = 90,

    [int]$PostCompletionDelaySeconds = 3,

    [string]$Serial,

    [switch]$NoCleanStart
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$captureRoot = Join-Path $projectRoot "perf-captures"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$cleanSuffix = if ($NoCleanStart) { "" } else { "-clean" }
$modeSlug = $Mode.Replace("glxgears-", "").Replace("-", "-")
$captureBaseName = "{0}-combined-{1}{2}-{3}" -f $Variant, $modeSlug, $cleanSuffix, $timestamp
$logPath = Join-Path $captureRoot "$captureBaseName.logcat.txt"
$summaryPath = Join-Path $captureRoot "$captureBaseName.summary.txt"

New-Item -ItemType Directory -Path $captureRoot -Force | Out-Null

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$AllowFailure
    )

    $fullArguments = @()
    if ($Serial) {
        $fullArguments += @("-s", $Serial)
    }
    $fullArguments += $Arguments

    $output = & adb @fullArguments 2>&1
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "adb $($fullArguments -join ' ') failed with exit code $exitCode`n$output"
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = ($output | Out-String).TrimEnd()
    }
}

function Get-FilteredLogcatText {
    $result = Invoke-Adb -Arguments @(
        "logcat", "-d", "-v", "threadtime",
        "RLT:V",
        "LorieNative:D",
        "BufferQueueProducer:I",
        "LayerHistory:I",
        "ActivityManager:I",
        "*:S"
    ) -AllowFailure
    return $result.Output
}

function Get-LogSummary {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Lines
    )

    $attachIndex = -1
    for ($i = 0; $i -lt $Lines.Length; $i++) {
        if ($Lines[$i] -match "attached xConnection fd=") {
            $attachIndex = $i
            break
        }
    }

    $newClientIndices = @()
    for ($i = 0; $i -lt $Lines.Length; $i++) {
        if ($Lines[$i] -match "New client connection!") {
            $newClientIndices += $i
        }
    }

    $beforeAttach = if ($attachIndex -ge 0) {
        @($newClientIndices | Where-Object { $_ -lt $attachIndex }).Count
    } else {
        $null
    }

    $afterAttach = if ($attachIndex -ge 0) {
        @($newClientIndices | Where-Object { $_ -gt $attachIndex }).Count
    } else {
        $null
    }

    $displayReady = @($Lines | Where-Object { $_ -match "DISPLAY ready" }).Count -gt 0
    $rendererMatches = @($Lines | Where-Object { $_ -match "OpenGL renderer string:" })
    $fpsMatches = @($Lines | Where-Object { $_ -match "frames in 5\.0 seconds =" })
    $combinedMatches = @($Lines | Where-Object { $_ -match "HybridX11TestReceiver: combined probe mode=" })
    $rendererLine = if ($rendererMatches.Count -gt 0) { $rendererMatches[-1] } else { $null }
    $fpsLine = if ($fpsMatches.Count -gt 0) { $fpsMatches[-1] } else { $null }
    $combinedResult = if ($combinedMatches.Count -gt 0) { $combinedMatches[-1] } else { $null }

    return [pscustomobject]@{
        NewClientConnectionCount = $newClientIndices.Count
        AttachSeen = $attachIndex -ge 0
        BeforeAttach = $beforeAttach
        AfterAttach = $afterAttach
        DisplayReady = $displayReady
        RendererLine = $rendererLine
        FpsLine = $fpsLine
        CombinedResult = $combinedResult
    }
}

Write-Host "capture_base=$captureBaseName"
Write-Host "log_path=$logPath"
Write-Host "summary_path=$summaryPath"

Invoke-Adb -Arguments @("get-state") | Out-Null

if (-not $NoCleanStart) {
    Write-Host "forcing clean start for com.termux, com.termux.x11, and com.runelitetablet"
    Invoke-Adb -Arguments @("shell", "am", "force-stop", "com.termux") -AllowFailure | Out-Null
    Invoke-Adb -Arguments @("shell", "am", "force-stop", "com.termux.x11") -AllowFailure | Out-Null
    Invoke-Adb -Arguments @("shell", "am", "force-stop", "com.runelitetablet") -AllowFailure | Out-Null
    Start-Sleep -Seconds 1
}

Write-Host "clearing logcat"
Invoke-Adb -Arguments @("logcat", "-c") | Out-Null

$broadcastArguments = @(
    "shell", "am", "broadcast",
    "-a", "com.runelitetablet.action.START_AND_RUN_HYBRID_X11_CLIENT",
    "-n", "com.runelitetablet/.presentation.hybrid.HybridX11TestReceiver",
    "--es", "client_mode", $Mode,
    "--es", "probe_shape", $ProbeShape,
    "--es", "presentation_variant", $Variant
)
if ($Variant -eq "hybrid") {
    $broadcastArguments += @("--es", "override_package", "com.runelitetablet")
}

Write-Host "dispatching probe variant=$Variant mode=$Mode probe_shape=$ProbeShape"
$broadcastResult = Invoke-Adb -Arguments $broadcastArguments
Write-Host $broadcastResult.Output

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$logText = ""
$sawCompletion = $false
$requiresFps = $Mode.StartsWith("glxgears-")
do {
    Start-Sleep -Seconds 2
    $logText = Get-FilteredLogcatText
    $sawCompletion = $logText -match "HybridX11TestReceiver: combined probe mode="
    $sawFps = $logText -match "frames in 5\.0 seconds ="
    if ($requiresFps) {
        if ($sawCompletion -and $sawFps) {
            break
        }
    } elseif ($sawCompletion) {
        break
    }
} while ((Get-Date) -lt $deadline)

if ($sawCompletion -and $PostCompletionDelaySeconds -gt 0) {
    Start-Sleep -Seconds $PostCompletionDelaySeconds
    $logText = Get-FilteredLogcatText
}

if ([string]::IsNullOrWhiteSpace($logText)) {
    $logText = Get-FilteredLogcatText
}

Set-Content -Path $logPath -Value $logText

$lines = Get-Content $logPath
$summary = Get-LogSummary -Lines $lines

$summaryLines = @(
    "capture_base=$captureBaseName"
    "variant=$Variant"
    "mode=$Mode"
    "probe_shape=$ProbeShape"
    "clean_start=$([bool](-not $NoCleanStart))"
    "new_client_connection_count=$($summary.NewClientConnectionCount)"
    "attach_seen=$($summary.AttachSeen)"
    "before_attach=$($summary.BeforeAttach)"
    "after_attach=$($summary.AfterAttach)"
    "display_ready=$($summary.DisplayReady)"
    "renderer_line=$($summary.RendererLine)"
    "fps_line=$($summary.FpsLine)"
    "combined_result=$($summary.CombinedResult)"
    "log_path=$logPath"
)

Set-Content -Path $summaryPath -Value $summaryLines
$summaryLines | ForEach-Object { Write-Host $_ }

if (-not ($logText -match "HybridX11TestReceiver: combined probe mode=")) {
    throw "Probe did not report completion before timeout. Log captured at $logPath"
}
