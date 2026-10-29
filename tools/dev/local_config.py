"""Read local Coach settings; personal addresses never enter source control."""

from pathlib import Path
import ipaddress


def value(key):
    settings = Path.home() / "Coach/config/local.properties"
    for line in settings.read_text().splitlines():
        if line.strip().startswith("#") or "=" not in line:
            continue
        name, text = line.split("=", 1)
        if name.strip() == key:
            return text.strip()
    raise ValueError(f"Set {key} in ~/Coach/config/local.properties")


def tail_host():
    return str(ipaddress.IPv4Address(value("coach.tailHost")))
