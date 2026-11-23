#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""The second person of the end-to-end identity test (e2e/run.sh).

A server knows a person by the hash of their certificate and tells it to the others. This
connects to the local test server as any foreign client could, speaking the Mumble protocol
itself, and watches the phone (first argument: its user name) come and go. It prints CONNECTED,
then for every time the phone connects either `FIRST 1` or, compared with the time before,
`SAME <n>` or `DIFFERENT <n>`. The hash itself is never printed. The server's port comes from
MUMCORD_PORT_BASE.
"""
import os
import socket
import ssl
import struct
import sys

NAME = "e2e-identity"
VERSION, AUTHENTICATE, PING, SERVER_SYNC, USER_REMOVE, USER_STATE = 0, 2, 3, 5, 8, 9
# Fields of UserState.
SESSION, USER_NAME, CERTIFICATE_HASH = 1, 3, 15


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def number(field, value):
    return varint(field << 3) + varint(value)


def text(field, value):
    data = value.encode()
    return varint((field << 3) | 2) + varint(len(data)) + data


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


def main():
    phone = sys.argv[1]
    port = int(os.environ["MUMCORD_PORT_BASE"])
    # The local test server has a self-signed certificate.
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    connection = context.wrap_socket(socket.create_connection(("127.0.0.1", port)))
    connection.settimeout(5)
    send(connection, VERSION, number(1, (1 << 16) | (5 << 8)) + text(2, "identity-watch"))
    send(connection, AUTHENTICATE, text(1, NAME) + number(5, 1))

    # The phone's sessions seen so far, and the hash of the connection before this one.
    names, counted = {}, set()
    last, connects = None, 0
    while True:
        try:
            kind, length = struct.unpack(">HI", receive(connection, 6))
            payload = receive(connection, length)
        except socket.timeout:
            send(connection, PING)
            continue
        if kind == SERVER_SYNC:
            print("CONNECTED", flush=True)
        elif kind == USER_REMOVE:
            names.pop(fields(payload).get(SESSION, 0), None)
        elif kind == USER_STATE:
            message = fields(payload)
            session = message.get(SESSION, 0)
            if USER_NAME in message:
                names[session] = message[USER_NAME].decode()
            if names.get(session) != phone or session in counted or CERTIFICATE_HASH not in message:
                continue
            counted.add(session)
            connects += 1
            seen = message[CERTIFICATE_HASH]
            if last is None:
                print(f"FIRST {connects}", flush=True)
            else:
                print(f"{'SAME' if seen == last else 'DIFFERENT'} {connects}", flush=True)
            last = seen


if __name__ == "__main__":
    main()
