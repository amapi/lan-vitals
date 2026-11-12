"""Exercise LAN discovery, certificate continuity, password and live samples."""

import hashlib
import ipaddress
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PASSWORD = "essai-métriques-2026"


def discover():
    links = json.loads(subprocess.check_output(["ip", "-j", "-4", "addr", "show", "up"]))
    for link in links:
        if not {"LOWER_UP", "BROADCAST"}.issubset(link["flags"]):
            continue
        for entry in link.get("addr_info", []):
            interface = ipaddress.IPv4Interface(f'{entry["local"]}/{entry["prefixlen"]}')
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                client.bind((str(interface.ip), 0))
                client.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                client.settimeout(1)
                client.sendto(b"LAN-VITALS/1 DISCOVER", (str(interface.network.broadcast_address), 48321))
                try:
                    packet, address = client.recvfrom(1024)
                except TimeoutError:
                    continue
                assert address[1] == 48321
                message = json.loads(packet)
                assert message["type"] == "LAN-VITALS/1"
                return address[0], message["port"]
    raise RuntimeError("Aucune réponse UDP sur les interfaces LAN actives")


def exchange(endpoint, identity, password, expected):
    context = ssl.create_default_context(cafile=str(identity))
    with socket.create_connection(endpoint, timeout=5) as client:
        with context.wrap_socket(client, server_hostname="lan-vitals.local") as stream:
            fingerprint = hashlib.sha256(stream.getpeercert(binary_form=True)).hexdigest()
            with stream.makefile("r", encoding="utf-8") as reader:
                stream.sendall((json.dumps({"password": password}, ensure_ascii=False) + "\n").encode())
                assert json.loads(reader.readline())["type"] == expected
                if expected == "ok":
                    samples = [json.loads(reader.readline()) for _ in range(3)]
                    for sample in samples:
                        assert sample["type"] == "metrics"
                        assert 0 <= sample["cpu"] <= 100
                        assert 0 <= sample["ram_used"] <= sample["ram_total"]
                        assert 0 <= sample["disk_free"] <= sample["disk_total"]
                        assert sample["rx"] >= 0 and sample["tx"] >= 0
                    assert samples[-1]["uptime"] > samples[0]["uptime"]
                return fingerprint


def smoke():
    with tempfile.TemporaryDirectory(prefix="lan-vitals-smoke-") as directory:
        identity = Path(directory) / "identity.pem"
        fingerprint = None
        for index in range(2):
            with subprocess.Popen(
                [ROOT / "lan-vitals-server", "--state-dir", directory],
                stdout=subprocess.PIPE, text=True, env={**os.environ, "LAN_VITALS_PASSWORD": PASSWORD},
            ) as server:
                try:
                    assert server.stdout.readline().startswith("LAN Vitals")
                    reported = server.stdout.readline().split(" : ", 1)[1].strip()
                    endpoint = discover()
                    if index == 0:
                        exchange(endpoint, identity, "incorrect", "denied")
                    current = exchange(endpoint, identity, PASSWORD, "ok")
                    assert current.startswith(reported.replace(":", "").lower())
                    if fingerprint:
                        assert current == fingerprint
                    fingerprint = current
                finally:
                    server.terminate()
                    server.wait(timeout=5)
    print("OK — découverte UDP, TLS, rejet du mauvais mot de passe, métriques et redémarrage.")


if __name__ == "__main__":
    smoke()
