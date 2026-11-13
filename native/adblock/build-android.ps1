<#
.SYNOPSIS  Builds libmovy_adblock.so for Android ABIs with cargo-ndk.
.PARAMETER OutDir  jniLibs output directory (default: native/adblock/target/jniLibs).
.PARAMETER Platform  Android min SDK / API level (default 24).
#>
param(
  [string]$OutDir = (Join-Path $PSScriptRoot 'target\jniLibs'),
  [int]$Platform = 24,
  [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64')
)
$ErrorActionPreference = 'Stop'
$env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $env:ANDROID_NDK_HOME) {
  $ndkRoot = Join-Path $env:ANDROID_HOME 'ndk'
  $latest = Get-ChildItem $ndkRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
  if (-not $latest) { throw "No Android NDK found under $ndkRoot; set ANDROID_NDK_HOME." }
  $env:ANDROID_NDK_HOME = $latest.FullName
}
$OutDir = [IO.Path]::GetFullPath($OutDir)
New-Item -ItemType Directory -Force $OutDir | Out-Null
Push-Location $PSScriptRoot
try {
  $targetArgs = $Abis | ForEach-Object { '-t', $_ }
  cargo ndk @targetArgs --platform $Platform -o $OutDir build --release --lib --features jni
  if ($LASTEXITCODE -ne 0) { throw "cargo ndk failed ($LASTEXITCODE)" }
} finally { Pop-Location }
foreach ($abi in $Abis) {
  $so = Join-Path $OutDir "$abi\libmovy_adblock.so"
  if (-not (Test-Path $so)) { throw "Missing output: $so" }
  '{0}  {1:N0} bytes' -f $so, (Get-Item $so).Length
}
