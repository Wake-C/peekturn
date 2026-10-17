#!/usr/bin/env python3
"""Build Margin's offline dictionary from pinned, independently downloaded ECDICT.

Usage: python3 tools/build_dictionary.py [--source /path/to/ecdict.csv]
The optional local CSV must match the pinned upstream SHA-256 once recorded.
Only Python's standard library is required. No network is used by the Android app.
"""
import argparse
import csv
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import sqlite3
import tempfile
import urllib.request

REVISION = "bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b"
BASE = f"https://raw.githubusercontent.com/skywind3000/ECDICT/{REVISION}"
ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets/dict"


def normalize(text):
    return re.sub(r"\s+", " ", text.strip()).lower()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path)
    args = parser.parse_args()
    ASSETS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="margin-dictionary-") as temporary:
        work = Path(temporary)
        source = args.source or work / "ecdict.csv"
        if not args.source:
            with urllib.request.urlopen(f"{BASE}/ecdict.csv") as response, source.open("wb") as out:
                shutil.copyfileobj(response, out)
        source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
        provenance_path = ASSETS / "provenance.json"
        if provenance_path.exists():
            previous = json.loads(provenance_path.read_text())
            if previous["revision"] == REVISION and previous["source_sha256"] != source_hash:
                raise ValueError("CSV does not match the recorded pinned upstream source")
        database = work / "dictionary.sqlite"
        connection = sqlite3.connect(database)
        connection.execute("PRAGMA page_size=4096")
        connection.execute("PRAGMA user_version=1")
        connection.execute("CREATE TABLE entries (query TEXT PRIMARY KEY, word TEXT NOT NULL, phonetic TEXT NOT NULL, pos TEXT NOT NULL, definition TEXT NOT NULL, translation TEXT NOT NULL) WITHOUT ROWID")
        source_rows = 0
        with source.open(encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream)
            required = {"word", "phonetic", "pos", "definition", "translation"}
            if not required.issubset(reader.fieldnames or []):
                raise ValueError("Unexpected upstream CSV schema")
            for row in reader:
                source_rows += 1
                word = row["word"].strip()
                query = normalize(word)
                definition = row["definition"].replace("\\n", "\n").strip()
                translation = row["translation"].replace("\\n", "\n").strip()
                if not query or not any("a" <= c <= "z" for c in query) or not (definition or translation):
                    continue
                # First upstream spelling wins for normalized duplicates; no stemming or fuzzy keys.
                connection.execute("INSERT OR IGNORE INTO entries VALUES (?, ?, ?, ?, ?, ?)",
                                   (query, word, row["phonetic"].strip(), row["pos"].strip(), definition, translation))
        connection.commit()
        connection.execute("VACUUM")
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        count = connection.execute("SELECT count(*) FROM entries").fetchone()[0]
        if integrity != "ok" or count < 100000:
            raise ValueError(f"Invalid or insubstantial dictionary: {integrity}, {count} entries")
        connection.close()
        asset = ASSETS / "dictionary.dict"
        with database.open("rb") as source_db, asset.open("wb") as output:
            with gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0, compresslevel=9) as compressed:
                shutil.copyfileobj(source_db, compressed)
        with urllib.request.urlopen(f"{BASE}/LICENSE") as response:
            license_bytes = response.read()
        (ASSETS / "LICENSE-ECDICT.txt").write_bytes(license_bytes)
        provenance = {
            "project": "ECDICT", "upstream": "https://github.com/skywind3000/ECDICT",
            "revision": REVISION, "source_url": f"{BASE}/ecdict.csv", "license": "MIT",
            "license_url": f"{BASE}/LICENSE", "source_sha256": source_hash,
            "source_rows": source_rows, "entry_count": count, "schema_version": 1,
            "sqlite_sha256": hashlib.sha256(database.read_bytes()).hexdigest(),
            "asset_sha256": hashlib.sha256(asset.read_bytes()).hexdigest(),
            "sqlite_bytes": database.stat().st_size, "asset_bytes": asset.stat().st_size,
            "transformation": "Trim and collapse whitespace; lowercase exact keys; retain first normalized duplicate; retain entries containing an English letter and at least one definition/translation; unescape literal newline separators. Preserve upstream spelling, phonetic, POS, English definition and Chinese translation."
        }
        provenance_path.write_text(json.dumps(provenance, indent=2, ensure_ascii=False) + "\n")
        print(json.dumps(provenance, indent=2))


if __name__ == "__main__":
    main()
