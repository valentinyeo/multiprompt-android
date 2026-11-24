#!/usr/bin/env bash
room_order_send() {
    local text=$1 attempt
    python3 "$scripts/mpa" fill 'Message Order room' "$text"
    python3 "$scripts/mpa" tap text Send || true
    for attempt in {1..30}; do
        if python3 "$scripts/mpa" read --select draft --value '' >/dev/null 2>&1; then return 0; fi
        python3 "$scripts/mpa" tap text Send || true
        sleep 1
    done
    printf 'Room send stayed in the composer: %s\n' "$text" >&2
    return 1
}

room_order_submits() {
    local target=$1
    if sudo test -f /home/verifyssh/room-submits.jsonl; then
        sudo cat /home/verifyssh/room-submits.jsonl > "$target"
    else
        : > "$target"
    fi
}

room_order_background() {
    local attempt
    for attempt in {1..3}; do
        adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
        sleep 3
        if ! python3 "$scripts/mpa" read --select contains --value 'Inbox' >/dev/null 2>&1; then return 0; fi
        adb shell input keyevent KEYCODE_APP_SWITCH >/dev/null 2>&1 || true
        sleep 2
        if ! python3 "$scripts/mpa" read --select contains --value 'Inbox' >/dev/null 2>&1; then return 0; fi
    done
    printf 'The app stayed in the foreground, so it never resumed\n' >&2
    return 1
}

room_order_inbox() {
    local cycle attempt
    for cycle in {1..4}; do
        if python3 "$scripts/mpa" read --select contains --value 'Order room' >/dev/null 2>&1; then return 0; fi
        room_order_background || true
        adb shell am start -W -n "$package/dev.multiprompt.companion.MainActivity" >/dev/null
        for attempt in {1..25}; do
            if python3 "$scripts/mpa" read --select contains --value 'Order room' >/dev/null 2>&1; then return 0; fi
            sleep 1
        done
    done
    return 1
}

room_order_journey() {
    local attempt stage account_id
    setup_failure=1
    export VERIFY_PACKAGE=$package
    account_id=$(python3 "$scripts/room-order-check.py" account-id)
    adb shell am force-stop "$package"
    adb shell am instrument -w \
        -e class dev.multiprompt.companion.account.SeedAccountDeviceTest#seedsDeviceLoginFromInstrumentationArguments \
        -e refresh_token aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
        -e account_id "$account_id" \
        -e device_id verify-phone \
        "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$EVIDENCE/room-account-seed.txt"
    grep -F 'OK (1 test)' "$EVIDENCE/room-account-seed.txt"
    stage=$(mktemp -d)
    mkdir -p "$stage/scripts" "$stage/assets"
    cp "$scripts/room-order-check.py" "$scripts/room-order-runtime.py" "$stage/scripts/"
    cp "$scripts/../../../../app/src/main/assets/rooms/"*.py "$stage/assets/"
    chmod -R a+rX "$stage"
    sudo -u verifyssh env HOME=/home/verifyssh python3 "$stage/scripts/room-order-check.py" install "$stage/assets" \
        | tee "$EVIDENCE/room-fixture.txt"
    grep -F 'ROOM_READY' "$EVIDENCE/room-fixture.txt"
    rm -rf "$stage"
    getent ahosts account.multiprompt.dev | awk '{print $1}' | sort -u > "$EVIDENCE/account-origin-ips.txt"
    python3 "$scripts/room-order-check.py" block-rules "$EVIDENCE/account-origin-ips.txt" > "$EVIDENCE/account-origin-blocked.txt"
    while read -r family ip; do
        if [[ "$family" == v6 ]]; then
            sudo ip6tables -I OUTPUT -d "$ip" -p tcp --dport 443 -j REJECT --reject-with tcp-reset
        else
            sudo iptables -I OUTPUT -d "$ip" -p tcp --dport 443 -j REJECT --reject-with tcp-reset
        fi
    done < "$EVIDENCE/account-origin-blocked.txt"
    relaunch_app
    adb shell run-as "$package" cat shared_prefs/encrypted_secrets.xml > "$EVIDENCE/account-store.txt" || true
    room_order_inbox || true
    python3 "$scripts/mpa" read --select contains --value 'Order room'
    python3 "$scripts/mpa" screenshot room-inbox
    python3 "$scripts/mpa" tap text 'Order room'
    python3 "$scripts/mpa" read --wait-text 'No messages yet'
    setup_failure=0
    python3 "$scripts/mpa" screenshot room-empty
    room_order_submits "$EVIDENCE/room-submits-before.txt"
    python3 "$scripts/room-order-check.py" nothing-sent "$EVIDENCE/room-submits-before.txt"
    room_order_send one
    for attempt in {1..30}; do
        room_order_submits "$EVIDENCE/room-submits-first.txt"
        if python3 "$scripts/room-order-check.py" alpha-only "$EVIDENCE/room-submits-first.txt"; then break; fi
        sleep 1
    done
    python3 "$scripts/room-order-check.py" alpha-only "$EVIDENCE/room-submits-first.txt"
    for attempt in {1..30}; do
        dump_ui || true
        if [[ -f "$EVIDENCE/current.xml" ]] && python3 "$scripts/room-order-check.py" first "$EVIDENCE/current.xml"; then break; fi
        sleep 1
    done
    python3 "$scripts/room-order-check.py" first "$EVIDENCE/current.xml"
    python3 "$scripts/mpa" screenshot room-first-message
    room_order_send two
    for attempt in {1..60}; do
        dump_ui || true
        if [[ -f "$EVIDENCE/current.xml" ]] && python3 "$scripts/room-order-check.py" shown "$EVIDENCE/current.xml"; then break; fi
        sleep 1
    done
    python3 "$scripts/room-order-check.py" shown "$EVIDENCE/current.xml"
    cp "$EVIDENCE/current.xml" "$EVIDENCE/room-ordered.xml"
    python3 "$scripts/mpa" screenshot room-ordered
    room_order_submits "$EVIDENCE/room-submits.txt"
    python3 "$scripts/room-order-check.py" submitted "$EVIDENCE/room-submits.txt"
    printf 'ROOM_ORDER_OK\n'
    exit 0
}
