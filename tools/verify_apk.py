#!/usr/bin/env python3
"""Static verification of the built APK.

Checks what can be checked without a device:
  * zip layout (manifest present, dex stored + 4-byte aligned)
  * binary AndroidManifest.xml re-parsed by the independent reader
  * every dex header valid, class list complete (compare with the classes jar)
  * key Kotlin/Android classes actually present in the dex

Usage: verify_apk.py <apk> [classes.jar]
"""
import os
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import axml

ATTR_NAME = 0x01010003
ATTR_LABEL = 0x01010001
ATTR_THEME = 0x01010000
ATTR_EXPORTED = 0x01010010
ATTR_REQUIRED = 0x0101028E
ATTR_USES_CLEARTEXT = 0x010104EC
ATTR_MIN_SDK = 0x0101020C
ATTR_TARGET_SDK = 0x01010270

FAILS = []


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + ("  (" + str(detail) + ")" if detail else ""))
    if not ok:
        FAILS.append(name)


def attr(node, resid):
    for a in node.attrs:
        if a.resid == resid:
            return a
    return None


def truthy(a):
    return a is not None and a.data == 0xFFFFFFFF


def children(node, name):
    return [c for c in node.children if c.name == name]


# ---------------------------------------------------------------------- zip
def check_zip(path):
    print("== zip layout ==")
    with zipfile.ZipFile(path) as zf:
        names = zf.namelist()
        bad = zf.testzip()
        check("zip CRCs valid", bad is None, bad or "all entries")
        check("AndroidManifest.xml present", "AndroidManifest.xml" in names)
        dexes = sorted(n for n in names if n.startswith("classes") and n.endswith(".dex"))
        check("classes.dex present", len(dexes) >= 1, ", ".join(dexes))
        # apksigner writes the v1 (JAR) signature into META-INF; v2/v3 live in
        # the signing block, so those entries are expected here.
        check("v1 signature entries present",
              "META-INF/MANIFEST.MF" in names and
              any(n.endswith(".SF") for n in names) and
              any(n.endswith(".RSA") or n.endswith(".EC") for n in names),
              [n for n in names if n.startswith("META-INF/")])
        check("no resources.arsc (resource-less build)", "resources.arsc" not in names)

        # data offsets/alignment of the dex payloads
        offsets = {}
        with open(path, "rb") as fp:
            while True:
                if fp.read(4) != b"PK\x03\x04":
                    break
                fp.seek(2, os.SEEK_CUR)
                flags, method = struct.unpack("<HH", fp.read(4))
                fp.seek(8, os.SEEK_CUR)
                csize, usize = struct.unpack("<II", fp.read(8))
                nlen, elen = struct.unpack("<HH", fp.read(4))
                name = fp.read(nlen).decode("utf-8", "replace")
                fp.seek(elen, os.SEEK_CUR)
                offsets[name] = (fp.tell(), method, usize)
                fp.seek(csize, os.SEEK_CUR)
                if flags & 0x08:
                    fp.seek(12, os.SEEK_CUR)
        for dex in dexes:
            off, method, size = offsets.get(dex, (0, 1, 0))
            check(dex + " stored", method == 0, "method=%d" % method)
            check(dex + " 4-byte aligned", off % 4 == 0, "offset=%d" % off)
            check(dex + " non-trivial size", size > 100_000, "%d bytes" % size)

    return dexes


# ------------------------------------------------------------------ manifest
def check_manifest(path):
    print("== AndroidManifest.xml ==")
    with zipfile.ZipFile(path) as zf:
        data = zf.read("AndroidManifest.xml")
    root = axml.read_axml(data)
    check("root element is <manifest>", root.name == "manifest", root.name)
    pkg = [a for a in root.attrs if (a.resid in (None, 0)) and a.name == "package"]
    pkg = pkg[0].data if pkg and isinstance(pkg[0].data, str) else None
    check("package tv.qrmzi.client", pkg == "tv.qrmzi.client", pkg)

    def fqcn(name):
        """Resolves the relative activity names the manifest generator emits."""
        if not isinstance(name, str):
            return name
        if name.startswith("."):
            return (pkg or "") + name
        if "." not in name:
            return (pkg or "") + "." + name
        return name

    version = {a.name: a.data for a in root.attrs}
    check("versionCode 2", version.get("versionCode") == 2, version.get("versionCode"))
    check("versionName 1.1", version.get("versionName") == "1.1", version.get("versionName"))

    sdk = children(root, "uses-sdk")
    check("one uses-sdk", len(sdk) == 1)
    if sdk:
        check("minSdkVersion 29", attr(sdk[0], ATTR_MIN_SDK).data == 29)
        check("targetSdkVersion 29", attr(sdk[0], ATTR_TARGET_SDK).data == 29)

    perms = {attr(p, ATTR_NAME).data for p in children(root, "uses-permission")}
    check("INTERNET permission", "android.permission.INTERNET" in perms, sorted(perms))
    check("ACCESS_NETWORK_STATE permission", "android.permission.ACCESS_NETWORK_STATE" in perms)

    features = {}
    for f in children(root, "uses-feature"):
        name = attr(f, ATTR_NAME).data
        features[name] = truthy(attr(f, ATTR_REQUIRED))
    check("leanback feature required", features.get("android.software.leanback") is True, features)
    check("touchscreen not required", features.get("android.hardware.touchscreen") is False)

    apps = children(root, "application")
    check("one application", len(apps) == 1)
    app = apps[0]
    check("application class is .App", attr(app, ATTR_NAME).data == "tv.qrmzi.client.App",
          attr(app, ATTR_NAME).data)
    check("label is Arabic string", attr(app, ATTR_LABEL).data == "قرمزي TV",
          attr(app, ATTR_LABEL).data)
    check("theme is framework NoActionBar",
          attr(app, ATTR_THEME).data == 0x0103022E,
          hex(attr(app, ATTR_THEME).data))
    check("usesCleartextTraffic = false", truthy(attr(app, ATTR_USES_CLEARTEXT)) is False)
    check("allowBackup = false",
          next((a.data for a in app.attrs if a.name == "allowBackup"), None) == 0)

    acts = {fqcn(attr(a, ATTR_NAME).data): a for a in children(app, "activity")}
    check("MainActivity declared", "tv.qrmzi.client.MainActivity" in acts, sorted(acts))
    check("PlayerActivity declared", "tv.qrmzi.client.player.PlayerActivity" in acts)
    if "tv.qrmzi.client.MainActivity" in acts:
        main = acts["tv.qrmzi.client.MainActivity"]
        check("MainActivity exported", truthy(attr(main, ATTR_EXPORTED)))
        cats = set()
        for f in children(main, "intent-filter"):
            for c in children(f, "category"):
                cats.add(attr(c, ATTR_NAME).data)
        check("launcher intents (leanback + launcher)",
              "android.intent.category.LEANBACK_LAUNCHER" in cats and
              "android.intent.category.LAUNCHER" in cats, sorted(cats))
    if "tv.qrmzi.client.player.PlayerActivity" in acts:
        player = acts["tv.qrmzi.client.player.PlayerActivity"]
        check("PlayerActivity not exported", truthy(attr(player, ATTR_EXPORTED)) is False)
        orientation = next((a.data for a in player.attrs if a.name == "screenOrientation"), None)
        check("PlayerActivity landscape", orientation == 0, orientation)


# ----------------------------------------------------------------------- dex
def dex_strings(data):
    sids_size, sids_off = struct.unpack_from("<II", data, 56)
    strings = []
    for i in range(sids_size):
        off = struct.unpack_from("<I", data, sids_off + 4 * i)[0]
        p = off
        while data[p] & 0x80:      # uleb128 utf16 length
            p += 1
        p += 1
        end = data.index(b"\x00", p)
        strings.append(data[p:end].decode("utf-8", "replace"))
    return strings


def dex_classes(data):
    """Class descriptors (Lfoo/Bar;) in definition order."""
    strings = dex_strings(data)
    tids_size, tids_off = struct.unpack_from("<II", data, 64)
    cdefs_size, cdefs_off = struct.unpack_from("<II", data, 96)
    out = []
    for i in range(cdefs_size):
        (class_idx,) = struct.unpack_from("<I", data, cdefs_off + 32 * i)
        if class_idx >= tids_size:
            raise ValueError("class_idx %d out of range (%d type ids)" % (class_idx, tids_size))
        (string_idx,) = struct.unpack_from("<I", data, tids_off + 4 * class_idx)
        out.append(strings[string_idx])
    return out


def check_dex(path, dexes, jar):
    print("== dex ==")
    all_classes = set()
    all_strings = set()
    for name in dexes:
        with zipfile.ZipFile(path) as zf:
            data = zf.read(name)
        strings = dex_strings(data)
        size = struct.unpack_from("<I", data, 32)[0]
        header = struct.unpack_from("<I", data, 36)[0]
        endian = struct.unpack_from("<I", data, 40)[0]
        check(name + " magic", data[:4] == b"dex\n", data[:7])
        check(name + " checksum valid", zlib_adler(data) == struct.unpack_from("<I", data, 8)[0])
        check(name + " file_size matches", size == len(data), "%d vs %d" % (size, len(data)))
        check(name + " header_size 0x70", header == 0x70, hex(header))
        check(name + " little endian", endian == 0x12345678, hex(endian))
        classes = dex_classes(data)
        check(name + " has classes", len(classes) > 20, "%d classes" % len(classes))
        all_classes.update(classes)
        all_strings.update(strings)

    for required in (
        "Ltv/qrmzi/client/MainActivity;",
        "Ltv/qrmzi/client/App;",
        "Ltv/qrmzi/client/player/PlayerActivity;",
        "Ltv/qrmzi/client/ui/screens/HomeScreen;",
        "Ltv/qrmzi/client/ui/screens/SettingsScreen;",
        "Ltv/qrmzi/client/ui/ThumbView;",
        "Ltv/qrmzi/client/data/Repo;",
        "Ltv/qrmzi/client/data/Store;",
        "Ltv/qrmzi/client/data/Parsers;",
        "Lkotlinx/coroutines/BuildersKt;",
        "Lorg/jsoup/Jsoup;",
    ):
        check("dex contains " + required, required in all_classes)

    if jar:
        with zipfile.ZipFile(jar) as zj:
            jar_classes = {
                "L" + n[:-6] + ";"
                for n in zj.namelist()
                if n.endswith(".class") and not n.startswith("META-INF")
            }
        missing = sorted(jar_classes - all_classes)
        check("every compiled class is in the dex", not missing,
              "%d/%d present%s" % (len(jar_classes) - len(missing), len(jar_classes),
                                   "" if not missing else ", missing: " + ", ".join(missing[:5])))


def zlib_adler(data):
    import zlib
    return zlib.adler32(data[12:]) & 0xFFFFFFFF


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    apk = sys.argv[1]
    jar = sys.argv[2] if len(sys.argv) > 2 else None
    check_zip(apk)
    check_manifest(apk)
    check_dex(apk, sorted(n for n in zipfile.ZipFile(apk).namelist()
                          if n.startswith("classes") and n.endswith(".dex")), jar)
    print()
    if FAILS:
        print("FAILED: %d check(s): %s" % (len(FAILS), ", ".join(FAILS)))
        return 1
    print("all static checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
