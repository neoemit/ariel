#!/bin/zsh
# Checks that this Mac is ready to build the Ariel iOS app and sideload it onto iPhones and iPads with
# scripts/ios-sideload.sh, and shows what that script will sign with (docs/ios.md › Sideloading).
#
#   scripts/ios-check-env.sh
#
# Settings come from the environment first, then ios/Config/Local.xcconfig, then the defaults:
#
#   APPLE_DEVELOPMENT_TEAM  Team ID to sign with. Optional when Xcode is signed in to a single team
#                           (Xcode › Settings › Accounts). Local.xcconfig: DEVELOPMENT_TEAM.
#   ARIEL_IOS_BUNDLE_ID     Bundle ID; the widget's ID derives from it.
#                           Local.xcconfig / Base.xcconfig: APP_BUNDLE_ID.
#   ARIEL_RELAY_URL         Optional: your relay backend, to check that it answers.
#
# A free Personal Team cannot sign Push Notifications, so its builds have in-app alerts: a buddy's
# panic reaches the iPhone while Ariel is open (docs/ios.md › Push or in-app alerts).
#
# Exits 1 when something has to be fixed first. ios-sideload.sh runs the same checks before building.

source ${${(%):-%x}:A:h}/env-common.sh

# A setting from Local.xcconfig, else Base.xcconfig, the way Xcode resolves them; empty when unset.
xcconfig_value() {
	local file value
	for file in $ROOT/ios/Config/Local.xcconfig $ROOT/ios/Config/Base.xcconfig; do
		[[ -f $file ]] || continue
		value=$(awk -F= -v key=$1 '{ sub(/\/\/.*/, "") }
			$1 ~ "^[ \t]*" key "[ \t]*$" { value = $2; gsub(/^[ \t]+|[ \t]+$/, "", value) }
			END { print value }' $file)
		[[ -z $value ]] || { print -r -- $value; return 0 }
	done
	return 0
}

# The teams Xcode is signed in to, as JSON: [{id, name, free}].
xcode_teams() {
	defaults export com.apple.dt.Xcode - 2>/dev/null |
		plutil -extract IDEProvisioningTeamByIdentifier json -o - - 2>/dev/null |
		jq -c '[.[][] | {id: .teamID, name: .teamName, free: (.isFreeProvisioningTeam == true)}]
			| unique_by(.id)' 2>/dev/null || print '[]'
}

# Runs every check and sets team, team_name, team_free and bundle_id for ios-sideload.sh.
# Returns 1 when something has to be fixed first.
check_env() {
	env_errors=0 env_warnings=0
	team= team_name= team_free=false bundle_id=
	local value teams count source developer_dir

	print "Xcode and tools"
	developer_dir=$(xcode-select -p 2>/dev/null || true)
	if [[ $developer_dir != *.app/Contents/Developer ]]; then
		env_bad "xcode-select points to ${developer_dir:-nothing}, not Xcode: sudo xcode-select -s /Applications/Xcode.app"
	elif ! value=$(xcodebuild -version 2>/dev/null); then
		env_bad "xcodebuild does not run: open Xcode once to finish installing it"
	else
		env_ok "${value%%$'\n'*} (${developer_dir%/Contents/Developer})"
		xcodebuild -checkFirstLaunchStatus >/dev/null 2>&1 ||
			env_warn "Xcode has first-launch setup left to do: xcodebuild -runFirstLaunch"
	fi
	if xcrun --find devicectl >/dev/null 2>&1; then
		env_ok "devicectl (installs on devices)"
	else
		env_bad "devicectl is missing: Xcode 15 or newer is needed"
	fi
	if command -v jq >/dev/null; then
		env_ok "jq"
	else
		env_bad "jq is missing: brew install jq"
	fi
	if command -v xcodegen >/dev/null; then
		env_ok "XcodeGen"
	else
		env_warn "XcodeGen is missing; only needed after editing ios/project.yml or adding files (brew install xcodegen)"
	fi
	[[ -d $ROOT/ios/Ariel.xcodeproj ]] || env_bad "ios/Ariel.xcodeproj is missing: cd ios && xcodegen generate"
	if value=$(ariel_version "$(cat $ROOT/app/build.gradle.kts 2>/dev/null)"); then
		env_ok "version ${value% *} (build ${value#* }, from app/build.gradle.kts)"
	else
		env_bad "no appVersionMinor in app/build.gradle.kts: the iOS build takes its version from there"
	fi

	print "\nSigning"
	teams=$(xcode_teams)
	count=$(jq length <<<$teams)
	if [[ -n ${APPLE_DEVELOPMENT_TEAM:-} ]]; then
		team=$APPLE_DEVELOPMENT_TEAM source=APPLE_DEVELOPMENT_TEAM
	elif value=$(xcconfig_value DEVELOPMENT_TEAM) && [[ -n $value ]]; then
		team=$value source="DEVELOPMENT_TEAM in ios/Config"
	elif (( count == 1 )); then
		team=$(jq -r '.[0].id' <<<$teams) source="the only team Xcode is signed in to"
	fi
	if (( count == 0 )); then
		env_bad "Xcode is not signed in to an Apple ID: Xcode › Settings › Accounts › +"
	elif [[ -z $team ]]; then
		env_bad "Xcode is signed in to $count teams; set APPLE_DEVELOPMENT_TEAM to one of: $(jq -r 'map("\(.id) (\(.name))") | join(", ")' <<<$teams)"
	elif [[ ! $team =~ '^[A-Z0-9]{10}$' ]]; then
		env_bad "team '$team' (from $source) is not a 10-character Team ID"
	elif ! jq -e --arg t $team 'any(.[]; .id == $t)' <<<$teams >/dev/null; then
		env_bad "Xcode is not signed in to team $team (from $source): Xcode › Settings › Accounts"
	else
		team_name=$(jq -r --arg t $team '.[] | select(.id == $t) | .name' <<<$teams)
		team_free=$(jq -r --arg t $team '.[] | select(.id == $t) | .free' <<<$teams)
		env_ok "team $team, $team_name (from $source)"
		if $team_free; then
			env_note "Free Personal Team: the app stays signed for 7 days (run ios-sideload.sh again before"
			env_note "then, or schedule scripts/ios-renew.sh), and a device takes at most 3 apps from free teams."
		fi
	fi
	value=$(xcconfig_value DEVELOPMENT_TEAM)
	if [[ -n ${APPLE_DEVELOPMENT_TEAM:-} && -n $value && $value != $APPLE_DEVELOPMENT_TEAM ]]; then
		env_warn "APPLE_DEVELOPMENT_TEAM ($APPLE_DEVELOPMENT_TEAM) differs from DEVELOPMENT_TEAM in Local.xcconfig ($value), which Xcode uses"
	fi
	bundle_id=${ARIEL_IOS_BUNDLE_ID:-$(xcconfig_value APP_BUNDLE_ID)}
	if [[ $bundle_id =~ '^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$' ]]; then
		env_ok "bundle ID $bundle_id (widget $bundle_id.widget)"
	else
		env_bad "bundle ID '$bundle_id' is not a reverse-DNS ID such as com.yourname.ariel (ARIEL_IOS_BUNDLE_ID or APP_BUNDLE_ID)"
	fi

	print "\nAlerts"
	local plist=$ROOT/ios/Ariel/Resources/GoogleService-Info.plist
	if $team_free; then
		env_ok "Personal Team build: in-app alerts, without push. A buddy's panic reaches the iPhone"
		env_note "while Ariel is open, and now and then through background refresh (docs/ios.md)."
	elif [[ -f $plist ]]; then
		env_ok "push notifications through Firebase (GoogleService-Info.plist found)"
	else
		env_warn "no ios/Ariel/Resources/GoogleService-Info.plist, so no push: in-app alerts only (docs/ios.md › Push)"
	fi

	print "\nRelay"
	if [[ -z ${ARIEL_RELAY_URL:-} ]]; then
		env_ok "not checked: ARIEL_RELAY_URL is not set (optional)"
	else
		local url=${ARIEL_RELAY_URL%/} health
		[[ $url == *://* ]] || url="http://$url"
		if health=$(curl -fsS --max-time 10 "$url/health" 2>&1); then
			if [[ $(jq -r '.firebaseReady // false' <<<$health 2>/dev/null) == true ]]; then
				env_ok "$url answers and can send pushes"
			else
				env_warn "$url answers, but has no Firebase credentials: pushes are off, in-app alerts still work"
			fi
			if ! curl -fsS --max-time 10 -H 'Content-Type: application/json' \
				-d '{"buddyId":"ios-check-env","afterId":0}' "$url/v1/events" >/dev/null 2>&1; then
				env_warn "$url has no /v1/events: redeploy the relay from this repository for in-app alerts"
			fi
		else
			env_warn "$url/health does not answer: ${health##*curl: }"
		fi
	fi

	print
	if (( env_errors == 1 )); then
		print -r -- "${_red}1 problem to fix before sideloading.${_reset}"
		return 1
	elif (( env_errors )); then
		print -r -- "${_red}$env_errors problems to fix before sideloading.${_reset}"
		return 1
	fi
	print -r -- "${_green}Ready to sideload.${_reset}"
}

# Run directly rather than sourced by another script: check and exit with the result.
if [[ $zsh_eval_context == toplevel ]]; then
	check_env
fi
