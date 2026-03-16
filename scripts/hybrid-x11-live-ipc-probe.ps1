param(
    [string]$Serial,
    [int]$ThreadSampleCount = 12,
    [int]$ThreadSampleIntervalMs = 250,
    [string]$CaptureLabel = "internal-hybrid-live-ipc"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$captureRoot = Join-Path $projectRoot "perf-captures"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$captureBaseName = "$CaptureLabel-probe-$timestamp"
$summaryPath = Join-Path $captureRoot "$captureBaseName.summary.txt"
$rawPath = Join-Path $captureRoot "$captureBaseName.raw.txt"

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

function Get-FirstRegexMatch {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text,
        [Parameter(Mandatory = $true)]
        [string]$Pattern
    )

    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if ($match.Success) {
        return $match
    }
    return $null
}

function Get-TopThreadTid {
    param(
        [Parameter(Mandatory = $true)]
        [int]$ThreadOwnerPid,
        [Parameter(Mandatory = $true)]
        [string]$ThreadPattern
    )

    $topOutput = Invoke-Adb -Arguments @("shell", "top", "-H", "-b", "-n", "1", "-p", "$ThreadOwnerPid")
    $line = $topOutput.Output -split "`r?`n" | Where-Object { $_ -match $ThreadPattern } | Select-Object -First 1
    if (-not $line) {
        return $null
    }

    $trimmed = $line.Trim()
    $columns = $trimmed -split "\s+"
    if ($columns.Count -lt 1) {
        return $null
    }
    return [int]$columns[0]
}

function Get-RunAsFileText {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    return (Invoke-Adb -Arguments @("shell", "run-as", "com.termux", "cat", $Path) -AllowFailure).Output
}

function Parse-SocketInodesFromLsof {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LsofText
    )

    $result = New-Object System.Collections.Generic.List[object]
    foreach ($line in ($LsofText -split "`r?`n")) {
        $match = [regex]::Match($line, "^\S+\s+\d+\s+\S+\s+(?<fd>\d+)[A-Za-z]*\s+\S+.*socket:\[(?<inode>\d+)\]")
        if ($match.Success) {
            $result.Add([pscustomobject]@{
                Fd = [int]$match.Groups["fd"].Value
                Inode = [long]$match.Groups["inode"].Value
                Line = $line.Trim()
            })
        }
    }
    return @($result.ToArray())
}

function Get-UnixTableEntryMap {
    $map = @{}
    $unixText = (Invoke-Adb -Arguments @("shell", "cat", "/proc/net/unix")).Output
    foreach ($line in ($unixText -split "`r?`n")) {
        $match = [regex]::Match($line, "^\S+:\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+(?<inode>\d+)(?:\s+(?<path>.*))?$")
        if ($match.Success) {
            $inode = $match.Groups["inode"].Value
            $map[$inode] = [pscustomobject]@{
                Inode = [long]$inode
                Path = $match.Groups["path"].Value.Trim()
                Line = $line.Trim()
            }
        }
    }
    return [pscustomobject]@{
        Text = $unixText
        Map = $map
    }
}

function Get-InetEntryMap {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TableName
    )

    $tableText = (Invoke-Adb -Arguments @("shell", "cat", "/proc/net/$TableName")).Output
    $map = @{}
    foreach ($line in ($tableText -split "`r?`n")) {
        $trimmedLine = $line.Trim()
        if (-not $trimmedLine -or $trimmedLine -like "sl*") {
            continue
        }

        $columns = $trimmedLine -split "\s+"
        if ($columns.Count -ge 10) {
            $inode = $columns[9]
            if ($inode -match "^\d+$") {
                $map[$inode] = $trimmedLine
            }
        }
    }
    return [pscustomobject]@{
        Text = $tableText
        Map = $map
    }
}

function Get-SocketClassification {
    param(
        [Parameter(Mandatory = $true)]
        [long]$Inode,
        [Parameter(Mandatory = $true)]
        [hashtable]$UnixMap,
        [Parameter(Mandatory = $true)]
        [hashtable]$TcpMap,
        [Parameter(Mandatory = $true)]
        [hashtable]$Tcp6Map,
        [Parameter(Mandatory = $true)]
        [hashtable]$UdpMap,
        [Parameter(Mandatory = $true)]
        [hashtable]$Udp6Map
    )

    $inodeKey = "$Inode"
    if ($UnixMap.ContainsKey($inodeKey)) {
        $entry = $UnixMap[$inodeKey]
        $path = $entry.Path
        if (-not $path) {
            $path = "<unnamed>"
        }
        return "unix:$path"
    }
    if ($TcpMap.ContainsKey($inodeKey)) {
        return "tcp"
    }
    if ($Tcp6Map.ContainsKey($inodeKey)) {
        return "tcp6"
    }
    if ($UdpMap.ContainsKey($inodeKey)) {
        return "udp"
    }
    if ($Udp6Map.ContainsKey($inodeKey)) {
        return "udp6"
    }
    return "unmapped"
}

$psOutput = (Invoke-Adb -Arguments @("shell", "toybox", "ps", "-A", "-o", "PID,PPID,NAME,ARGS")).Output

$javaMatch = Get-FirstRegexMatch -Text $psOutput -Pattern "(?m)^\s*(?<pid>\d+)\s+\d+\s+java\s+.*net\.runelite\.client\.RuneLite"
$x11Match = Get-FirstRegexMatch -Text $psOutput -Pattern "(?m)^\s*(?<pid>\d+)\s+\d+\s+termux-x11\s+.*:0"
$virglMatches = [regex]::Matches($psOutput, "(?m)^\s*(?<pid>\d+)\s+\d+\s+virgl_test_server_android\s+virgl_test_server_android")

if (-not $javaMatch -or -not $x11Match -or $virglMatches.Count -eq 0) {
    throw "Could not discover live RuneLite/X11/VirGL processes."
}

$javaPid = [int]$javaMatch.Groups["pid"].Value
$x11Pid = [int]$x11Match.Groups["pid"].Value
$virglPid = [int]$virglMatches[$virglMatches.Count - 1].Groups["pid"].Value

$clientTid = Get-TopThreadTid -ThreadOwnerPid $javaPid -ThreadPattern "\bClient\b"
$xawtTid = Get-TopThreadTid -ThreadOwnerPid $javaPid -ThreadPattern "\bAWT-XAWT\b"
$x11Tid = Get-TopThreadTid -ThreadOwnerPid $x11Pid -ThreadPattern "\bmain\b"
$virglTid = Get-TopThreadTid -ThreadOwnerPid $virglPid -ThreadPattern "\bvirgl_test_serv\b|\bmain\b"

if (-not $clientTid -or -not $xawtTid -or -not $x11Tid -or -not $virglTid) {
    throw "Could not discover one or more hot thread ids."
}

$threadSamples = New-Object System.Collections.Generic.List[object]
for ($i = 0; $i -lt $ThreadSampleCount; $i++) {
    Start-Sleep -Milliseconds $ThreadSampleIntervalMs
    $clientWchan = Get-RunAsFileText -Path "/proc/$javaPid/task/$clientTid/wchan"
    $clientSyscall = Get-RunAsFileText -Path "/proc/$javaPid/task/$clientTid/syscall"
    $xawtWchan = Get-RunAsFileText -Path "/proc/$javaPid/task/$xawtTid/wchan"
    $xawtSyscall = Get-RunAsFileText -Path "/proc/$javaPid/task/$xawtTid/syscall"
    $threadSamples.Add([pscustomobject]@{
        Index = $i
        ClientWchan = $clientWchan.Trim()
        ClientSyscall = $clientSyscall.Trim()
        XawtWchan = $xawtWchan.Trim()
        XawtSyscall = $xawtSyscall.Trim()
    })
}

$clientReadSample = $threadSamples | Where-Object { $_.ClientSyscall -match "^63\s+0x(?<fd>[0-9a-fA-F]+)\b" } | Select-Object -First 1
$clientBlockingFd = $null
if ($clientReadSample) {
    $fdHex = [regex]::Match($clientReadSample.ClientSyscall, "^63\s+0x(?<fd>[0-9a-fA-F]+)\b").Groups["fd"].Value
    $clientBlockingFd = [Convert]::ToInt32($fdHex, 16)
}

$javaLsof = (Invoke-Adb -Arguments @("shell", "run-as", "com.termux", "lsof", "-p", "$javaPid")).Output
$x11Lsof = (Invoke-Adb -Arguments @("shell", "run-as", "com.termux", "lsof", "-p", "$x11Pid")).Output
$virglLsof = (Invoke-Adb -Arguments @("shell", "run-as", "com.termux", "lsof", "-p", "$virglPid")).Output

$javaSockets = Parse-SocketInodesFromLsof -LsofText $javaLsof
$x11Sockets = Parse-SocketInodesFromLsof -LsofText $x11Lsof
$virglSockets = Parse-SocketInodesFromLsof -LsofText $virglLsof

$unixTable = Get-UnixTableEntryMap
$tcpTable = Get-InetEntryMap -TableName "tcp"
$tcp6Table = Get-InetEntryMap -TableName "tcp6"
$udpTable = Get-InetEntryMap -TableName "udp"
$udp6Table = Get-InetEntryMap -TableName "udp6"

$javaSocketSummaries = foreach ($socket in $javaSockets | Sort-Object Fd) {
    [pscustomobject]@{
        Fd = $socket.Fd
        Inode = $socket.Inode
        Classification = Get-SocketClassification -Inode $socket.Inode -UnixMap $unixTable.Map -TcpMap $tcpTable.Map -Tcp6Map $tcp6Table.Map -UdpMap $udpTable.Map -Udp6Map $udp6Table.Map
        Line = $socket.Line
    }
}

$x11SocketSummaries = foreach ($socket in $x11Sockets | Sort-Object Fd) {
    [pscustomobject]@{
        Fd = $socket.Fd
        Inode = $socket.Inode
        Classification = Get-SocketClassification -Inode $socket.Inode -UnixMap $unixTable.Map -TcpMap $tcpTable.Map -Tcp6Map $tcp6Table.Map -UdpMap $udpTable.Map -Udp6Map $udp6Table.Map
        Line = $socket.Line
    }
}

$virglSocketSummaries = foreach ($socket in $virglSockets | Sort-Object Fd) {
    [pscustomobject]@{
        Fd = $socket.Fd
        Inode = $socket.Inode
        Classification = Get-SocketClassification -Inode $socket.Inode -UnixMap $unixTable.Map -TcpMap $tcpTable.Map -Tcp6Map $tcp6Table.Map -UdpMap $udpTable.Map -Udp6Map $udp6Table.Map
        Line = $socket.Line
    }
}

$clientSocketSummary = $null
if ($null -ne $clientBlockingFd) {
    $clientSocketSummary = $javaSocketSummaries | Where-Object { $_.Fd -eq $clientBlockingFd } | Select-Object -First 1
}

$javaUnnamedUnixSockets = @($javaSocketSummaries | Where-Object { $_.Classification -eq "unix:<unnamed>" })
$x11NamedUnixSockets = @($x11SocketSummaries | Where-Object { $_.Classification -like "unix:*X11-unix/X0*" })
$virglNamedUnixSockets = @($virglSocketSummaries | Where-Object { $_.Classification -like "unix:*.virgl_test*" })

$virglInference = $null
if ($clientSocketSummary -and $virglNamedUnixSockets.Count -gt 0) {
    $candidate = $virglNamedUnixSockets | Sort-Object { [math]::Abs($_.Inode - $clientSocketSummary.Inode) } | Select-Object -First 1
    $delta = [math]::Abs($candidate.Inode - $clientSocketSummary.Inode)
    if ($delta -le 4) {
        $virglInference = "client fd $($clientSocketSummary.Fd) inode $($clientSocketSummary.Inode) is most likely the Java-side peer of virgl fd $($candidate.Fd) inode $($candidate.Inode) ($($candidate.Classification)); inode delta=$delta"
    }
}

$summaryLines = New-Object System.Collections.Generic.List[string]
$summaryLines.Add("capture_base=$captureBaseName")
$summaryLines.Add("java_pid=$javaPid")
$summaryLines.Add("x11_pid=$x11Pid")
$summaryLines.Add("virgl_pid=$virglPid")
$summaryLines.Add("client_tid=$clientTid")
$summaryLines.Add("xawt_tid=$xawtTid")
$summaryLines.Add("x11_hot_tid=$x11Tid")
$summaryLines.Add("virgl_hot_tid=$virglTid")
$summaryLines.Add("thread_sample_count=$ThreadSampleCount")
$summaryLines.Add("thread_sample_interval_ms=$ThreadSampleIntervalMs")
$summaryLines.Add("client_blocking_fd=$clientBlockingFd")
if ($clientSocketSummary) {
    $summaryLines.Add("client_blocking_fd_inode=$($clientSocketSummary.Inode)")
    $summaryLines.Add("client_blocking_fd_classification=$($clientSocketSummary.Classification)")
    $summaryLines.Add("client_blocking_fd_lsof_line=$($clientSocketSummary.Line)")
}
if ($virglInference) {
    $summaryLines.Add("virgl_inference=$virglInference")
}
$summaryLines.Add("java_unix_socket_count=$(@($javaSocketSummaries | Where-Object { $_.Classification -like 'unix:*' }).Count)")
$summaryLines.Add("java_unmapped_socket_count=$(@($javaSocketSummaries | Where-Object { $_.Classification -eq 'unmapped' }).Count)")
$summaryLines.Add("java_tcp_socket_count=$(@($javaSocketSummaries | Where-Object { $_.Classification -eq 'tcp' -or $_.Classification -eq 'tcp6' }).Count)")
$summaryLines.Add("x11_named_unix_socket_count=$($x11NamedUnixSockets.Count)")
$summaryLines.Add("virgl_named_unix_socket_count=$($virglNamedUnixSockets.Count)")
$summaryLines.Add("java_unix_sockets=$((@($javaSocketSummaries | Where-Object { $_.Classification -like 'unix:*' } | ForEach-Object { 'fd=' + $_.Fd + ',inode=' + $_.Inode + ',class=' + $_.Classification }) -join '; '))")
$summaryLines.Add("java_unmapped_sockets=$((@($javaSocketSummaries | Where-Object { $_.Classification -eq 'unmapped' } | ForEach-Object { 'fd=' + $_.Fd + ',inode=' + $_.Inode }) -join '; '))")
$summaryLines.Add("java_tcp_sockets=$((@($javaSocketSummaries | Where-Object { $_.Classification -eq 'tcp' -or $_.Classification -eq 'tcp6' } | ForEach-Object { 'fd=' + $_.Fd + ',inode=' + $_.Inode + ',class=' + $_.Classification }) -join '; '))")
$summaryLines.Add("x11_named_unix_sockets=$((@($x11NamedUnixSockets | ForEach-Object { 'fd=' + $_.Fd + ',inode=' + $_.Inode + ',class=' + $_.Classification }) -join '; '))")
$summaryLines.Add("virgl_named_unix_sockets=$((@($virglNamedUnixSockets | ForEach-Object { 'fd=' + $_.Fd + ',inode=' + $_.Inode + ',class=' + $_.Classification }) -join '; '))")

foreach ($sample in $threadSamples) {
    $summaryLines.Add("thread_sample_$($sample.Index)=clientW=$($sample.ClientWchan) | clientS=$($sample.ClientSyscall) | xawtW=$($sample.XawtWchan) | xawtS=$($sample.XawtSyscall)")
}

Set-Content -Path $summaryPath -Value $summaryLines

$rawLines = New-Object System.Collections.Generic.List[string]
$rawLines.Add("[ps]")
$rawLines.Add($psOutput)
$rawLines.Add("")
$rawLines.Add("[java_lsof]")
$rawLines.Add($javaLsof)
$rawLines.Add("")
$rawLines.Add("[x11_lsof]")
$rawLines.Add($x11Lsof)
$rawLines.Add("")
$rawLines.Add("[virgl_lsof]")
$rawLines.Add($virglLsof)
$rawLines.Add("")
$rawLines.Add("[proc_net_unix]")
$rawLines.Add($unixTable.Text)
$rawLines.Add("")
$rawLines.Add("[proc_net_tcp]")
$rawLines.Add($tcpTable.Text)
$rawLines.Add("")
$rawLines.Add("[proc_net_tcp6]")
$rawLines.Add($tcp6Table.Text)
$rawLines.Add("")
$rawLines.Add("[proc_net_udp]")
$rawLines.Add($udpTable.Text)
$rawLines.Add("")
$rawLines.Add("[proc_net_udp6]")
$rawLines.Add($udp6Table.Text)

Set-Content -Path $rawPath -Value $rawLines

$summaryLines | ForEach-Object { Write-Host $_ }
Write-Host "summary_path=$summaryPath"
Write-Host "raw_path=$rawPath"
