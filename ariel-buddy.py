#!/usr/bin/env python3
import json
import os
import sys
import subprocess
import time
import urllib.request
import uuid

BUDDY_NAME = "VirtualBuddy_01"
DEFAULT_RELAY_URL = "https://ariel.lmbdev.co.za"

def run_adb(args):
    try:
        full_cmd = ['adb'] + args
        result = subprocess.run(full_cmd, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"ADB Error: {result.stderr}")
        return result.stdout.strip()
    except FileNotFoundError:
        print("Error: adb command not found. Please install Android Platform Tools.")
        sys.exit(1)

def show_help():
    print("Ariel Virtual Buddy CLI")
    print("Usage:")
    print("  python3 ariel-buddy.py pair      - Show QR code data to scan with your phone")
    print("  python3 ariel-buddy.py panic     - Trigger a panic alert on your phone from 'Virtual Buddy'")
    print("  python3 ariel-buddy.py stop      - Stop the siren on your phone")
    print("  python3 ariel-buddy.py monitor   - Wait for panic signals FROM your phone and show alert")
    print("")
    print("Through the relay backend (any phone, including iPhones; set ARIEL_RELAY_URL, default")
    print(f"{DEFAULT_RELAY_URL}):")
    print("  python3 ariel-buddy.py relay-panic <buddyId> [GENERIC|MEDICAL|ARMED]")
    print("                                   - Send a panic from 'Virtual Buddy' to that phone's ID")
    print("  python3 ariel-buddy.py relay-monitor")
    print("                                   - Stay online as 'Virtual Buddy' and show the panics and")
    print("                                     acknowledgments it receives; offers to acknowledge panics")

def pair():
    buddy_name = "VirtualBuddy_01"
    print("\n=== PAIRING VIRTUAL BUDDY ===")
    print(f"Buddy Name: {buddy_name}")
    print("\nTo pair:")
    print("1. Open Ariel on your phone.")
    print("2. Go to the Pairing tab.")
    print("3. Tap 'SCAN FRIENDS QR'.")
    print(f"4. The scanner expects the plain text: {buddy_name}")
    print("\nSince I can't easily draw a high-res QR in every terminal,")
    print("you can use this URL to see the QR code for scanning:")
    print(f"https://api.qrserver.com/v1/create-qr-code/?size=300x300&data={buddy_name}")
    print("==============================\n")

def get_device():
    devices = run_adb(['devices']).split('\n')
    connected = [d for d in devices if d.strip() and 'device' in d and 'List of' not in d]
    if not connected:
        return None
    return connected[0].split('\t')[0]

def panic():
    device_id = get_device()
    if not device_id:
        print("Error: No device connected via ADB.")
        return

    print(f"Sending Panic Signal from Virtual Buddy to {device_id}...")
    
    cmd = [
        '-s', device_id, 'shell', 'am', 'start-foreground-service',
        '-a', 'SIMULATED_PANIC',
        '--es', 'SENDER_NAME', 'VirtualBuddy_01',
        '-n', 'com.thomaslamendola.ariel/.SirenService'
    ]
    output = run_adb(cmd)
    if "Error" in output or "Exception" in output:
        print(f"Failed to start service: {output}")
    else:
        print("Alert Trigger Signal Sent!")

def stop():
    device_id = get_device()
    if not device_id: return
    run_adb(['-s', device_id, 'shell', 'am', 'start-foreground-service', '-a', 'STOP_SIREN', '-n', 'com.thomaslamendola.ariel/.SirenService'])
    print("Siren Stop Signal Sent.")

def monitor():
    device_id = get_device()
    if not device_id:
        print("Error: No device connected via ADB.")
        return

    print(f"Monitoring device {device_id} for Panic Signals...")
    print("Press Ctrl+C to stop monitoring.\n")
    
    # Clear logs first
    run_adb(['-s', device_id, 'logcat', '-c'])
    
    process = subprocess.Popen(
        ['adb', '-s', device_id, 'logcat', '-s', 'NearbyManager:D'],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )

    try:
        while True:
            line = process.stdout.readline()
            if "BROADCASTING PANIC FROM" in line:
                name = line.split("FROM")[-1].strip()
                print("\n" + "!"*40)
                print(f"!!! RECEIVED PANIC ALERT FROM: {name} !!!")
                print("!"*40)
                print("\nOptions:")
                print("  [A] Acknowledge alert (simulates friend responding)")
                print("  [S] Stop Siren on phone")
                print("  [Q] Quit monitoring")
                
                choice = input("\nSelect an option: ").strip().upper()
                if choice == 'A':
                    run_adb(['-s', device_id, 'shell', 'am', 'start-foreground-service', 
                           '-a', 'SIMULATED_ACK', 
                           '--es', 'SENDER_NAME', 'VirtualBuddy_01',
                           '-n', 'com.thomaslamendola.ariel/.SirenService'])
                    print("\nAcknowledge signal sent!")
                elif choice == 'S':
                    stop()
                elif choice == 'Q':
                    break
    except KeyboardInterrupt:
        print("\nStopping monitor...")
    finally:
        process.terminate()

def relay_url():
    return os.environ.get("ARIEL_RELAY_URL", DEFAULT_RELAY_URL).rstrip("/")


def relay_post(path, body):
    request = urllib.request.Request(
        relay_url() + path,
        data=json.dumps(body).encode("utf-8"),
        # Cloudflare in front of a relay refuses urllib's default User-Agent ("error code: 1010").
        headers={"Content-Type": "application/json", "Accept": "application/json", "User-Agent": "ariel-buddy"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8") or "{}")


def relay_panic(recipient, escalation="GENERIC"):
    event_id = str(uuid.uuid4())
    result = relay_post("/v1/panic", {
        "senderId": BUDDY_NAME,
        "eventId": event_id,
        "escalationType": escalation.upper(),
        "recipientIds": [recipient],
    })
    print(f"Panic {event_id} sent to {recipient} through {relay_url()}: {result}")


def relay_monitor():
    print(f"Online as {BUDDY_NAME} on {relay_url()}. Add it on your phone (Pairing tab, enter the ID).")
    print("Press Ctrl+C to stop.\n")
    cursor = relay_post("/v1/events", {"buddyId": BUDDY_NAME, "afterId": 0}).get("cursor", 0)
    last_heartbeat = 0.0
    try:
        while True:
            if time.time() - last_heartbeat > 60:
                relay_post("/v1/heartbeat", {"buddyId": BUDDY_NAME, "platform": "cli"})
                last_heartbeat = time.time()
            response = relay_post("/v1/events", {"buddyId": BUDDY_NAME, "afterId": cursor})
            cursor = response.get("cursor", cursor)
            for event in response.get("events", []):
                if event.get("type") == "panic":
                    sender = event.get("senderId")
                    print("\n" + "!" * 40)
                    print(f"!!! PANIC ({event.get('escalationType')}) FROM: {sender} !!!")
                    print("!" * 40)
                    if input("Acknowledge it? [y/N] ").strip().lower() == "y":
                        relay_post("/v1/ack", {
                            "senderId": sender,
                            "acknowledgerId": BUDDY_NAME,
                            "eventId": event.get("eventId"),
                        })
                        print("Acknowledgment sent.")
                elif event.get("type") == "ack":
                    print(f"{event.get('acknowledgerId')} acknowledged panic {event.get('eventId')}")
            time.sleep(3)
    except KeyboardInterrupt:
        print("\nStopping monitor...")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        show_help()
    elif sys.argv[1] == "pair":
        pair()
    elif sys.argv[1] == "panic":
        panic()
    elif sys.argv[1] == "stop":
        stop()
    elif sys.argv[1] == "monitor":
        monitor()
    elif sys.argv[1] == "relay-panic" and len(sys.argv) >= 3:
        relay_panic(sys.argv[2], sys.argv[3] if len(sys.argv) >= 4 else "GENERIC")
    elif sys.argv[1] == "relay-monitor":
        relay_monitor()
    else:
        show_help()
