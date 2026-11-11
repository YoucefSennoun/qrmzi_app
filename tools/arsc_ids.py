#!/usr/bin/env python3
"""Minimal ARSC reader: resolves framework resource IDs by (type, name).

Used only to obtain the public resource IDs of android: attributes and
framework styles so a binary AndroidManifest.xml can be generated without
aapt.  Read-only, local tooling.
"""
import struct
import sys
import json

RES_STRING_POOL = 0x0001
RES_TABLE = 0x0002
RES_TABLE_PACKAGE = 0x0200
RES_TABLE_TYPE = 0x0201
RES_TABLE_TYPE_SPEC = 0x0202

FLAG_SPARSE = 0x01
FLAG_OFFSET16 = 0x02

NO_ENTRY = 0xFFFFFFFF


def u16(b, o):
    return struct.unpack_from('<H', b, o)[0]


def u32(b, o):
    return struct.unpack_from('<I', b, o)[0]


def parse_string_pool(b, start):
    """Returns list[str]."""
    assert u16(b, start) == RES_STRING_POOL, hex(u16(b, start))
    hsize = u16(b, start + 2)
    count = u32(b, start + 8)
    flags = u32(b, start + 16)
    strings_start = u32(b, start + 20)
    utf8 = bool(flags & 0x100)
    offsets = [u32(b, start + hsize + 4 * i) for i in range(count)]
    base = start + strings_start
    out = []
    for off in offsets:
        p = base + off
        if utf8:
            # utf16 length (varint), then utf8 byte length (varint)
            n = b[p]
            p += 2 if n & 0x80 else 1
            n = b[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | b[p + 1]
                p += 2
            else:
                p += 1
            out.append(b[p:p + n].decode('utf-8', 'replace'))
        else:
            n = u16(b, p)
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | u16(b, p)
                p += 2
            out.append(b[p:p + 2 * n].decode('utf-16-le', 'replace'))
    return out


def type_entry_offsets(b, p, entry_count):
    """Yield (entry_index, byte_offset_within_entries) for one ResTable_type."""
    hsize = u16(b, p + 2)
    flags = b[p + 9]
    base = p + hsize
    if flags & FLAG_SPARSE:
        for i in range(entry_count):
            q = base + 4 * i
            if q + 4 > len(b):
                return
            idx = u16(b, q)
            off = u16(b, q + 2) * 4
            yield idx, off
    elif flags & FLAG_OFFSET16:
        for i in range(entry_count):
            q = base + 2 * i
            if q + 2 > len(b):
                return
            off = u16(b, q)
            if off == 0xFFFF:
                continue
            yield i, off * 4
    else:
        for i in range(entry_count):
            q = base + 4 * i
            if q + 4 > len(b):
                return
            off = u32(b, q)
            if off == NO_ENTRY:
                continue
            yield i, off


def load(path):
    b = open(path, 'rb').read()
    assert u16(b, 0) == RES_TABLE
    hsize = u16(b, 2)
    total = u32(b, 4)
    pos = hsize
    # Global value string pool (not needed, skip)
    pos += u32(b, pos + 4)
    resources = {}   # (type_name, entry_name) -> res_id
    while pos + 8 <= total:
        ctype = u16(b, pos)
        csize = u32(b, pos + 4)
        if csize < 8:
            break
        if ctype == RES_TABLE_PACKAGE:
            pkg_id = u32(b, pos + 8)
            type_strings_off = u32(b, pos + 268)
            key_strings_off = u32(b, pos + 276)
            type_names = parse_string_pool(b, pos + type_strings_off)
            key_names = parse_string_pool(b, pos + key_strings_off)
            p = pos + u16(b, pos + 2)
            end = pos + csize
            seen = set()
            while p + 8 <= end:
                t = u16(b, p)
                tsize = u32(b, p + 4)
                if tsize < 8:
                    break
                if t == RES_TABLE_TYPE:
                    type_id = b[p + 8]
                    entry_count = u32(b, p + 12)
                    entries_start = u32(b, p + 16)
                    if type_id not in seen and 0 < type_id <= len(type_names):
                        seen.add(type_id)
                        tname = type_names[type_id - 1]
                        for i, off in type_entry_offsets(b, p, entry_count):
                            e = p + entries_start + off
                            if e + 8 > len(b):
                                continue
                            key = u32(b, e + 4)
                            if key < len(key_names):
                                rid = (pkg_id << 24) | (type_id << 16) | i
                                resources.setdefault((tname, key_names[key]), rid)
                p += tsize
        pos += csize
    return resources


if __name__ == '__main__':
    res = load(sys.argv[1])
    if len(sys.argv) == 2:
        wanted = ['attr', 'style']
        out = {}
        for (t, n), i in res.items():
            if t in wanted:
                out.setdefault(t, {})[n] = hex(i)
        json.dump(out, sys.stdout, ensure_ascii=False, indent=0)
    else:
        for key in sys.argv[2:]:
            t, n = key.split('/', 1)
            print('%-9s %-40s %s' % (t, n, hex(res[(t, n)]) if (t, n) in res else 'MISSING'))
