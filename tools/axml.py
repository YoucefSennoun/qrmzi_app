#!/usr/bin/env python3
"""Minimal Android binary XML (AXML) writer and reader.

Written because no aapt/aapt2 binary exists for aarch64 in this environment.
The writer emits the same chunk layout aapt2 produces for AndroidManifest.xml:

    RES_XML_TYPE
      RES_STRING_POOL_TYPE        (UTF-8 pool, attribute names first)
      RES_XML_RESOURCE_MAP_TYPE   (attr name string index -> framework attr id)
      RES_XML_START_NAMESPACE_TYPE
      RES_XML_START_ELEMENT_TYPE
        ...
      RES_XML_END_ELEMENT_TYPE
      RES_XML_END_NAMESPACE_TYPE

Attributes inside an element are sorted ascending by resource id, which is
what androidfw's ResXMLTree::indexOfAttribute() expects (it binary-searches
using the resource map), so android:foo lookups by the framework keep working.

The reader is independent (a second implementation used to verify output and
to inspect real APKs).
"""
import struct
import sys

# ---------------------------------------------------------------- chunk types
RES_STRING_POOL = 0x0001
RES_XML = 0x0003
RES_XML_START_NAMESPACE = 0x0100
RES_XML_END_NAMESPACE = 0x0101
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
RES_XML_CDATA = 0x0104
RES_XML_RESOURCE_MAP = 0x0180

# ---------------------------------------------------------------- value types
TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12

NO_ENTRY = 0xFFFFFFFF

NS_ANDROID = 'http://schemas.android.com/apk/res/android'


# ------------------------------------------------------------------- values
class Val:
    def __init__(self, dtype, data, raw=None):
        self.dtype = dtype
        self.data = data
        self.raw = raw


def S(text):
    """String value; data is filled in with the pool index at serialise time."""
    v = Val(TYPE_STRING, text)
    v.text = text
    return v


def R(resid):
    return Val(TYPE_REFERENCE, resid)


def D(value):
    return Val(TYPE_INT_DEC, value)


def H(value):
    return Val(TYPE_INT_HEX, value)


def B(flag):
    return Val(TYPE_INT_BOOLEAN, 0xFFFFFFFF if flag else 0)


class Attr:
    def __init__(self, name, value, resid=None, ns=NS_ANDROID):
        self.name = name
        self.value = value
        self.resid = resid
        self.ns = ns


def A(name, resid, value):
    """android:<name> attribute with its framework resource id."""
    return Attr(name, value, resid)


class Node:
    def __init__(self, name, attrs=None, ns=None):
        self.name = name
        self.attrs = attrs or []
        self.ns = ns
        self.children = []

    def add(self, node):
        self.children.append(node)
        return node

    def el(self, name, attrs=None, ns=None):
        return self.add(Node(name, attrs, ns))


# ------------------------------------------------------------------ encoding
def _varint_len(n, out):
    if n > 0x7F:
        out.append(((n >> 8) & 0x7F) | 0x80)
        out.append(n & 0xFF)
    else:
        out.append(n & 0xFF)


def string_pool(strings):
    data = bytearray()
    offsets = []
    for s in strings:
        offsets.append(len(data))
        raw = s.encode('utf-8')
        _varint_len(len(s.encode('utf-16-le')) // 2, data)
        _varint_len(len(raw), data)
        data += raw
        data.append(0)
    while len(data) % 4:
        data.append(0)
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    body = struct.pack('<IIIII', len(strings), 0, 0x100, strings_start, 0)
    body += b''.join(struct.pack('<I', o) for o in offsets)
    body += bytes(data)
    return struct.pack('<HHI', RES_STRING_POOL, header_size, 8 + len(body)) + body


def _chunk(ctype, header_size, body):
    assert (header_size + len(body)) % 4 == 0, (ctype, header_size, len(body))
    return struct.pack('<HHI', ctype, header_size, header_size + len(body)) + body


def _node_header(ctype, body, line=1):
    # ResXMLTree_node: chunk header(8) + lineNumber(4) + comment(4)
    size = 16 + len(body)
    assert size % 4 == 0, (ctype, size)
    return struct.pack('<HHIII', ctype, 16, size, line, NO_ENTRY) + body


class AxmlWriter:
    def __init__(self, root):
        self.root = root
        self.attr_names = []       # [(name, resid)] ascending by resid
        self.attr_index = {}
        self.pool = []
        self.pool_index = {}
        self.resmap = []
        self._prepare()

    # ---- string pool ordering: attribute names first (required by resmap)
    def _prepare(self):
        used = {}
        def walk(n):
            for a in n.attrs:
                if a.resid is not None:
                    used[a.name] = a.resid
            for c in n.children:
                walk(c)
        walk(self.root)
        self.attr_names = sorted(used.items(), key=lambda kv: kv[1])
        for name, resid in self.attr_names:
            self._intern(name)
            self.resmap.append(resid)
        # remaining strings, in document order for determinism
        self._intern(NS_ANDROID)
        self._intern('android')
        def walk2(n):
            self._intern(n.name)
            for a in n.attrs:
                self._intern(a.name)      # e.g. non-namespaced "package"
                if isinstance(a.value, Val) and a.value.dtype == TYPE_STRING:
                    self._intern(a.value.text)
            for c in n.children:
                walk2(c)
        walk2(self.root)

    def _intern(self, s):
        if s in self.pool_index:
            return self.pool_index[s]
        i = len(self.pool)
        self.pool.append(s)
        self.pool_index[s] = i
        return i

    def _attr_shard(self, attr):
        name = self.pool_index[attr.name]
        ns = self.pool_index[attr.ns] if attr.ns else NO_ENTRY
        v = attr.value
        if v.dtype == TYPE_STRING:
            data = self.pool_index[v.text]
            raw = data
        else:
            data = v.data
            raw = NO_ENTRY
        return struct.pack('<IIIHBB I'.replace(' ', ''),
                           ns, name, raw, 8, 0, v.dtype, data)

    def _element(self, node, line=1):
        attrs = sorted(node.attrs, key=lambda a: (a.resid is None, a.resid or 0))
        body = struct.pack('<IIHHHHHH',
                           self.pool_index[node.ns] if node.ns else NO_ENTRY,
                           self.pool_index[node.name],
                           20, 20, len(attrs), 0, 0, 0)
        for a in attrs:
            body += self._attr_shard(a)
        out = _node_header(RES_XML_START_ELEMENT, body, line)
        for c in node.children:
            out += self._element(c, line + 1)
        end = struct.pack('<II',
                          self.pool_index[node.ns] if node.ns else NO_ENTRY,
                          self.pool_index[node.name])
        out += _node_header(RES_XML_END_ELEMENT, end, line + 1)
        return out

    def tobytes(self):
        ns_start = _node_header(RES_XML_START_NAMESPACE, struct.pack(
            '<II', self.pool_index['android'], self.pool_index[NS_ANDROID]))
        ns_end = _node_header(RES_XML_END_NAMESPACE, struct.pack(
            '<II', self.pool_index['android'], self.pool_index[NS_ANDROID]))
        map_body = b''.join(struct.pack('<I', r) for r in self.resmap)
        chunks = (string_pool(self.pool)
                  + _chunk(RES_XML_RESOURCE_MAP, 8, map_body)
                  + ns_start
                  + self._element(self.root)
                  + ns_end)
        return struct.pack('<HHI', RES_XML, 8, 8 + len(chunks)) + chunks


def write_axml(root, path):
    data = AxmlWriter(root).tobytes()
    with open(path, 'wb') as f:
        f.write(data)
    return data


# -------------------------------------------------------------------- reader
def u16(b, o):
    return struct.unpack_from('<H', b, o)[0]


def u32(b, o):
    return struct.unpack_from('<I', b, o)[0]


def read_pool(b, start):
    assert u16(b, start) == RES_STRING_POOL
    hsize = u16(b, start + 2)
    count = u32(b, start + 8)
    flags = u32(b, start + 16)
    strings_start = u32(b, start + 20)
    utf8 = bool(flags & 0x100)
    base = start + strings_start
    out = []
    for i in range(count):
        p = base + u32(b, start + hsize + 4 * i)
        if utf8:
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


class RawAttr:
    def __init__(self, ns, name, resid, dtype, data, raw):
        self.ns, self.name, self.resid = ns, name, resid
        self.dtype, self.data, self.raw = dtype, data, raw


class RawNode:
    def __init__(self, name, attrs):
        self.name = name
        self.attrs = attrs
        self.children = []

    def __repr__(self):
        return '<%s %s>' % (self.name, self.attrs)


def read_axml(b):
    assert u16(b, 0) == RES_XML, hex(u16(b, 0))
    total = u32(b, 4)
    pos = u16(b, 2)
    pool = None
    resmap = []
    stack = []
    root = None
    while pos + 8 <= total:
        ctype = u16(b, pos)
        hsize = u16(b, pos + 2)
        csize = u32(b, pos + 4)
        if csize < 8:
            break
        if ctype == RES_STRING_POOL:
            pool = read_pool(b, pos)
        elif ctype == RES_XML_RESOURCE_MAP:
            n = (csize - hsize) // 4
            resmap = [u32(b, pos + hsize + 4 * i) for i in range(n)]
        elif ctype == RES_XML_START_ELEMENT:
            if pool is None:
                raise ValueError('element before string pool')
            ns_i = u32(b, pos + 16)
            name_i = u32(b, pos + 20)
            attr_start = u16(b, pos + 24)
            attr_size = u16(b, pos + 26)
            attr_count = u16(b, pos + 28)
            attrs = []
            for i in range(attr_count):
                p = pos + 16 + attr_start + i * attr_size
                ans = u32(b, p)
                aname = u32(b, p + 4)
                raw = u32(b, p + 8)
                dtype = b[p + 15]
                data = u32(b, p + 16)
                resid = resmap[aname] if aname < len(resmap) else 0
                attrs.append(RawAttr(
                    pool[ans] if ans != NO_ENTRY else None,
                    pool[aname],
                    resid,
                    dtype,
                    pool[data] if dtype == TYPE_STRING and data < len(pool) else data,
                    pool[raw] if raw != NO_ENTRY and raw < len(pool) else None,
                ))
            node = RawNode(pool[name_i], attrs)
            if stack:
                stack[-1].children.append(node)
            else:
                root = node
            stack.append(node)
        elif ctype == RES_XML_END_ELEMENT:
            if stack:
                stack.pop()
        pos += csize
    return root


def dump(root, indent=0):
    pad = '  ' * indent
    print('%s<%s' % (pad, root.name))
    for a in root.attrs:
        pretty = '%r' % (a.data,) if a.dtype == TYPE_STRING else {
            TYPE_REFERENCE: 'ref', TYPE_INT_DEC: 'dec',
            TYPE_INT_HEX: 'hex', TYPE_INT_BOOLEAN: 'bool'}.get(a.dtype, str(a.dtype))
        if a.dtype == TYPE_INT_BOOLEAN:
            pretty = 'true' if a.data else 'false'
        elif a.dtype == TYPE_REFERENCE:
            pretty = '@0x%08x' % a.data
        elif a.dtype in (TYPE_INT_DEC, TYPE_INT_HEX):
            pretty = str(a.data)
        print('%s  %-28s %-8s %s' % (pad, a.name, pretty,
                                     ('#0x%08x' % a.resid) if a.resid else ''))
    for c in root.children:
        dump(c, indent + 1)


if __name__ == '__main__':
    if sys.argv[1] == '-p':          # pretty print an existing binary xml
        with open(sys.argv[2], 'rb') as f:
            dump(read_axml(f.read()))
    else:
        print('usage: axml.py -p file.xml', file=sys.stderr)
        sys.exit(2)
