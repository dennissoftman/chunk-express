"""Minimal Minecraft RCON client: python scripts/rcon.py <port> <password> <command> [<command> ...]"""
import socket
import struct
import sys


def packet(sock, req_id, kind, body):
    data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(data)) + data)
    length = struct.unpack("<i", sock.recv(4))[0]
    payload = b""
    while len(payload) < length:
        payload += sock.recv(length - len(payload))
    rid, _ = struct.unpack("<ii", payload[:8])
    return rid, payload[8:-2].decode("utf-8", "replace")


def main():
    port, password, commands = int(sys.argv[1]), sys.argv[2], sys.argv[3:]
    with socket.create_connection(("127.0.0.1", port), timeout=120) as sock:
        if packet(sock, 1, 3, password)[0] == -1:
            sys.exit("rcon: wrong password")
        for i, command in enumerate(commands, start=2):
            print(f"> {command}")
            print(packet(sock, i, 2, command)[1])


if __name__ == "__main__":
    main()
