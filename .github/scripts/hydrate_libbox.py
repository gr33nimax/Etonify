"""Download the exact existing CI artifact pinned by the checked-in provenance."""
import hashlib
import json
from pathlib import Path
import tempfile
import urllib.parse
import urllib.request

ASSET = "hydracore-client-libbox.aar"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def hydrate(provenance, target):
    expected = provenance["artifacts"][ASSET]["sha256"]
    if target.is_file() and digest(target) == expected:
        return
    source = provenance["source"]
    repository = source["repository"].rstrip("/")
    if not repository.startswith("https://github.com/"):
        raise ValueError("unsupported artifact repository")
    version = urllib.parse.quote(source["version"], safe="")
    url = f"{repository}/releases/download/{version}/{ASSET}"
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, suffix=".part", delete=False) as output:
            temporary = Path(output.name)
            size = 0
            with urllib.request.urlopen(url, timeout=60) as response:
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > 512 * 1024 * 1024:
                        raise ValueError("artifact exceeds size limit")
                    output.write(chunk)
        if digest(temporary) != expected:
            raise ValueError("libbox artifact does not match pinned SHA-256")
        temporary.replace(target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2]
    directory = root / "platform/android/libs"
    hydrate(json.loads((directory / "libbox.provenance.json").read_text()), directory / "libbox.aar")
    print("Pinned libbox artifact verified")
