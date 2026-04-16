#requires -Version 5.1
<#
.SYNOPSIS
    Create a GitHub Issue following the Tablite issue convention.
.DESCRIPTION
    Validates title grammar (type/scope), priority, and body minimum length,
    appends the required Reason trailer, applies labels on three axes
    (type / scope / priority) plus optional historical / needs-repro, and
    opens (or opens + immediately closes, for historical records) the issue
    via gh. Returns the issue URL.
.PARAMETER Type
    One of: feat, fix, refactor, perf, test, docs, chore, ci, build,
    blocker, security. Becomes the "type:<value>" label and the subject
    prefix.
.PARAMETER Scope
    Must match an entry in scripts/git/valid-scopes.txt. Becomes the
    "scope:<value>" label and goes in parens in the subject.
.PARAMETER Subject
    Imperative-mood subject (<=72 chars). The helper prepends "<type>(<scope>): ".
.PARAMETER Priority
    One of: p0, p1, p2, p3.
.PARAMETER Body
    Markdown body containing Problem / Decision / Tradeoff / Evidence
    sections. Must be >=40 non-whitespace chars.
.PARAMETER Reason
    One-line forcing function. Required. Appended as a Reason: trailer.
.PARAMETER Refs
    Optional one-line list of references (path:line, #issue, commit sha).
    Appended as a Refs: trailer.
.PARAMETER FollowUp
    Optional remaining work. Appended as Follow-up: trailer.
.PARAMETER Historical
    When set, labels with "historical" and closes immediately with a
    "migrated on YYYY-MM-DD" close comment.
.PARAMETER NeedsRepro
    When set, adds the "needs-repro" label (for issues whose evidence
    is incomplete).
.PARAMETER Repo
    Target repo. Defaults to RobertoChavez2433/runelite_tablet_workaround.
.EXAMPLE
    ./tools/create-issue.ps1 -Type fix -Scope setup -Subject "reconcile ABSENT must clear stateStore" -Priority p1 -Body @'
Problem: ...
Decision: ...
Tradeoff: ...
Evidence: ...
'@ -Reason "affected step silently skips after reconcile" -Refs "runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt"
#>
param(
    [Parameter(Mandatory)]
    [ValidateSet("feat", "fix", "refactor", "perf", "test", "docs", "chore", "ci", "build", "blocker", "security")]
    [string]$Type,

    [Parameter(Mandatory)]
    [string]$Scope,

    [Parameter(Mandatory)]
    [string]$Subject,

    [Parameter(Mandatory)]
    [ValidateSet("p0", "p1", "p2", "p3")]
    [string]$Priority,

    [Parameter(Mandatory)]
    [string]$Body,

    [Parameter(Mandatory)]
    [string]$Reason,

    [string]$Refs,
    [string]$FollowUp,

    [switch]$Historical,
    [switch]$NeedsRepro,

    [string]$Repo = "RobertoChavez2433/runelite_tablet_workaround"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$scopesFile = Join-Path $repoRoot "scripts/git/valid-scopes.txt"
if (-not (Test-Path $scopesFile)) {
    throw "valid-scopes.txt missing at $scopesFile"
}
$validScopes = Get-Content $scopesFile | Where-Object { $_ -and -not $_.StartsWith("#") }
if ($validScopes -notcontains $Scope) {
    throw "unknown scope '$Scope'; add it to scripts/git/valid-scopes.txt if intentional. Valid: $($validScopes -join ', ')"
}

if ($Subject.StartsWith("-")) {
    throw "Subject must not start with '-' (CLI flag injection guard)"
}

$title = "$Type($Scope): $Subject"
if ($title.Length -gt 72) {
    Write-Warning "Title is $($title.Length) chars (>72 recommended)"
}

$bodyCharCount = ($Body -replace '\s', '').Length
if ($bodyCharCount -lt 40) {
    throw "body has $bodyCharCount non-whitespace chars; need >=40 across Problem/Decision/Tradeoff/Evidence"
}

if ([string]::IsNullOrWhiteSpace($Reason)) {
    throw "Reason trailer is required"
}

$trailerLines = @("Reason: $Reason")
if ($Refs)     { $trailerLines += "Refs: $Refs" }
if ($FollowUp) { $trailerLines += "Follow-up: $FollowUp" }

$fullBody = $Body.TrimEnd() + "`n`n" + ($trailerLines -join "`n") + "`n"

$labels = @("type:$Type", "scope:$Scope", $Priority)
if ($Historical) { $labels += "historical" }
if ($NeedsRepro) { $labels += "needs-repro" }

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Write-Host "[create-issue] $title" -ForegroundColor Cyan

$issueUrl = $fullBody | gh issue create `
    --repo $Repo `
    --title $title `
    --label ($labels -join ",") `
    --body-file -

if ($LASTEXITCODE -ne 0) { throw "gh issue create failed" }

Write-Host "[create-issue] opened: $issueUrl" -ForegroundColor Green

if ($Historical) {
    $issueNum = [regex]::Match($issueUrl, '/issues/(\d+)').Groups[1].Value
    if (-not $issueNum) { throw "could not parse issue number from $issueUrl" }
    $closeComment = "Migrated from .claude/defects/ on $(Get-Date -Format 'yyyy-MM-dd'). Historical record; no action required.`n`nResolved-by: pre-existing code (see body evidence)"
    gh issue close $issueNum --repo $Repo --comment $closeComment --reason "completed" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "gh issue close failed" }
    Write-Host "[create-issue] closed as historical: #$issueNum" -ForegroundColor DarkGray
}

Write-Output $issueUrl
