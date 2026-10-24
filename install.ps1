# Installs Peresós on Windows, with its own Java, and puts the peresos command on your PATH:
#
#   irm https://raw.githubusercontent.com/deeptelar/peresos/main/install.ps1 | iex
#
# It goes in %LOCALAPPDATA%\Programs\Peresos, needs no administrator, and adds Peresós to the Start
# menu, where it opens its window. Then it runs peresos doctor, which checks Ollama and Gemma.
#
# PERESOS_FROM takes the files from another address or a folder, to try a build before a release.
# Update in the window of Peresós runs this script again, with PERESOS_NO_DOCTOR=1.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue' # the progress bar makes Invoke-WebRequest many times slower

$from = if ($env:PERESOS_FROM) { $env:PERESOS_FROM } else { 'https://github.com/deeptelar/peresos/releases/latest/download' }
$dir = Join-Path $env:LOCALAPPDATA 'Programs\Peresos'
$name = "Peres$([char]0xF3)s" # Peresós, written so that any PowerShell reads this file the same way

function Say($text) { Write-Host "  $text" }

if (Get-Process -Name 'peresos', 'peresos-window' -ErrorAction SilentlyContinue) {
    Say "$name is running. Quit it from its icon in the tray, then run the installer again."
    return
}

$tmp = Join-Path ([IO.Path]::GetTempPath()) "peresos-$([Guid]::NewGuid())"
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

# The command, in the PATH of new terminals and of this one.
$path = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not (($path -split ';') -contains $dir)) {
    [Environment]::SetEnvironmentVariable('Path', $(if ($path) { "$path;$dir" } else { $dir }), 'User')
}
if (-not (($env:Path -split ';') -contains $dir)) { $env:Path = "$env:Path;$dir" }

# The window, in the Start menu.
$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path ([Environment]::GetFolderPath('Programs')) "$name.lnk"))
$shortcut.TargetPath = Join-Path $dir 'peresos-window.exe'
$shortcut.Description = 'A sloth that eats one item of your backlog a day'
$shortcut.Save()

# Feed to Peresós in the menu of photos, PDFs and text files in the Explorer (under Show more options
# on Windows 11). The window's launcher runs it, so no console opens. peresos uninstall removes it.
$window = Join-Path $dir 'peresos-window.exe'
foreach ($type in 'image', '.pdf', 'text') {
    $key = "HKCU:\Software\Classes\SystemFileAssociations\$type\shell\Peresos"
    New-Item -Path $key -Value "Feed to $name" -Force | Out-Null
    New-ItemProperty -Path $key -Name 'Icon' -Value $window -Force | Out-Null
    New-Item -Path "$key\command" -Value "`"$window`" feed --notify `"%1`"" -Force | Out-Null
}

Say "$name is installed in $dir, in the Start menu, and in the menu of files: Feed to $name."
Say "Type peresos in any new terminal. To remove it: peresos uninstall"
if ($env:PERESOS_NO_DOCTOR) { return }
Write-Host ''
Say "Now $name checks what it needs:"
Write-Host ''
& (Join-Path $dir 'peresos.exe') doctor
