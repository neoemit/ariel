# What the iOS scripts share: shell options, the repo root and how checks report.

setopt err_exit no_unset pipe_fail

ROOT=${${(%):-%x}:A:h:h}

if [[ -t 1 ]]; then
	_green=$'\e[32m' _yellow=$'\e[33m' _red=$'\e[31m' _dim=$'\e[2m' _reset=$'\e[0m'
else
	_green= _yellow= _red= _dim= _reset=
fi

env_ok() { print -r -- "  ${_green}✓${_reset} $*" }
env_warn() { print -r -- "  ${_yellow}!${_reset} $*"; (( env_warnings += 1 )) }
env_bad() { print -r -- "  ${_red}✗${_reset} $*"; (( env_errors += 1 )) }
env_note() { print -r -- "    ${_dim}$*${_reset}" }

# The app version from the Android build, which iOS shares: "1.<minor> <minor>" for a
# build.gradle.kts given as text (ios/Config/stamp-version.sh stamps the same into the app).
ariel_version() {
	local minor
	minor=$(sed -nE 's/^[[:space:]]*val[[:space:]]+appVersionMinor[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' <<<$1 | head -n 1)
	[[ -n $minor ]] || return 1
	print -r -- "1.$minor $minor"
}
