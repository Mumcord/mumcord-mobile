#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""The second person of the end-to-end profile test (e2e/run.sh).

Connects to the local test server as any foreign client could, speaking the Mumble protocol
itself, and watches the comment and the avatar of the phone (first argument: its user name).
A server announces a long comment or an avatar only by its hash; this then asks for the full
one (RequestBlob), as Mumble clients do. It prints CONNECTED, then `COMMENT <text>` for every
comment of the phone (the test's own fixed text, on one line) and `AVATAR <format> <exif>` for
every avatar: the image format by its signature and whether it still holds EXIF data. The
server's port comes from MUMCORD_PORT_BASE.
"""
import os
import socket
import ssl
import struct
import sys

NAME = "e2e-profile"
VERSION, AUTHENTICATE, PING, SERVER_SYNC, USER_REMOVE, USER_STATE, REQUEST_BLOB = 0, 2, 3, 5, 8, 9, 23
# Fields of UserState.
SESSION, USER_NAME, TEXTURE, COMMENT, COMMENT_HASH, TEXTURE_HASH = 1, 3, 11, 14, 16, 17
# Fields of RequestBlob.
SESSION_TEXTURE, SESSION_COMMENT = 1, 2


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


def image_format(data):
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if data.startswith(b"\xff\xd8"):
        return "jpeg"
    return "unknown"


def has_exif(data):
    # PNG keeps EXIF in an eXIf chunk, JPEG in an APP1 segment that starts with "Exif".
    return b"eXIf" in data or b"Exif\x00\x00" in data


def main():
    phone = sys.argv[1]
    port = int(os.environ["MUMCORD_PORT_BASE"])
    # The local test server has a self-signed certificate.
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    connection = context.wrap_socket(socket.create_connection(("127.0.0.1", port)))
    connection.settimeout(5)
    send(connection, VERSION, number(1, (1 << 16) | (5 << 8)) + text(2, "profile-watch"))
    send(connection, AUTHENTICATE, text(1, NAME) + number(5, 1))

    names = {}
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
            if names.get(session) != phone:
                continue
            if COMMENT in message:
                comment = message[COMMENT].decode()
                print(f"COMMENT {' '.join(comment.split())}", flush=True)
            elif COMMENT_HASH in message:
                send(connection, REQUEST_BLOB, number(SESSION_COMMENT, session))
            if TEXTURE in message and message[TEXTURE]:
                texture = message[TEXTURE]
                print(f"AVATAR {image_format(texture)} {'exif' if has_exif(texture) else 'no-exif'}", flush=True)
            elif TEXTURE_HASH in message:
                send(connection, REQUEST_BLOB, number(SESSION_TEXTURE, session))


if __name__ == "__main__":
    main()
