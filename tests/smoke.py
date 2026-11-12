"""Vérifie la découverte, la configuration et les métriques.
Checks discovery, configuration, and metrics.
"""

import hashlib
import ipaddress
import json
import os
from pathlib import Path
import runpy
import socket
import ssl
import stat
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PASSWORD = "essai-métriques-2026"


def discover(port):
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
                client.sendto(b"LAN-VITALS/1 DISCOVER", (str(interface.network.broadcast_address), port))
                try:
                    packet, address = client.recvfrom(1024)
                except TimeoutError:
                    continue
                assert address[1] == port
                message = json.loads(packet)
                assert message["type"] == "LAN-VITALS/1"
                return address[0], message["port"]
    raise RuntimeError("Aucune réponse UDP sur les interfaces LAN actives")


def exchange(endpoint, identity, password, expected, detail=False):
    context = ssl.create_default_context(cafile=str(identity))
    with socket.create_connection(endpoint, timeout=5) as client:
        with context.wrap_socket(client, server_hostname="lan-vitals.local") as stream:
            fingerprint = hashlib.sha256(stream.getpeercert(binary_form=True)).hexdigest()
            with stream.makefile("r", encoding="utf-8") as reader:
                request = {"password": password, "details": True} if detail else {"password": password}
                stream.sendall((json.dumps(request, ensure_ascii=False) + "\n").encode())
                assert json.loads(reader.readline())["type"] == expected
                if expected == "ok":
                    samples = [json.loads(reader.readline()) for _ in range(3)]
                    for sample in samples:
                        assert sample["type"] == "metrics"
                        assert 0 <= sample["cpu"] <= 100
                        assert 0 <= sample["ram_used"] <= sample["ram_total"]
                        assert 0 <= sample["disk_free"] <= sample["disk_total"]
                        assert sample["rx"] >= 0 and sample["tx"] >= 0
                        if detail:
                            memory = sample["memory"]
                            assert memory["MemAvailable"] + sample["ram_used"] == memory["MemTotal"]
                            assert 0 <= memory["SwapFree"] <= memory["SwapTotal"]
                            assert "Cached" in memory and "Buffers" in memory
                            assert "lo" in {interface["name"] for interface in sample["interfaces"]}
                            assert any("read_iops" in device for device in sample["disks"])
                            assert len({device["id"] for device in sample["disks"]}) == len(sample["disks"])
                        else:
                            assert not {"memory", "interfaces", "disks"} & sample.keys()
                    assert samples[-1]["uptime"] > samples[0]["uptime"]
                return fingerprint


def smoke():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp, socket.socket() as tcp:
        udp.bind(("", 0))
        tcp.bind(("", 0))
        discovery_port, stream_port = udp.getsockname()[1], tcp.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="lan-vitals-smoke-") as directory:
        identity = Path(directory) / "identity.pem"
        config = Path(directory) / "config.json"
        fingerprint = None
        env = {key: value for key, value in os.environ.items() if key != "LAN_VITALS_PASSWORD"}
        for index in range(2):
            arguments = [sys.executable, "-c",
                         "import runpy, sys; server = runpy.run_path(sys.argv.pop(1)); "
                         "server['main'].__globals__['DISCOVERY_PORT'] = int(sys.argv.pop(1)); "
                         "sys.exit(server['main']())",
                         ROOT / "lan-vitals-server", str(discovery_port), "--config", config]
            if index == 0:
                arguments += ["--state-dir", directory, "--port", str(stream_port), "--password", PASSWORD, "--lang", "en"]
            with subprocess.Popen(
                arguments, stdout=subprocess.PIPE, text=True, env=env,
            ) as server:
                try:
                    assert f"TCP {stream_port}" in server.stdout.readline()
                    label, reported = server.stdout.readline().strip().split(" : ", 1)
                    assert label == "Pairing fingerprint"
                    endpoint = discover(discovery_port)
                    assert endpoint[1] == stream_port
                    if index == 0:
                        exchange(endpoint, identity, "incorrect", "denied")
                        exchange(endpoint, identity, PASSWORD, "ok")
                    current = exchange(endpoint, identity, PASSWORD, "ok", detail=True)
                    assert current.startswith(reported.replace(":", "").lower())
                    if fingerprint:
                        assert current == fingerprint
                    fingerprint = current
                    assert stat.S_IMODE(config.stat().st_mode) == 0o600
                    assert PASSWORD not in config.read_text()
                    stored = json.loads(config.read_text())
                    assert stored["lang"] == "en" and stored["port"] == stream_port
                finally:
                    server.terminate()
                    server.wait(timeout=5)
    print("OK — UDP, TLS, authentification/authentication, métriques/metrics, configuration.")


def verify_disk_rates():
    metrics = runpy.run_path(str(ROOT / "lan-vitals-server"))["disk_metrics"]
    devices = [{"maj:min": "8:0", "kname": "sda", "type": "disk", "size": 4096, "model": None, "tran": None}]
    previous = {"8:0": [100, 0, 1000, 1000, 100, 0, 2000, 1000, 0, 1000, 1000]}
    current = {"8:0": [104, 0, 1016, 1008, 102, 0, 2032, 1006, 1, 1500, 3000]}
    disk = metrics(devices, previous, current, 2)[0]
    assert (disk["read_bps"], disk["write_bps"]) == (4096, 8192)
    assert (disk["read_iops"], disk["write_iops"], disk["read_ms"], disk["write_ms"]) == (2, 1, 2, 3)
    assert (disk["busy"], disk["queue"], disk["in_flight"]) == (25, 1, 1)


def verify_missing_commands():
    with tempfile.TemporaryDirectory(prefix="lan-vitals-dependencies-") as directory:
        config = Path(directory) / "config.json"
        state = Path(directory) / "state"
        for language, heading in (("fr", "Prérequis non satisfaits"), ("en", "Unmet requirements")):
            result = subprocess.run(
                [sys.executable, str(ROOT / "lan-vitals-server"), "--lang", language,
                 "--config", str(config), "--state-dir", str(state)],
                env={**os.environ, "PATH": directory}, capture_output=True, text=True, timeout=5,
            )
            assert result.returncode == 1 and not result.stdout
            for message in (heading, "iproute2", "util-linux", "openssl"):
                assert message in result.stderr, result.stderr
            assert not config.exists() and not state.exists()


if __name__ == "__main__":
    verify_missing_commands()
    verify_disk_rates()
    smoke()
