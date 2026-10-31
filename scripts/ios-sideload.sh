#!/bin/zsh
# Builds Ariel from this checkout and installs it on the iPhones and iPads you pick among those paired
# with this Mac and reachable now (by cable, or over the same Wi-Fi). Simulators are never offered
# (docs/ios.md › Sideloading).
#
#   scripts/ios-sideload.sh [--all | --device UDID...] [--debug] [--in-app] [--build-only]
#
# Without --all or --device it lists the reachable devices and asks for one, several ("1 3") or all of
# them. --debug skips the Release optimisations (quicker to build); --in-app builds without push even
# for a paid team; --build-only builds and signs for the chosen devices without installing anything.
#
# It runs scripts/ios-check-env.sh first and uses its settings.
#
# A device needs Developer Mode on (Settings › Privacy & Security › Developer Mode) and has to be
# unlocked while the app installs. With a free Personal Team the app is built without push (in-app
# alerts: a buddy's panic reaches the iPhone while Ariel is open) and stops opening after 7 days: run
# this again before then, or let scripts/ios-renew.sh do it. The app's data is kept.

SCRIPT=${0:A}
source ${SCRIPT:h}/ios-check-env.sh
source ${SCRIPT:h}/ios-common.sh

DERIVED=$ROOT/ios/build/sideload

usage() {
	print -u2 "usage: ${SCRIPT:t} [--all | --device UDID...] [--debug] [--in-app] [--build-only]"
	exit 2
}

# Fills ids, names, details and modes with the paired iPhones and iPads that can be reached now, and
# away with the names of those that cannot.
reachable_devices() {
	local line fields os
	ids=() names=() details=() modes=() away=()
	list_ios_devices
	for line in ${(f)"$(jq -r '.result.devices[]
		| select(.hardwareProperties.reality == "physical" and .hardwareProperties.platform == "iOS"
			and .connectionProperties.pairingState == "paired")
		| [.hardwareProperties.udid, .deviceProperties.name,
			(.hardwareProperties.marketingName // .hardwareProperties.productType // "?"),
			(.deviceProperties.osVersionNumber // "?"),
			(if .connectionProperties.tunnelState == "unavailable" then "" else .connectionProperties.transportType // "" end),
			(.deviceProperties.developerModeStatus // "unknown")]
		| @tsv' $work/devices.json 2>/dev/null || true)"}; do
		fields=("${(@ps:\t:)line}")
		if [[ -z $fields[5] ]]; then
			away+=($fields[2])
			continue
		fi
		os=iOS
		[[ $fields[3] != iPad* ]] || os=iPadOS
		ids+=($fields[1])
		names+=($fields[2])
		details+=("$fields[3], $os $fields[4], ${${fields[5]/localNetwork/Wi-Fi}/wired/cable}")
		modes+=($fields[6])
	done
}

# Asks which of the reachable devices get the app; sets selected. Each line says what this Mac last
# installed there.
choose_devices() {
	local i reply number note expires
	print "\nReachable iPhones and iPads:"
	for (( i = 1; i <= $#ids; i++ )); do
		note=
		expires=$(read_state --arg d $ids[i] '.devices[$d].expires // empty')
		if [[ -n $expires ]]; then
			if (( $(to_epoch $expires) < $(date +%s) )); then
				note=" — stopped opening $(local_date $expires)"
			else
				note=" — signed until $(local_date $expires)"
			fi
		fi
		[[ $modes[i] != disabled ]] || note=" — Developer Mode off"
		print -r -- "  $i) $names[i] — $details[i]$note"
	done
	print "  a) All of them"
	print "  q) Quit"
	(( ! $#away )) || print -r -- "Paired but out of reach: ${(j:, :)away} (unlock it on this Mac's Wi-Fi, or connect it by cable)"
	while true; do
		read -r "reply?Install on (1-$#ids, several like \"1 3\", a or q): " || exit 1
		case ${reply:l} in
			a | all) selected=($ids); return ;;
			q | quit) exit 0 ;;
		esac
		selected=()
		for number in ${=reply//,/ }; do
			if [[ $number != <1-> ]] || (( number > $#ids )); then
				selected=()
				break
			fi
			selected+=($ids[number])
		done
		if (( $#selected )); then
			selected=(${(u)selected})
			return
		fi
		print -u2 "Type numbers from 1 to $#ids, a for all, or q."
	done
}

# Builds and signs the app for one device. Building for a device the team does not have yet
# registers it, and a free team's profile may list only the device it was made for, so every device
# gets its own build; after the first, that only re-signs.
build_for() {
	local udid=$1 bundle reply
	signing_settings
	local arguments=(-project $ROOT/ios/Ariel.xcodeproj -scheme Ariel -configuration $configuration
		-destination id=$udid -derivedDataPath $DERIVED
		-allowProvisioningUpdates -allowProvisioningDeviceRegistration $reply)
	mkdir -p $DERIVED
	if ! xcodebuild build $arguments > $DERIVED/last-build.log 2>&1; then
		grep -E 'error:' $DERIVED/last-build.log | sort -u | head -8 | sed 's/^/    /'
		print -r -- "    full log: ${DERIVED#$ROOT/}/last-build.log"
		return 1
	fi
	for bundle in $APP $APP/PlugIns/*.appex(N); do
		if ! profile_value $bundle/embedded.mobileprovision ProvisionedDevices json |
			jq -e --arg d $udid 'index($d) != null' >/dev/null; then
			print -r -- "    the profile Xcode made for ${bundle:t} does not include this device"
			return 1
		fi
	done
}

# When the built app stops opening: the first of its profiles (app and widget) to run out.
signed_until() {
	local bundle
	for bundle in $APP $APP/PlugIns/*.appex(N); do
		print -r -- "$(profile_value $bundle/embedded.mobileprovision ExpirationDate)"
	done | sort | sed -n '/./{p;q;}'
}

# A locked device's Wi-Fi naps, and the first attempt often fails while it wakes up.
install_on() {
	local udid=$1 attempt
	for attempt in 1 2 3; do
		xcrun devicectl --timeout 600 device install app --device $udid $APP > $work/install.log 2>&1 && return 0
		(( attempt == 3 )) || { print "    no answer; trying again in 15 seconds (keep it unlocked)"; sleep 15 }
	done
	grep -iE 'error|fail' $work/install.log | head -3 | sed 's/^/    /'
	return 1
}

sideload() {
	local udid=$1 i=$2 version expires alerts=push
	if [[ $modes[i] == disabled ]]; then
		print "    Developer Mode is off: Settings › Privacy & Security › Developer Mode, then restart and confirm"
		failed_names+=($names[i])
		return
	fi
	if $team_free || $in_app || [[ ! -f $ROOT/ios/Ariel/Resources/GoogleService-Info.plist ]]; then
		alerts=in-app
	fi
	if [[ ! -d $DERIVED/SourcePackages ]]; then
		print "    building $configuration with $alerts alerts, team $team (the first build downloads the Firebase SDK: a few minutes)"
	else
		print "    building $configuration with $alerts alerts, team $team"
	fi
	if ! build_for $udid; then
		failed_names+=($names[i])
		return
	fi
	version="$(plutil -extract CFBundleShortVersionString raw $APP/Info.plist) ($(plutil -extract CFBundleVersion raw $APP/Info.plist))"
	expires=$(signed_until)
	if $build_only; then
		print -r -- "    built Ariel $version for it, signed until $(local_date $expires): ${APP#$ROOT/}"
		done_names+=($names[i])
		return
	fi
	print "    installing Ariel $version"
	if install_on $udid; then
		print -r -- "    installed, signed until $(local_date $expires)"
		done_names+=($names[i])
		note_install $udid $expires || true
	else
		print "    installing failed: unlock it and run this again"
		failed_names+=($names[i])
	fi
}

configuration=Release
build_only=false
all=false
in_app=false
requested=()
while (( $# )); do
	case $1 in
		--all) all=true; shift ;;
		--device) (( $# > 1 )) || usage; requested+=($2); shift 2 ;;
		--debug) configuration=Debug; shift ;;
		--in-app) in_app=true; shift ;;
		--build-only) build_only=true; shift ;;
		*) usage ;;
	esac
done
! $all || (( ! $#requested )) || usage

print "Checking this Mac (scripts/ios-check-env.sh)\n"
check_env || exit 1

APP=$DERIVED/Build/Products/$configuration-iphoneos/Ariel.app
done_names=() failed_names=()

work=$(mktemp -d -t ariel-sideload)
trap 'rm -rf $work' EXIT

reachable_devices
if $all; then
	selected=($ids)
elif (( $#requested )); then
	selected=()
	for id in $requested; do
		(( $ids[(Ie)$id] )) || { print -u2 "$id is not a reachable iPhone or iPad paired with this Mac (simulators never get the app)"; exit 1 }
		selected+=($id)
	done
	selected=(${(u)selected})
elif (( ! $#ids )); then
	selected=()
elif [[ -t 0 ]]; then
	choose_devices
else
	print -u2 "Not interactive: pass --all or --device UDID."
	exit 1
fi
if (( ! $#selected )); then
	print -u2 "\nNo iPhone or iPad paired with this Mac can be reached${away:+ (out of reach: ${(j:, :)away})}."
	print -u2 "Connect it by cable once and trust this Mac, then keep it unlocked on the same Wi-Fi."
	exit 1
fi

for id in $selected; do
	i=$ids[(ie)$id]
	print
	print -r -- "$names[i] ($details[i])"
	sideload $id $i
done

print
if (( $#done_names )); then
	if $build_only; then
		print -r -- "${_green}Built for: ${(j:, :)done_names}${_reset}"
	else
		print -r -- "${_green}Installed on: ${(j:, :)done_names}${_reset}"
		if $team_free; then
			print "If Ariel does not open (\"Untrusted Developer\"): Settings › General › VPN & Device Management ›"
			print "your Apple ID › Trust. Free-team builds stop opening on the date above, unless"
			print "scripts/ios-renew.sh schedule renews them; otherwise run this again before."
		fi
	fi
fi
if (( $#failed_names )); then
	print -r -- "${_red}Failed: ${(j:, :)failed_names}${_reset}"
	exit 1
fi
