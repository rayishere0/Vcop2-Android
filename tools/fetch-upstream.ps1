# SPDX-License-Identifier: GPL-2.0-or-later
# Fetch pinned upstream vcop2-vr, verify HEAD, apply the overlay patches in
# series order, and overlay the flat/ files. Fails loudly on any mismatch.
#
# Usage: .\fetch-upstream.ps1 -BuildDir <path> [-OverlayRoot <path>]
#   -BuildDir    Directory that will hold the upstream checkout (created if
#                missing). Must contain no spaces (MAME build rule).
#   -OverlayRoot Directory holding patches/, flat/, upstream-lock.json.
#                Defaults to the parent of this script's directory.
#
# All paths are parameters: this script has no drive-letter defaults.
param(
    [Parameter(Mandatory = $true)][string]$BuildDir,
    [string]$OverlayRoot = (Split-Path -Parent $PSScriptRoot)
)
$ErrorActionPreference = 'Stop'

$PIN = 'def8d32914911d03c9c8d24680bf21b3f869524c'
$URL = 'https://github.com/vr4all-standalone/vcop2-vr'

function Run-Git {
    param([string[]]$Arguments)
    & git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

$OverlayRoot = (Resolve-Path $OverlayRoot).Path
$lockFile = Join-Path $OverlayRoot 'upstream-lock.json'
if (-not (Select-String -Quiet -Pattern $PIN -Path $lockFile)) {
    throw "Pin $PIN not found in $lockFile"
}
if (-not (Select-String -Quiet -Pattern ([regex]::Escape($URL)) -Path $lockFile)) {
    throw "URL $URL not found in $lockFile"
}

New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
$BuildDir = (Resolve-Path $BuildDir).Path
if ($BuildDir -match '\s') {
    throw "Build dir must contain no spaces: $BuildDir"
}

$Src = Join-Path $BuildDir 'vcop2-vr'
if (Test-Path $Src) {
    throw "$Src already exists; delete it to re-fetch"
}

Write-Host "fetch-upstream: cloning $URL ..."
Run-Git @('clone', $URL, $Src)
Run-Git @('-C', $Src, 'checkout', '-q', $PIN)
$head = (& git -C $Src rev-parse HEAD).Trim()
if ($head -ne $PIN) {
    throw "HEAD mismatch after checkout: got $head, want $PIN"
}
Write-Host "fetch-upstream: HEAD verified: $head"

Write-Host 'fetch-upstream: applying patches in series order ...'
$seriesFile = Join-Path $OverlayRoot 'patches\series'
Get-Content $seriesFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $patchFile = Join-Path $OverlayRoot "patches\$line"
    if (-not (Test-Path $patchFile)) {
        throw "Patch file missing: $patchFile"
    }
    Run-Git @('-C', $Src, 'apply', '--check', $patchFile)
    Run-Git @('-C', $Src, 'apply', $patchFile)
    Write-Host "fetch-upstream: applied $line"
}

Write-Host 'fetch-upstream: overlaying flat/ files ...'
$flatRoot = Join-Path $OverlayRoot 'flat'
Get-ChildItem -Recurse -File $flatRoot | ForEach-Object {
    $rel = $_.FullName.Substring($flatRoot.Length + 1)
    $dest = Join-Path $Src $rel
    $parent = Split-Path -Parent $dest
    if ($parent -and -not (Test-Path $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    Copy-Item $_.FullName $dest -Force
}
if (-not (Test-Path (Join-Path $Src 'CMakeLists.txt'))) {
    throw 'Overlay failed: CMakeLists.txt missing'
}
if (-not (Test-Path (Join-Path $Src 'native\flat_host.cpp'))) {
    throw 'Overlay failed: native\flat_host.cpp missing'
}

Write-Host 'fetch-upstream: verifying resulting tree state ...'
$expectedList = [System.Collections.Generic.List[string]]::new()
$expectedList.Add(' M CMakeLists.txt')
$expectedList.Add(' M native/gl_context.cpp')
$expectedList.Add(' M native/gl_context.h')

Get-ChildItem -Recurse -File $flatRoot | ForEach-Object {
    $rel = $_.FullName.Substring($flatRoot.Length + 1).Replace('\', '/')
    if ($rel -eq 'CMakeLists.txt') { return }
    $trackedFiles = & git -C $Src ls-files -- $rel
    if ($trackedFiles) {
        $expectedList.Add(" M $rel")
    } else {
        $expectedList.Add("?? $rel")
    }
}
$expected = $expectedList | Sort-Object -Unique
$actual = @(& git -C $Src status --porcelain -uall | Sort-Object)
$diff = Compare-Object $actual $expected
if ($diff) {
    Write-Host 'fetch-upstream: ERROR: unexpected tree state after patch+overlay:'
    $actual | ForEach-Object { Write-Host "  $_" }
    Write-Host 'expected exactly:'
    $expected | ForEach-Object { Write-Host "  $_" }
    throw 'Tree state mismatch after patch+overlay'
}

Write-Host 'fetch-upstream: OK'
Write-Host "  upstream checkout : $Src"
Write-Host "  HEAD              : $head (v0.2.15)"
Write-Host '  patches applied   : in patches/series order'
Write-Host '  overlaid files    : CMakeLists.txt, native/flat_host.cpp'
Write-Host ''
Write-Host 'NOTE: the Android app layer (activity, manifest, Gradle, JNI glue) is'
Write-Host 'NOT part of this overlay. See docs/ANDROID-GAP.md before building.'
Write-Host 'Next: docs/BUILD.md'
