# What scripts/ios-sideload.sh and scripts/ios-renew.sh share: reading devices and provisioning
# profiles, and remembering what this Mac installed where. A device cannot say when its copy's
# signature runs out, so that is noted at install time, and ios-renew.sh knows when a device is due.
# These need work (a temporary folder) and bundle_id (from check_env).

IOS_DATA="$HOME/Library/Application Support/ariel-ios"
IOS_STATE=$IOS_DATA/state.json

to_epoch() { date -j -u -f '%Y-%m-%dT%H:%M:%SZ' $1 +%s }
local_date() { date -j -f %s $(to_epoch $1) '+%a %e %b %H:%M' }

# One value of a provisioning profile (a key path such as ExpirationDate), as text or JSON.
profile_value() { security cms -D -i $1 2>/dev/null | plutil -extract $2 ${3:-raw} -o - - 2>/dev/null }

# Writes devices.json. A device paired by cable and not connected to since does not say whether it
# is physical, so those are asked first.
list_ios_devices() {
	local d unknown
	xcrun devicectl list devices --json-output $work/devices.json >/dev/null 2>&1 || true
	unknown=($(jq -r '.result.devices[] | select(.hardwareProperties.platform == "iOS"
		and .connectionProperties.pairingState == "paired" and .hardwareProperties.reality == null)
		| .hardwareProperties.udid' $work/devices.json 2>/dev/null || true))
	(( $#unknown )) || return 0
	for d in $unknown; do
		xcrun devicectl --timeout 30 device info details --device $d >/dev/null 2>&1 || true
	done
	xcrun devicectl list devices --json-output $work/devices.json >/dev/null 2>&1 || true
}

read_state() { [[ -f $IOS_STATE ]] && jq -r "$@" $IOS_STATE || true }

write_state() {
	mkdir -p $IOS_DATA
	{ [[ -f $IOS_STATE ]] && cat $IOS_STATE || print '{}' } | jq "$@" > $IOS_STATE.tmp
	mv $IOS_STATE.tmp $IOS_STATE
}

# The installed build number, version and bundle URL (a new URL after every install), nothing if the
# app is not installed; fails when the device cannot be reached. A locked device's Wi-Fi naps, and the
# first attempt often fails while it wakes up, so it tries three times.
installed_app() {
	local attempt
	for attempt in 1 2 3; do
		if xcrun devicectl --timeout 60 device info apps --device $1 --bundle-id $bundle_id \
			--json-output $work/apps.json >/dev/null 2>&1; then
			jq -r '.result.apps[0] // empty | "\(.bundleVersion) \(.version) \(.url)"' $work/apps.json
			return
		fi
		(( attempt == 3 )) || sleep ${INSTALLED_RETRY_SECONDS:-20}
	done
	return 1
}

# Notes an install on a device: its signature's end, and which copy that is about.
note_install() {
	local app_info
	app_info=($(installed_app $1)) && (( $#app_info )) || return 1
	write_state --arg d $1 --arg b $app_info[1] --arg v $app_info[2] --arg u $app_info[3] --arg e $2 \
		'.devices[$d] = { build: $b, version: $v, url: $u, expires: $e }'
}

# Sets reply to the xcodebuild settings that sign for this team. A free Personal Team can sign
# neither Push Notifications nor Time Sensitive notifications, so its builds have in-app alerts
# instead (the app polls the relay while it is open) and ntfy (README), as do builds with --in-app.
signing_settings() {
	reply=(DEVELOPMENT_TEAM=$team APP_BUNDLE_ID=$bundle_id CODE_SIGN_STYLE=Automatic)
	if $team_free || ${in_app:-false}; then
		reply+=(ARIEL_PUSH_ENABLED=NO ARIEL_APP_ENTITLEMENTS=Config/PersonalTeam.entitlements)
	fi
}
