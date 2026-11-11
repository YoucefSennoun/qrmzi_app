#!/usr/bin/env python3
"""Builds an installable APK from the compiled classes and the binary manifest.

No aapt/zipalign exists for this host (aarch64 Termux, x86_64 SDK binaries), so
the archive is written here: the manifest is already binary AXML, classes.dex is
stored uncompressed and 4-byte aligned (what ART mmaps), and apksigner adds the
v1/v2 signatures afterwards.

Usage: package_apk.py <manifest> <dex-dir> <output.apk> [extra.zip:entry=path]
"""
import os
import struct
import sys
import zipfile

# 4 bytes: dex/arsc are mmap'ed by ART, which wants aligned uncompressed data.
ALIGN = 4


def write_aligned(zf: zipfile.ZipFile, name: str, data: bytes) -> None:
    """Writes [name] uncompressed, padding the extra field for data alignment."""
    offset = zf.fp.tell()
    # A 4-byte padding extra-field header is always present, then `pad` bytes.
    pad = (-(offset + 30 + len(name.encode("utf-8")) + 4)) % ALIGN
    extra = struct.pack("<HH", 0xD935, pad) + b"\x00" * pad
    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_STORED
    info.external_attr = 0o644 << 16
    info.extra = extra
    zf.writestr(info, data)


def entry_offsets(path: str):
    """Returns {name: (data_offset, stored)} for verification."""
    out = {}
    with open(path, "rb") as fp:
        while True:
            sig = fp.read(4)
            if sig != b"PK\x03\x04":
                break
            fp.seek(2, os.SEEK_CUR)          # version
            flags, method = struct.unpack("<HH", fp.read(4))
            fp.seek(8, os.SEEK_CUR)          # time/date/crc
            csize, usize = struct.unpack("<II", fp.read(8))
            nlen, elen = struct.unpack("<HH", fp.read(4))
            name = fp.read(nlen).decode("utf-8", "replace")
            fp.seek(elen, os.SEEK_CUR)
            data_off = fp.tell()
            out[name] = (data_off, method == 0)
            fp.seek(csize, os.SEEK_CUR)
            if flags & 0x08:                 # data descriptor
                fp.seek(12, os.SEEK_CUR)
    return out


def main() -> int:
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    manifest, dex_dir, out = sys.argv[1], sys.argv[2], sys.argv[3]

    if not os.path.exists(manifest):
        print("missing manifest:", manifest)
        return 1

    dex_files = sorted(
        f for f in os.listdir(dex_dir)
        if f.startswith("classes") and f.endswith(".dex")
    )
    if not dex_files:
        print("no dex files in", dex_dir)
        return 1

    with zipfile.ZipFile(out, "w") as zf:
        # AndroidManifest.xml: deflate is fine, it is read whole.
        zf.write(manifest, "AndroidManifest.xml", zipfile.ZIP_DEFLATED)
        for dex in dex_files:
            with open(os.path.join(dex_dir, dex), "rb") as fp:
                write_aligned(zf, dex, fp.read())

    offsets = entry_offsets(out)
    problems = []
    for dex in dex_files:
        off, stored = offsets.get(dex, (0, False))
        if not stored:
            problems.append(dex + " is compressed")
        elif off % ALIGN:
            problems.append("%s unaligned (%d)" % (dex, off))
    if "AndroidManifest.xml" not in offsets:
        problems.append("AndroidManifest.xml missing")
    if problems:
        print("packaging problems:", "; ".join(problems))
        return 1

    size = os.path.getsize(out)
    print("wrote %s (%d bytes) with %s" % (out, size, ", ".join(dex_files)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
