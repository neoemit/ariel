#!/bin/zsh
# Keeps Ariel working on your iPhones and iPads: updates them to the latest main, and renews its
# signature before a free Apple team's 7 days run out (docs/ios.md › Keeping the app working).
#
#   scripts/ios-renew.sh [--device UDID...] [--dry-run] [--force]
#   scripts/ios-renew.sh schedule [--device UDID...]   # every 2 hours and at login, with launchd
#   scripts/ios-renew.sh unschedule
#
# It serves the iPhones and iPads paired with this Mac that already have Ariel, so
# scripts/ios-sideload.sh enrolls a device by installing the app there once. Devices named with
# --device get the app even if they do not have it.
#
# A device gets the app when it has an older build, has a copy whose signature date this Mac does not
# know, or has less than RENEW_DAYS of signature left; --force installs it anyway, with a fresh
# signature. A device with a newer build, sideloaded from a checkout, is left alone. --dry-run says
# what it would do, and does nothing.
#
# What it installs is always built from origin/main (what the Play Store internal track gets too), in
# a folder of its own: never from this checkout, whatever is in it. A change merged to main reaches
# the devices with the next run.
#
# Needs Xcode signed in to the team's Apple ID (Xcode › Settings › Accounts). Scheduled runs log to
# ~/Library/Logs/ariel-ios-renew.log and show a notification when something needs you.

# launchd starts jobs with a minimal PATH; jq comes from Homebrew.
path=($path /opt/homebrew/bin /usr/local/bin /usr/bin /bin /usr/sbin /sbin)

SCRIPT=${0:A}
source ${SCRIPT:h}/ios-check-env.sh
source ${SCRIPT:h}/ios-common.sh
zmodload zsh/system

RENEW_DAYS=3
BRANCH=main
LABEL=com.thomaslamendola.ariel.ios-renew
AGENT=$HOME/Library/LaunchAgents/$LABEL.plist
LOG=$HOME/Library/Logs/ariel-ios-renew.log
SOURCE=$IOS_DATA/source
DERIVED=$IOS_DATA/build
APP=$DERIVED/Build/Products/Release-iphoneos/Ariel.app
PROFILE_DIRS=("$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles" "$HOME/Library/MobileDevice/Provisioning Profiles")

log() { print -r -- "$(date '+%Y-%m-%d %H:%M:%S') $*" }

# A notification for scheduled runs (nobody reads their log), at most every 6 hours.
notify() {
	[[ -t 1 ]] && return
	local now=$(date +%s) last=$(read_state '.notified // 0')
	(( now - ${last:-0} < 6 * 3600 )) && return
	write_state --argjson t $now '.notified = $t'
	osascript - $1 <<-'EOF' >/dev/null || true
		on run argv
			display notification (item 1 of argv) with title "Ariel on iPhone and iPad"
		end run
	EOF
}

fail() {
	log "error: $1"
	notify $1
	exit 1
}

usage() {
	print -u2 "usage: ${SCRIPT:t} [schedule | unschedule] [--device UDID...] [--dry-run] [--force]"
	exit 2
}

device_name() {
	jq -r --arg d $1 '[.result.devices[] | select(.hardwareProperties.udid == $d) | .deviceProperties.name][0] // $d' \
		$work/devices.json 2>/dev/null || print -r -- $1
}

paired_devices() {
	jq -r '.result.devices[] | select(.hardwareProperties.reality == "physical" and .hardwareProperties.platform == "iOS"
		and .connectionProperties.pairingState == "paired")
		| .hardwareProperties.udid' $work/devices.json 2>/dev/null || true
}

# Stops unless every --device is a physical iPhone or iPad paired with this Mac (never a simulator).
check_devices() {
	local d
	for d in $devices; do
		jq -e --arg d $d 'any(.result.devices[]; .hardwareProperties.udid == $d
			and .hardwareProperties.reality == "physical" and .hardwareProperties.platform == "iOS")' \
			$work/devices.json >/dev/null 2>&1 ||
			fail "$d is not an iPhone or iPad paired with this Mac: simulators never get the app"
	done
}

# Developer Mode on a device: enabled, disabled or unknown (away). The list may remember an old
# state, so a device it does not show as enabled is asked.
developer_mode() {
	local mode=$(jq -r --arg d $1 '[.result.devices[] | select(.hardwareProperties.udid == $d)
		| .deviceProperties.developerModeStatus][0] // empty' $work/devices.json 2>/dev/null)
	if [[ $mode != enabled ]] && xcrun devicectl --timeout 60 device info details --device $1 \
		--json-output $work/details.json >/dev/null 2>&1; then
		mode=$(jq -r '.result.deviceProperties.developerModeStatus // empty' $work/details.json)
	fi
	print -r -- ${mode:-unknown}
}

# A device that could not get the app: a notification when its copy stops opening within a day.
warn_if_expiring() {
	local expires=$(read_state --arg d $1 '.devices[$d].expires // empty')
	[[ -n $expires ]] && (( $(to_epoch $expires) - $(date +%s) < 86400 )) || return 0
	notify "Ariel on $(device_name $1) stops opening on $(local_date $expires). Bring it onto this Mac's Wi-Fi, unlocked, or connect it by cable."
}

# Sets commit, version and build to origin/main's. The last one known is used when GitHub cannot be
# reached, since the devices still need renewing.
latest_main() {
	if git fetch -q origin $BRANCH 2>/dev/null; then
		commit=$(git rev-parse FETCH_HEAD)
		write_state --arg c $commit '.commit = $c'
	else
		commit=$(read_state '.commit // empty')
		[[ -n $commit ]] || fail "GitHub cannot be reached, and no commit of $BRANCH is known yet"
		log "GitHub cannot be reached; using ${commit[1,8]}"
	fi
	local numbers
	numbers=$(ariel_version "$(git show $commit:app/build.gradle.kts 2>/dev/null)") ||
		fail "${commit[1,8]} has no usable appVersionMinor in app/build.gradle.kts"
	version=${numbers% *}
	build=${numbers#* }
}

# Puts that commit's source in a folder of its own: what the devices get never depends on this
# checkout.
export_source() {
	[[ $(cat $SOURCE/.commit 2>/dev/null) != $commit ]] || return 0
	rm -rf $SOURCE
	mkdir -p $SOURCE
	git archive $commit ios app/build.gradle.kts | tar -xf - -C $SOURCE
	# Gitignored, so not in the commit; a paid team's push needs it.
	[[ ! -f ios/Ariel/Resources/GoogleService-Info.plist ]] || cp ios/Ariel/Resources/GoogleService-Info.plist $SOURCE/ios/Ariel/Resources/
	print -r -- $commit > $SOURCE/.commit
}

# Xcode hands out its cached profile until that expires; without it, Apple issues a new one for 7
# days. So a profile older than a day, of the app or the widget, goes first: otherwise a renewal
# would not renew anything. It happens once per run.
drop_old_profiles() {
	$profiles_dropped && return 0
	profiles_dropped=true
	local dir profile identifier expires
	for dir in $PROFILE_DIRS; do
		for profile in $dir/*.mobileprovision(N); do
			identifier=$(profile_value $profile Entitlements.application-identifier || true)
			[[ $identifier == $team.$bundle_id || $identifier == $team.$bundle_id.widget ]] || continue
			expires=$(profile_value $profile ExpirationDate || true)
			if $force || [[ -z $expires ]] || (( $(to_epoch $expires) - $(date +%s) < 6 * 86400 )); then
				rm -f $profile
			fi
		done
	done
}

# Builds and signs main for a destination. The old app goes first, so that a failed build can never
# install it. Shows Xcode's errors when it fails.
build_main() {
	local destination=$1 reply
	drop_old_profiles
	export_source
	mkdir -p $DERIVED
	rm -rf $DERIVED/Build/Products
	signing_settings
	local arguments=(-project $SOURCE/ios/Ariel.xcodeproj -scheme Ariel -configuration Release
		-destination $destination -derivedDataPath $DERIVED -allowProvisioningUpdates $reply)
	[[ $destination != id=* ]] || arguments+=(-allowProvisioningDeviceRegistration)
	if ! xcodebuild build $arguments > $IOS_DATA/last-build.log 2>&1; then
		grep -E 'error:' $IOS_DATA/last-build.log | sort -u | head -5 || true
		return 1
	fi
	[[ -d $APP && $(plutil -extract CFBundleVersion raw $APP/Info.plist) == $build ]]
}

# Whether the built app's profiles, the app's and the widget's, include a device.
profiles_include() {
	local bundle
	for bundle in $APP $APP/PlugIns/*.appex(N); do
		profile_value $bundle/embedded.mobileprovision ProvisionedDevices json |
			jq -e --arg d $1 'index($d) != null' >/dev/null || return 1
	done
}

# When the built app stops opening: the first of its profiles to run out.
signed_until() {
	local bundle
	for bundle in $APP $APP/PlugIns/*.appex(N); do
		print -r -- "$(profile_value $bundle/embedded.mobileprovision ExpirationDate)"
	done | sort | sed -n '/./{p;q;}'
}

run() {
	work=$(mktemp -d -t ariel-ios-renew)
	mkdir -p $IOS_DATA
	check_env > $work/check.log 2>&1 || { cat $work/check.log; fail "this Mac cannot sign the app now (see above)" }
	cd $ROOT
	list_ios_devices
	check_devices
	touch $IOS_DATA/.lock
	if ! zsystem flock -t 0 $IOS_DATA/.lock; then
		log "waiting for another run to finish"
		zsystem flock -t 900 $IOS_DATA/.lock || fail "another run is still going"
	fi
	latest_main
	local targets=($devices)
	(( $#targets )) || targets=($(paired_devices))
	(( $#targets )) || fail "no iPhone or iPad is paired with this Mac (docs/ios.md › Sideloading)"

	local now=$(date +%s) due=() d name app_info expires why wanted
	local release="$version ($build)"
	for d in $targets; do
		name=$(device_name $d)
		expires=$(read_state --arg d $d '.devices[$d].expires // empty')
		# Without Developer Mode the device mounts no developer disk image, so nothing can be read
		# from it or installed on it.
		if [[ $(developer_mode $d) == disabled ]]; then
			log "$name: Developer Mode is off (Settings › Privacy & Security › Developer Mode, then restart and confirm)"
			[[ -z $expires ]] || notify "Ariel on $name cannot be renewed with Developer Mode off: Settings › Privacy & Security › Developer Mode, then restart and confirm."
			continue
		fi
		if ! app_info=($(installed_app $d)); then
			log "$name: not reachable${expires:+, signed until $(local_date $expires)}"
			warn_if_expiring $d
			continue
		fi
		wanted=false
		(( ! $#devices )) || wanted=true
		if (( ! $#app_info )) && ! $wanted; then
			log "$name: does not have Ariel; leaving it (scripts/ios-sideload.sh installs it there)"
			continue
		fi
		if $force; then
			why="asked for"
		elif (( ! $#app_info )); then
			why="not installed"
		elif [[ $app_info[1] == <-> ]] && (( app_info[1] > build )); then
			log "$name: has $app_info[2] ($app_info[1]), newer than $release; leaving it"
			continue
		elif [[ $app_info[1] != $build ]]; then
			why="update from $app_info[2] ($app_info[1])"
		elif [[ -z $expires || $(read_state --arg d $d '.devices[$d].url // empty') != "$app_info[3]" ]]; then
			why="installed by something else, so its signature date is unknown"
		elif (( $(to_epoch $expires) - now < RENEW_DAYS * 86400 )); then
			why="signature runs out $(local_date $expires)"
		else
			log "$name: $release, signed until $(local_date $expires)"
			continue
		fi
		log "$name: installing $release ($why)"
		due+=($d)
	done
	(( $#due )) || return 0
	if $dry_run; then
		log "dry run: nothing was built or installed"
		return 0
	fi

	if ! build_main generic/platform=iOS; then
		log "building ${commit[1,8]} failed; the next run tries again (log: $IOS_DATA/last-build.log)"
		notify "Ariel $version could not be built for the iPhones and iPads. Is Xcode still signed in to team $team (Xcode › Settings › Accounts)? See $LOG."
		for d in $due; do
			warn_if_expiring $d
		done
		return 1
	fi
	local failed=0 ends
	for d in $due; do
		name=$(device_name $d)
		# A device the team does not have yet is registered by building for it.
		if ! profiles_include $d && ! { build_main id=$d && profiles_include $d }; then
			log "$name: Xcode could not get a profile that includes it"
			notify "Xcode could not get a profile for $name. Is it still signed in to team $team (Xcode › Settings › Accounts)? See $LOG."
			failed=1
			continue
		fi
		ends=$(signed_until)
		if [[ -z $ends ]]; then
			log "$name: the build has no signature date; the next run tries again"
			failed=1
			continue
		fi
		if xcrun devicectl --timeout 600 device install app --device $d $APP > $work/install.log 2>&1 && note_install $d "$ends"; then
			log "$name: installed $release, signed until $(local_date $ends)"
		else
			grep -iE 'error|fail' $work/install.log | head -3 || true
			log "$name: installing failed; the next run tries again"
			warn_if_expiring $d
			failed=1
		fi
	done
	return $failed
}

schedule() {
	work=$(mktemp -d -t ariel-ios-renew)
	check_env > $work/check.log 2>&1 || { cat $work/check.log; fail "this Mac cannot sign the app now (see above)" }
	list_ios_devices
	check_devices
	mkdir -p ${AGENT:h} ${LOG:h}
	local arguments=(/bin/zsh $SCRIPT) d argument
	for d in $devices; do
		arguments+=(--device $d)
	done
	launchctl bootout gui/$UID/$LABEL 2>/dev/null || true
	plutil -create xml1 $AGENT
	plutil -insert Label -string $LABEL $AGENT
	plutil -insert ProgramArguments -array $AGENT
	for argument in $arguments; do
		plutil -insert ProgramArguments -string $argument -append $AGENT
	done
	plutil -insert EnvironmentVariables -dictionary $AGENT
	plutil -insert EnvironmentVariables.APPLE_DEVELOPMENT_TEAM -string $team $AGENT
	plutil -insert StartInterval -integer 7200 $AGENT
	plutil -insert RunAtLoad -bool true $AGENT
	plutil -insert StandardOutPath -string $LOG $AGENT
	plutil -insert StandardErrorPath -string $LOG $AGENT
	launchctl bootstrap gui/$UID $AGENT
	log "scheduled every 2 hours and at login ($AGENT); it logs to $LOG"
}

unschedule() {
	launchctl bootout gui/$UID/$LABEL 2>/dev/null || true
	rm -f $AGENT
	log "no longer scheduled"
}

command=run
case ${1:-} in
	schedule | unschedule) command=$1 && shift ;;
esac
devices=()
dry_run=false
force=false
in_app=false
profiles_dropped=false
commit= version= build=
work=
trap '[[ -z $work ]] || rm -rf $work' EXIT
while (( $# )); do
	case $1 in
		--device) (( $# > 1 )) || usage; devices+=($2); shift 2 ;;
		--dry-run) dry_run=true; shift ;;
		--force) force=true; shift ;;
		*) usage ;;
	esac
done
$command
