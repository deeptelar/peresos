# Installs Peresós on Windows, with its own Java, and puts the peresos command on your PATH:
#
#   irm https://raw.githubusercontent.com/deeptelar/peresos/main/install.ps1 | iex
#
# It goes in %LOCALAPPDATA%\Programs\Peresos, needs no administrator, and adds Peresós to the Start
# menu, where it opens its window. Then it runs peresos doctor, which checks Ollama and Gemma.
#
# PERESOS_FROM takes the files from another address or a folder, to try a build before a release.
# Update in the window of Peresós runs this script again, with PERESOS_NO_DOCTOR=1.
#
# On a PC with a policy that controls apps, as a PC of work or Smart App Control, PowerShell runs in
# Constrained Language Mode: cmdlets work, but not .NET or COM. So this script uses cmdlets, and
# says what it cannot do there.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue' # the progress bar makes Invoke-WebRequest many times slower

$from = if ($env:PERESOS_FROM) { $env:PERESOS_FROM } else { 'https://github.com/deeptelar/peresos/releases/latest/download' }
$dir = Join-Path $env:LOCALAPPDATA 'Programs\Peresos'
$name = "Peres$([char]0xF3)s" # Peresós, written so that any PowerShell reads this file the same way

function Say($text) { Write-Host "  $text" }

$full = $ExecutionContext.SessionState.LanguageMode -eq 'FullLanguage'

if (Get-Process -Name 'peresos', 'peresos-window' -ErrorAction SilentlyContinue) {
    Say "$name is running. Quit it from its icon in the tray, then run the installer again."
    return
}

$tmp = Join-Path $env:TEMP "peresos-$(New-Guid)"
New-Item -ItemType Directory $tmp | Out-Null
try {
    Say "Downloading $name for Windows..."
    $zip = Join-Path $tmp 'peresos.zip'
    if (Test-Path $from -PathType Container) {
        Copy-Item (Join-Path $from 'peresos-windows-x64.zip') $zip
    } else {
        Invoke-WebRequest "$from/peresos-windows-x64.zip" -OutFile $zip -UseBasicParsing
    }
    Expand-Archive $zip $tmp
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
    New-Item -ItemType Directory (Split-Path $dir) -Force | Out-Null
    Move-Item (Join-Path $tmp 'peresos') $dir
} catch {
    Say "The installation failed: $($_.Exception.Message)"
    Say "If this looks like a bug in $name, tell us: https://github.com/deeptelar/peresos/issues/new"
    return
} finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

# The command, in the PATH of new terminals and of this one. .NET tells the open apps at once; the
# registry alone, which is all Constrained Language Mode allows, reaches them at the next sign-in.
$path = (Get-ItemProperty -Path 'HKCU:\Environment' -Name 'Path' -ErrorAction SilentlyContinue).Path
if (-not (($path -split ';') -contains $dir)) {
    $path = if ($path) { "$path;$dir" } else { $dir }
    if ($full) {
        [Environment]::SetEnvironmentVariable('Path', $path, 'User')
    } else {
        New-ItemProperty -Path 'HKCU:\Environment' -Name 'Path' -Value $path -PropertyType ExpandString -Force | Out-Null
        $signIn = $true
    }
}
if (-not (($env:Path -split ';') -contains $dir)) { $env:Path = "$env:Path;$dir" }

# The window, in the Start menu. A shortcut takes COM, which Constrained Language Mode does not allow.
$window = Join-Path $dir 'peresos-window.exe'
if ($full) {
    $shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path ([Environment]::GetFolderPath('Programs')) "$name.lnk"))
    $shortcut.TargetPath = $window
    $shortcut.Description = 'A sloth that eats one item of your backlog a day'
    $shortcut.Save()
}

# Feed to Peresós in the menu of photos, PDFs and text files in the Explorer (under Show more options
# on Windows 11). The window's launcher runs it, so no console opens. peresos uninstall removes it.
foreach ($type in 'image', '.pdf', 'text') {
    $key = "HKCU:\Software\Classes\SystemFileAssociations\$type\shell\Peresos"
    New-Item -Path $key -Value "Feed to $name" -Force | Out-Null
    New-ItemProperty -Path $key -Name 'Icon' -Value $window -Force | Out-Null
    New-Item -Path "$key\command" -Value "`"$window`" feed --notify `"%1`"" -Force | Out-Null
}

if ($full) {
    Say "$name is installed in $dir, in the Start menu, and in the menu of files: Feed to $name."
} else {
    Say "$name is installed in $dir, and in the menu of files: Feed to $name."
    Say "A policy of this PC keeps PowerShell from making its shortcut in the Start menu: open its window with peresos window."
}
Say "Type peresos in any new terminal. To remove it: peresos uninstall"
if ($signIn) { Say "Terminals find peresos after you sign out and in again; this one already does." }
if ($env:PERESOS_NO_DOCTOR) { return }
Write-Host ''
Say "Now $name checks what it needs:"
Write-Host ''
try {
    & (Join-Path $dir 'peresos.exe') doctor
} catch {
    # Smart App Control, or the policy of a PC of work, can keep an app that is not signed from running.
    Say "Windows did not let $name run: $($_.Exception.Message)"
    Say "A policy of this PC decides which apps may run, and $name is not signed yet. Whoever looks after this PC can allow it."
}
