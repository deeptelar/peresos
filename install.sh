#!/bin/sh
# Installs Peresós on Linux or macOS, with its own Java, and puts the peresos command on your PATH:
#
#   curl -fsSL https://raw.githubusercontent.com/deeptelar/peresos/main/install.sh | sh
#
# On Debian and Ubuntu it installs the .deb with apt (it asks for sudo): /opt/peresos, the peresos
# command in /usr/bin, and Peresós in the menu of apps. Elsewhere, or with PERESOS_LOCAL=1, it
# installs in your home without sudo: ~/.local/share/peresos (Linux) or ~/Applications (macOS),
# with the command in ~/.local/bin. Then it runs peresos doctor, which checks Ollama and Gemma.
#
# PERESOS_FROM takes the files from another address or a folder, to try a build before a release.
# peresos update runs this script again; from its window it sets PERESOS_SUDO=pkexec, which asks for
# the password in a dialog, and PERESOS_NO_DOCTOR=1.
set -eu

from="${PERESOS_FROM:-https://github.com/deeptelar/peresos/releases/latest/download}"
bin="$HOME/.local/bin"

say() { printf '  %s\n' "$*"; }
fail() {
	say "$*"
	say "If this looks like a bug in Peresós, tell us: https://github.com/deeptelar/peresos/issues/new"
	exit 1
}

# Copies the file $1 of the release to $2.
fetch() {
	if [ -d "$from" ]; then
		cp "$from/$1" "$2"
	else
		curl -fSL --progress-bar "$from/$1" -o "$2" || fail "Could not download $from/$1."
	fi
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# apt reads the .deb as its own user, which must be able to open the folder.
chmod 755 "$tmp"

# Puts $bin on the PATH of new terminals, in the file the shell reads when it starts.
on_path() {
	case ":$PATH:" in *":$bin:"*) return ;; esac
	case "${SHELL:-}" in
	*/zsh) rc="$HOME/.zshrc" ;;
	*/bash) rc="$HOME/.bashrc" ;;
	*) rc="$HOME/.profile" ;;
	esac
	if ! grep -qs '.local/bin' "$rc"; then
		printf '\n# The peresos command, added by its installer\nexport PATH="$HOME/.local/bin:$PATH"\n' >>"$rc"
		say "Added ~/.local/bin to your PATH in $rc."
	fi
	say "Open a new terminal to use peresos, or run: export PATH=\"\$HOME/.local/bin:\$PATH\""
}

install_deb() {
	say "Downloading Peresós for Debian and Ubuntu..."
	fetch peresos-linux-amd64.deb "$tmp/peresos.deb"
	chmod 644 "$tmp/peresos.deb"
	say "Installing it with apt, which asks for your password:"
	if [ "$(id -u)" = 0 ]; then
		apt-get install -y "$tmp/peresos.deb"
	else
		"${PERESOS_SUDO:-sudo}" apt-get install -y "$tmp/peresos.deb"
	fi
	peresos=/usr/bin/peresos
	say "Peresós is installed. Remove it with: peresos uninstall (or sudo apt remove peresos)"
}

install_linux_home() {
	say "Downloading Peresós for Linux..."
	fetch peresos-linux-x64.tar.gz "$tmp/peresos.tar.gz"
	app="$HOME/.local/share/peresos"
	rm -rf "$app"
	mkdir -p "$app" "$bin" "$HOME/.local/share/applications"
	tar -xzf "$tmp/peresos.tar.gz" -C "$app" --strip-components=1
	ln -sf "$app/bin/peresos" "$bin/peresos"
	cat >"$HOME/.local/share/applications/peresos.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Peresós
GenericName=Backlog sloth
Comment=A sloth that eats one item of your backlog a day
Exec=$app/bin/peresos window
Icon=$app/lib/peresos.png
Terminal=false
Categories=Utility;
StartupWMClass=dev-deeptelar-peresos-MainKt
DESKTOP
	peresos="$bin/peresos"
	say "Peresós is installed in $app, and in the menu of apps."
	say "Remove it with: peresos uninstall"
	on_path
}

install_mac() {
	say "Downloading Peresós for macOS..."
	fetch "peresos-macos-arm64.tar.gz" "$tmp/peresos.tar.gz"
	mkdir -p "$HOME/Applications" "$bin"
	tar -xzf "$tmp/peresos.tar.gz" -C "$tmp"
	rm -rf "$HOME/Applications/Peresos.app"
	mv "$tmp/Peresos.app" "$HOME/Applications/Peresos.app"
	ln -sf "$HOME/Applications/Peresos.app/Contents/MacOS/Peresos" "$bin/peresos"
	peresos="$bin/peresos"
	say "Peresós is installed in ~/Applications: open it there for its window, or type peresos in a terminal."
	say "Remove it with: peresos uninstall"
	on_path
}

case "$(uname -s) $(uname -m)" in
"Linux x86_64")
	if [ -z "${PERESOS_LOCAL:-}" ] && command -v apt-get >/dev/null 2>&1 && { [ "$(id -u)" = 0 ] || command -v sudo >/dev/null 2>&1; }; then
		install_deb
	else
		install_linux_home
	fi
	;;
"Darwin arm64") install_mac ;;
*) fail "There is no package of Peresós for $(uname -s) on $(uname -m) yet. Build it from its source: https://github.com/deeptelar/peresos#build-it-yourself" ;;
esac

[ -z "${PERESOS_NO_DOCTOR:-}" ] || exit 0
echo
say "Now Peresós checks what it needs:"
echo
# With the PATH of the new terminals, where the command now is.
PATH="$bin:$PATH" "$peresos" doctor </dev/null || true
