#!/usr/bin/env python3
"""Generate ks_tables.h without compiling the upstream makekeys host helper."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

KTNUM = 4000
XK_VOID_SYMBOL = 0xFFFFFF
MIN_REHASH = 15
MATCHES = 10

DEFINE_HEX_RE = re.compile(r"#define\s+(\S+)\s+0x([0-9A-Fa-f]+)\b")
DEFINE_EVDEV_RE = re.compile(r"#define\s+(\S+)\s+_EVDEVK\(0x([0-9A-Fa-f]+)\)")
DEFINE_ALIAS_RE = re.compile(r"#define\s+(\S+)\s+(\S+)")


@dataclass
class KeysymInfo:
    name: str
    value: int


def split_xk_symbol(symbol: str) -> tuple[str, str] | None:
    marker = symbol.find("XK_")
    if marker < 0:
        return None
    return symbol[:marker], symbol[marker + 3 :]


def parse_line(line: str, entries: list[KeysymInfo]) -> KeysymInfo | None:
    match = DEFINE_HEX_RE.match(line)
    if match:
        split = split_xk_symbol(match.group(1))
        if split is None:
            return None
        prefix, key = split
        return KeysymInfo(f"{prefix}{key}", int(match.group(2), 16))

    match = DEFINE_EVDEV_RE.match(line)
    if match:
        split = split_xk_symbol(match.group(1))
        if split is None:
            return None
        prefix, key = split
        return KeysymInfo(f"{prefix}{key}", int(match.group(2), 16) + 0x10081000)

    match = DEFINE_ALIAS_RE.match(line)
    if not match:
        return None

    key_split = split_xk_symbol(match.group(1))
    alias_split = split_xk_symbol(match.group(2))
    if key_split is None or alias_split is None:
        return None

    prefix, key = key_split
    alias_name = f"{alias_split[0]}{alias_split[1]}"
    for info in reversed(entries):
        if info.name == alias_name:
            return KeysymInfo(f"{prefix}{key}", info.value)

    print(
        f"can't find matching definition {alias_name} for keysym {prefix}{key}",
        file=sys.stderr,
    )
    return None


def load_keysyms(paths: list[Path]) -> list[KeysymInfo]:
    entries: list[KeysymInfo] = []
    for path in paths:
        try:
            lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
        except OSError:
            print(f"couldn't open {path.name}", file=sys.stderr)
            continue

        for line in lines:
            info = parse_line(line, entries)
            if info is None:
                continue
            value = 0 if info.value == XK_VOID_SYMBOL else info.value
            if value > 0x1FFFFFFF:
                print(
                    f"ignoring illegal keysym ({info.name}, {value:x})",
                    file=sys.stderr,
                )
                continue
            entries.append(KeysymInfo(info.name, value))
            if len(entries) == KTNUM:
                raise SystemExit("makekeys: too many keysyms!")
    return entries


def calc_signature(name: str) -> int:
    sig = 0
    for char in name:
        sig = ((sig << 1) + ord(char)) & 0xFFFFFFFF
    return sig


def find_best_string_hash(entries: list[KeysymInfo]) -> tuple[int, int]:
    ksnum = len(entries)
    best_max_rehash = ksnum
    best_z = 0
    num_found = 0

    for z in range(ksnum, KTNUM):
        tab = [0] * z
        max_rehash = 0
        collision = False
        for info in entries:
            sig = calc_signature(info.name)
            first = j = sig % z
            k = 0
            while tab[j]:
                j += first + 1
                if j >= z:
                    j -= z
                if j == first:
                    collision = True
                    break
                k += 1
            if collision:
                break
            tab[j] = 1
            if k > max_rehash:
                max_rehash = k
        if collision:
            continue
        if max_rehash < MIN_REHASH:
            if max_rehash < best_max_rehash:
                best_max_rehash = max_rehash
                best_z = z
            num_found += 1
            if num_found >= MATCHES:
                break

    if best_z == 0:
        raise SystemExit(
            "makekeys: failed to find small enough hash!\nTry increasing KTNUM in makekeys.c"
        )
    return best_z, best_max_rehash


def find_best_value_hash(entries: list[KeysymInfo]) -> tuple[int, int]:
    ksnum = len(entries)
    best_max_rehash = ksnum
    best_z = 0
    num_found = 0

    for z in range(ksnum, KTNUM):
        tab = [0] * z
        values = [0] * z
        max_rehash = 0
        collision = False
        for info in entries:
            value = info.value
            first = j = value % z
            k = 0
            while tab[j]:
                if values[j] == value:
                    break
                j += first + 1
                if j >= z:
                    j -= z
                if j == first:
                    collision = True
                    break
                k += 1
            if collision:
                break
            if not tab[j]:
                tab[j] = 1
                values[j] = value
                if k > max_rehash:
                    max_rehash = k
        if collision:
            continue
        if max_rehash < MIN_REHASH:
            if max_rehash < best_max_rehash:
                best_max_rehash = max_rehash
                best_z = z
            num_found += 1
            if num_found >= MATCHES:
                break

    if best_z == 0:
        raise SystemExit(
            "makekeys: failed to find small enough hash!\nTry increasing KTNUM in makekeys.c"
        )
    return best_z, best_max_rehash


def format_offsets(offsets: list[int]) -> str:
    parts: list[str] = []
    for index, offset in enumerate(offsets, start=1):
        parts.append(f"0x{offset:04x}")
        if index == len(offsets):
            break
        parts.append(",\n" if index % 8 == 0 else ", ")
    return "".join(parts)


def generate_output(entries: list[KeysymInfo]) -> str:
    z, best_max_rehash = find_best_string_hash(entries)
    offsets = [0] * z
    indexes = [0] * len(entries)

    lines = [
        "/* This file is generated from keysymdef.h. */",
        "/* Do not edit. */",
        "",
        "#ifdef NEEDKTABLE",
        "const unsigned char _XkeyTable[] = {",
        "0,",
    ]

    k = 1
    for index, info in enumerate(entries):
        sig = calc_signature(info.name)
        first = j = sig % z
        while offsets[j]:
            j += first + 1
            if j >= z:
                j -= z
        offsets[j] = k
        indexes[index] = k
        value = info.value
        prefix = (
            f"0x{(sig >> 8) & 0xFF:02x}, 0x{sig & 0xFF:02x}, "
            f"0x{(value >> 24) & 0xFF:02x}, 0x{(value >> 16) & 0xFF:02x}, "
            f"0x{(value >> 8) & 0xFF:02x}, 0x{value & 0xFF:02x}, "
        )
        chars = "".join(f"'{char}'," for char in info.name)
        terminator = "0" if index == len(entries) - 1 else "0,"
        lines.append(prefix + chars + terminator)
        k += len(info.name) + 7

    lines.extend(
        [
            "};",
            "",
            f"#define KTABLESIZE {z}",
            f"#define KMAXHASH {best_max_rehash + 1}",
            "",
            "static const unsigned short hashString[KTABLESIZE] = {",
            format_offsets(offsets),
            "};",
            "#endif /* NEEDKTABLE */",
        ]
    )

    z, best_max_rehash = find_best_value_hash(entries)
    value_offsets = [0] * z
    value_slots = [0] * z
    for index, info in enumerate(entries):
        value = info.value
        first = j = value % z
        while value_offsets[j]:
            if value_slots[j] == value:
                break
            j += first + 1
            if j >= z:
                j -= z
        if value_offsets[j]:
            continue
        value_offsets[j] = indexes[index] + 2
        value_slots[j] = value

    lines.extend(
        [
            "",
            "#ifdef NEEDVTABLE",
            f"#define VTABLESIZE {z}",
            f"#define VMAXHASH {best_max_rehash + 1}",
            "",
            "static const unsigned short hashKeysym[VTABLESIZE] = {",
            format_offsets(value_offsets),
            "};",
            "#endif /* NEEDVTABLE */",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("headers", nargs="+")
    parser.add_argument("--cwd", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    paths = [args.cwd / header for header in args.headers]
    output = generate_output(load_keysyms(paths))
    args.output.write_text(output, encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
