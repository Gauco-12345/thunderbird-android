#!/usr/bin/env python3
"""Turn scripts/dcmail/changelog.json into the changelog resources the app shows after an update.

Usage: make_changelog.py <changelog.json> <raw-resource-dir> <build-number>

Writes changelog_index.json plus one changelog_dcmail_<build>.json per release into the given res/raw directory.
The entry with "build": "next" gets the current build number and today's date.
"""

import datetime
import json
import os
import sys

VERSION_CODE_OFFSET = 1000
VALID_TYPES = {"new", "fixed", "changed"}


def main() -> None:
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    source, raw_dir, build_arg = sys.argv[1], sys.argv[2], sys.argv[3]
    current_build = int(build_arg)
    today = datetime.date.today().isoformat()

    with open(source, encoding="utf-8") as f:
        data = json.load(f)

    os.makedirs(raw_dir, exist_ok=True)
    for name in os.listdir(raw_dir):
        if name.startswith("changelog_"):
            os.remove(os.path.join(raw_dir, name))

    index = {"schemaVersion": 1, "releases": []}
    for release in data["releases"]:
        build = current_build if release["build"] == "next" else int(release["build"])
        if build > current_build:
            continue
        date = release.get("date") or today
        notes = []
        for note in release["notes"]:
            note_type = note["type"] if note["type"] in VALID_TYPES else "changed"
            notes.append({"type": note_type, "text": note["text"], "source": "dcmail"})

        resource = f"changelog_dcmail_{build}"
        version = f"Build {build}"
        with open(os.path.join(raw_dir, resource + ".json"), "w", encoding="utf-8") as f:
            json.dump(
                {
                    "schemaVersion": 1,
                    "version": version,
                    "versioncode": VERSION_CODE_OFFSET + build,
                    "date": date,
                    "notes": notes,
                },
                f,
                ensure_ascii=False,
                indent=2,
            )
        index["releases"].append(
            {"version": version, "versioncode": VERSION_CODE_OFFSET + build, "date": date, "resourceName": resource},
        )

    with open(os.path.join(raw_dir, "changelog_index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=2)

    print(f"Changelog: {len(index['releases'])} releases written to {raw_dir}")


if __name__ == "__main__":
    main()
