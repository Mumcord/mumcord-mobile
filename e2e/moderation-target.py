#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""The person the phone moderates in the end-to-end moderation tests (e2e/run.sh).

It speaks the Mumble protocol itself, as any client could: it connects to the local test
server, enters the channel "Lobby" and reports what the server does to it, one line each:

    CONNECTED                 in the Lobby for the first time
    MOVED <channel>           moved into another channel
    REMOVED reason=<text>     taken off the server (a kick, or the kick that ends a ban)
    REFUSED                   the server did not let it back in (a ban); reported once
    BACK                      in the Lobby again after it was removed

After it was removed it connects again and again until the server lets it in, for at most
RETRY_SECONDS, so a ban that ends is seen to end. It stays connected until it is stopped. The
server's port comes from MUMCORD_PORT_BASE.
"""
import os
import socket
import ssl
import struct
import sys
import time

NAME = "e2e-target"
CHANNEL = "Lobby"
# How long it keeps trying to get back in; longer than the ban the test sets.
RETRY_SECONDS = 120
VERSION, AUTHENTICATE, PING, REJECT, SERVER_SYNC, CHANNEL_STATE, USER_REMOVE, USER_STATE = 0, 2, 3, 4, 5, 7, 8, 9


def say(line):
    print(line, flush=True)


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def number(field, value):
    return varint(field << 3) + varint(value)


def text(field, value):
    data = value.encode()
    return varint(field << 3 | 2) + varint(len(data)) + data


def read_varint(payload, at):
    value, shift = 0, 0
    while True:
        byte = payload[at]
        at += 1
        value |= (byte & 0x7F) << shift
        shift += 7
        if not byte & 0x80:
            return value, at


def fields(payload):
    """The fields of a protobuf message: number -> last value (int or bytes)."""
    found, at = {}, 0
    while at < len(payload):
        key, at = read_varint(payload, at)
        field, kind = key >> 3, key & 7
        if kind == 0:
            value, at = read_varint(payload, at)
        elif kind == 2:
            length, at = read_varint(payload, at)
            value = payload[at:at + length]
            at += length
        elif kind == 1:
            value = payload[at:at + 8]
            at += 8
        elif kind == 5:
            value = payload[at:at + 4]
            at += 4
        else:
            raise ValueError(f"unknown wire type {kind}")
        found[field] = value
    return found


def send(connection, kind, payload=b""):
    connection.sendall(struct.pack(">HI", kind, len(payload)) + payload)


def receive(connection, length):
    data = bytearray()
    while len(data) < length:
        part = connection.recv(length - len(data))
        if not part:
            raise ConnectionError("the server closed the connection")
        data += part
    return bytes(data)


def connect(port):
    # The local test server has a self-signed certificate.
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    connection = context.wrap_socket(socket.create_connection(("127.0.0.1", port), timeout=5))
    connection.settimeout(5)
    send(connection, VERSION, number(1, (1 << 16) | (5 << 8)) + text(2, "moderation-target"))
    send(connection, AUTHENTICATE, text(1, NAME) + number(5, 1))
    return connection


def session(connection, returning):
    """One connection, until the server removes this person (True) or refuses it (False)."""
    channels, names = {}, {}
    own, lobby, joined, current = None, None, False, None
    while True:
        try:
            kind, length = struct.unpack(">HI", receive(connection, 6))
            payload = receive(connection, length)
        except socket.timeout:
            send(connection, PING)
            continue
        if kind == REJECT:
            return False
        if kind == CHANNEL_STATE:
            message = fields(payload)
            if 3 in message:
                channels[message[3].decode()] = message.get(1, 0)
                names[message.get(1, 0)] = message[3].decode()
        elif kind == SERVER_SYNC:
            own = fields(payload).get(1, 0)
            lobby = channels[CHANNEL]
            send(connection, USER_STATE, number(1, own) + number(5, lobby))
        elif kind == USER_STATE:
            message = fields(payload)
            if own is None or message.get(1) != own:
                continue
            if 5 in message and message[5] != current:
                current = message[5]
                if not joined and current == lobby:
                    joined = True
                    say("BACK" if returning else "CONNECTED")
                elif joined:
                    say(f"MOVED {names.get(current, current)}")
        elif kind == USER_REMOVE:
            message = fields(payload)
            if own is not None and message.get(1) == own:
                say(f"REMOVED reason={message.get(3, b'').decode()}")
                return True


def main():
    port = int(os.environ["MUMCORD_PORT_BASE"])
    returning, refused, deadline = False, False, None
    while True:
        try:
            connection = connect(port)
            removed = session(connection, returning)
        except (OSError, ConnectionError, ssl.SSLError):
            removed = False
        if removed:
            returning, refused, deadline = True, False, time.monotonic() + RETRY_SECONDS
            continue
        # Not let in: a ban (the server drops a banned address during the handshake).
        if not returning:
            sys.exit("the server did not let the person in")
        if not refused:
            refused = True
            say("REFUSED")
        if time.monotonic() > deadline:
            sys.exit("the server did not let the person back in")
        time.sleep(1)


if __name__ == "__main__":
    main()
