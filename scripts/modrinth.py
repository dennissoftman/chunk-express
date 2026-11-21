"""Create or update the Chunk Express project page on Modrinth from docs/MODRINTH.md.

    python scripts/modrinth.py create    # create the project as a private draft (with the icon)
    python scripts/modrinth.py update    # push the current description and summary to the draft
    python scripts/modrinth.py gallery   # upload the showcase GIF as the featured gallery image
    python scripts/modrinth.py version 0.1.0 <jar>   # upload a version (Beta) with its CHANGELOG.md section
    python scripts/modrinth.py show      # read the project back

The token is read from MODRINTH_TOKEN or from modrinth.token in ~/.gradle/gradle.properties. It is
never printed. Nothing here submits the project for review or makes it public.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

API = "https://api.modrinth.com/v2"
ROOT = Path(__file__).resolve().parent.parent
LISTING = ROOT / "docs" / "MODRINTH.md"
ICON = ROOT / "art" / "icon.png"
# Two consecutive 12-second clips of the same ride (one long GIF would exceed Modrinth's 5 MiB limit).
SHOWCASE = [
    (ROOT / "art" / "showcase" / "vanilla-vs-chunkexpress-1.gif", "Vanilla vs Chunk Express (1/2)",
     "Same seed, real time: a full-speed ice-boat ride, 4-16 s in. "
     "Vanilla keeps slowing down while it waits for chunks; Chunk Express holds full speed.", False),
    (ROOT / "art" / "showcase" / "vanilla-vs-chunkexpress-2.gif", "Vanilla vs Chunk Express (2/2)",
     "The same ride, 16-29 s in: vanilla slows down again, then runs out of loaded road and stops. "
     "Chunk Express keeps going.", True),
]
SLUG = "chunk-express"
USER_AGENT = "chunk-express-publishing-script"
MARKER = "<!-- Everything below this line is the Modrinth description (Markdown). -->"


def token():
    value = os.environ.get("MODRINTH_TOKEN")
    if not value:
        props = Path.home() / ".gradle" / "gradle.properties"
        if props.exists():
            for line in props.read_text(encoding="utf-8").splitlines():
                if line.strip().startswith("modrinth.token="):
                    value = line.split("=", 1)[1].strip()
    if not value:
        sys.exit("No token: set MODRINTH_TOKEN or modrinth.token in ~/.gradle/gradle.properties")
    return value


def listing():
    text = LISTING.read_text(encoding="utf-8")
    summary = re.search(r"^\| Summary \(≤ 256 chars\) \| (.+?) \|$", text, re.M).group(1).strip()
    body = text.split(MARKER, 1)[1].strip() + "\n"
    if len(summary) > 256:
        sys.exit(f"summary too long: {len(summary)}")
    return summary, body


def request(method, path, data=None, content_type="application/json"):
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", token())
    req.add_header("User-Agent", USER_AGENT)
    if data is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def multipart(fields, files):
    boundary = uuid.uuid4().hex
    out = bytearray()
    for name, value in fields.items():
        out += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n".encode()
        out += value.encode("utf-8") + b"\r\n"
    for name, (filename, content, ctype) in files.items():
        out += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; filename=\"{filename}\"\r\n"
                f"Content-Type: {ctype}\r\n\r\n").encode()
        out += content + b"\r\n"
    out += f"--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


def create():
    summary, body = listing()
    data = {
        "slug": SLUG,
        "title": "Chunk Express",
        "description": summary,
        "body": body,
        "categories": ["optimization", "utility", "worldgen"],
        "additional_categories": [],
        "client_side": "optional",
        "server_side": "required",
        "license_id": "GPL-3.0-only",
        "project_type": "mod",
        "is_draft": True,
        "initial_versions": [],
        "issues_url": "https://github.com/dennissoftman/chunk-express/issues",
        "source_url": "https://github.com/dennissoftman/chunk-express",
        "wiki_url": None,
        "discord_url": None,
        "donation_urls": [],
    }
    payload, ctype = multipart({"data": json.dumps(data)}, {"icon": ("icon.png", ICON.read_bytes(), "image/png")})
    status, result = request("POST", "/project", payload, ctype)
    print("create:", status)
    if status >= 300:
        print(result)
        sys.exit(1)
    print("  id:", result.get("id"), "| slug:", result.get("slug"), "| status:", result.get("status"))


def update():
    summary, body = listing()
    status, result = request("PATCH", f"/project/{SLUG}", json.dumps({"description": summary, "body": body}).encode())
    print("update:", status, "" if status < 300 else result)


def gallery():
    """Upload the showcase GIFs to the gallery (replacing earlier uploads with the same titles)."""
    import urllib.parse
    for path, title, _, _ in SHOWCASE:
        if path.stat().st_size > 5 * 1024 * 1024:
            sys.exit(f"{path.name} is over 5 MiB; rebuild it narrower (art/make_showcase_gif.py --width)")
    status, p = request("GET", f"/project/{SLUG}")
    titles = {t for _, t, _, _ in SHOWCASE} | {"Vanilla vs Chunk Express"}
    for old in (p.get("gallery") or []) if status < 300 else []:
        if old.get("title") in titles:
            q = urllib.parse.urlencode({"url": old["url"]})
            print("remove earlier upload:", old.get("title"), request("DELETE", f"/project/{SLUG}/gallery?{q}")[0])
    for order, (path, title, text, featured) in enumerate(SHOWCASE):
        q = urllib.parse.urlencode({"ext": "gif", "featured": str(featured).lower(), "title": title,
                                    "description": text, "ordering": str(order)})
        status, result = request("POST", f"/project/{SLUG}/gallery?{q}", path.read_bytes(), "image/gif")
        print("gallery:", title, status, "" if status < 300 else result)
    status, p = request("GET", f"/project/{SLUG}")
    for item in sorted(p.get("gallery") or [], key=lambda g: g.get("ordering", 0)):
        print("url:", item.get("title"), item["url"])


def version():
    """Upload a jar as a new Beta version of the project. Uploading to a draft publishes nothing."""
    if len(sys.argv) != 4:
        sys.exit("usage: modrinth.py version <version> <jar>")
    number, jar = sys.argv[2], Path(sys.argv[3])
    sys.path.insert(0, str(ROOT / "scripts"))
    from changelog_section import section
    notes = section(number, (ROOT / "CHANGELOG.md").read_text(encoding="utf-8"))
    if not notes:
        sys.exit(f"CHANGELOG.md has no section for {number}")
    status, p = request("GET", f"/project/{SLUG}")
    if status >= 300:
        sys.exit(f"project lookup failed: {status} {p}")
    status, existing = request("GET", f"/project/{SLUG}/version")
    if status < 300 and any(v.get("version_number") == number for v in existing):
        sys.exit(f"version {number} already exists on Modrinth")
    data = {
        "project_id": p["id"],
        "name": f"Chunk Express {number}",
        "version_number": number,
        "changelog": notes,
        "dependencies": [],
        "game_versions": ["1.21.1"],
        "version_type": "beta",
        "loaders": ["neoforge"],
        "featured": True,
        "file_parts": ["jar"],
        "primary_file": "jar",
    }
    payload, ctype = multipart({"data": json.dumps(data)}, {"jar": (jar.name, jar.read_bytes(), "application/java-archive")})
    status, result = request("POST", "/version", payload, ctype)
    print("version:", status, "" if status < 300 else result)
    if status < 300:
        print("  id:", result.get("id"), "| files:", [f.get("filename") for f in result.get("files", [])])


def show():
    status, p = request("GET", f"/project/{SLUG}")
    if status >= 300:
        print("show:", status, p)
        sys.exit(1)
    print(f"title: {p['title']}\nslug: {p['slug']}\nstatus: {p['status']}\ncategories: {p['categories']}\n"
          f"client/server: {p['client_side']}/{p['server_side']}\nlicense: {p['license']['id']}\n"
          f"icon: {'set' if p.get('icon_url') else 'missing'}\nbody: {len(p['body'])} chars\n"
          f"summary: {p['description']}\n"
          f"gallery: {[g.get('title') for g in p.get('gallery') or []]}")


if __name__ == "__main__":
    {"create": create, "update": update, "gallery": gallery, "version": version, "show": show}[sys.argv[1] if len(sys.argv) > 1 else "show"]()
