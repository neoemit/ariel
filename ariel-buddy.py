#!/usr/bin/env python3
import os
import sys
import subprocess
import time

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
    else:
        show_help()
