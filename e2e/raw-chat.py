#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""A third person of the end-to-end chat test (e2e/run.sh) who sends raw markup.

The apps and the bridge never send markup as it is, so this speaks the Mumble protocol itself,
as any foreign client could: it connects to the local test server, enters the channel "Lobby",
waits for the phone (first argument: its user name) to be there and sends one text message
whose content is script and markup, exactly as given. It prints CONNECTED, then RAW SENT, and
stays connected until it is stopped. The server's port comes from MUMCORD_PORT_BASE.
"""
import os
import socket
import ssl
import struct
import sys
import time

NAME = "e2e-raw"
CHANNEL = "Lobby"
# What a hostile client might send. The markers are what the phone must still show as text.
RAW = (
    "<script>alert('pwned')</script>"
    '<b onclick="alert(1)">raw-bold</b> '
    '<img src="x" onerror="alert(2)"> '
    '<a href="javascript:alert(3)">raw-link</a> '
    '<iframe src="https://evil.example/"></iframe>'
    "<style>* { display: none }</style> "
    "raw-marker &lt;i&gt;not italic&lt;/i&gt;"
)
VERSION, AUTHENTICATE, PING, SERVER_SYNC, CHANNEL_STATE, USER_REMOVE, USER_STATE, TEXT_MESSAGE = 0, 2, 3, 5, 7, 8, 9, 11


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


def fields(payload):
    """The fields of a protobuf message: number -> last value (int or bytes)."""
    found, at = {}, 0
    while at < len(payload):
        key, shift = 0, 0
        while True:
            byte = payload[at]
            at += 1
            key |= (byte & 0x7F) << shift
            shift += 7
            if not byte & 0x80:
                break
        field, kind = key >> 3, key & 7
        if kind == 0:
            value, shift = 0, 0
            while True:
                byte = payload[at]
                at += 1
                value |= (byte & 0x7F) << shift
                shift += 7
                if not byte & 0x80:
                    break
        elif kind == 2:
            length, shift = 0, 0
            while True:
                byte = payload[at]
                at += 1
                length |= (byte & 0x7F) << shift
                shift += 7
                if not byte & 0x80:
                    break
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


def main():
    phone = sys.argv[1]
    port = int(os.environ["MUMCORD_PORT_BASE"])
    # The local test server has a self-signed certificate.
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    connection = context.wrap_socket(socket.create_connection(("127.0.0.1", port)))
    connection.settimeout(5)
    send(connection, VERSION, number(1, (1 << 16) | (5 << 8)) + text(2, "raw-chat"))
    send(connection, AUTHENTICATE, text(1, NAME) + number(5, 1))

    channels, people = {}, {}
    own, lobby, joined, sent = None, None, False, False
    while True:
        try:
            kind, length = struct.unpack(">HI", receive(connection, 6))
            payload = receive(connection, length)
        except socket.timeout:
            send(connection, PING)
            continue
        if kind == CHANNEL_STATE:
            message = fields(payload)
            if 3 in message:
                channels[message[3].decode()] = message.get(1, 0)
        elif kind == USER_STATE:
            message = fields(payload)
            person = people.setdefault(message.get(1, 0), {"name": "", "channel": 0})
            if 3 in message:
                person["name"] = message[3].decode()
            if 5 in message:
                person["channel"] = message[5]
        elif kind == USER_REMOVE:
            people.pop(fields(payload).get(1, 0), None)
        elif kind == SERVER_SYNC:
            own = fields(payload).get(1, 0)
            lobby = channels[CHANNEL]
            send(connection, USER_STATE, number(1, own) + number(5, lobby))
        if own is not None and not joined and people.get(own, {}).get("channel") == lobby:
            joined = True
            print("CONNECTED", flush=True)
        phone_here = any(p["name"] == phone and p["channel"] == lobby for p in people.values())
        if joined and not sent and phone_here:
            # A moment for the phone to finish entering the channel.
            time.sleep(1)
            send(connection, TEXT_MESSAGE, number(3, lobby) + text(5, RAW))
            sent = True
            print("RAW SENT", flush=True)


if __name__ == "__main__":
    main()
