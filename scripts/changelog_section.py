"""Print the CHANGELOG.md section for one version (the release notes), or fail if there is none.

    python scripts/changelog_section.py 0.1.0

The section starts at the heading "## <version>" (anything may follow on that line, e.g. a date)
and ends before the next "## " heading. The heading itself is not printed.
"""
import sys
from pathlib import Path

CHANGELOG = Path(__file__).resolve().parent.parent / "CHANGELOG.md"


def section(version, text):
    lines, out, inside = text.splitlines(), [], False
    for line in lines:
        if line.startswith("## "):
            if inside:
                break
            inside = line[3:].strip().split()[0] == version if line[3:].strip() else False
            continue
        if inside:
            out.append(line)
    if not inside and not out:
        return None
    return "\n".join(out).strip() + "\n"


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: changelog_section.py <version>")
    notes = section(sys.argv[1], CHANGELOG.read_text(encoding="utf-8"))
    if not notes or not notes.strip():
        sys.exit(f"CHANGELOG.md has no section for {sys.argv[1]}")
    sys.stdout.write(notes)


if __name__ == "__main__":
    main()
