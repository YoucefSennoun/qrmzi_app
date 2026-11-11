#!/usr/bin/env python3
"""Generates the binary AndroidManifest.xml for the Qrmzi TV client.

Framework resource ids come from the device's own framework-res.apk (see
tools/arsc_ids.py); public framework ids are stable across API levels, so the
values resolved from an Android 14 table are valid for Android 10 (API 29).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import axml
from axml import A, S, R, D, H, B, Node, NS_ANDROID

# ------------------------------------------------- framework ids (API stable)
ATTR_NAME = 0x01010003
ATTR_LABEL = 0x01010001
ATTR_ICON = 0x01010002
ATTR_BANNER = 0x010103F2
ATTR_THEME = 0x01010000
ATTR_EXPORTED = 0x01010010
ATTR_LAUNCH_MODE = 0x0101001D
ATTR_SCREEN_ORIENTATION = 0x0101001E
ATTR_CONFIG_CHANGES = 0x0101001F
ATTR_HARDWARE_ACCELERATED = 0x010102D3
ATTR_USES_CLEARTEXT = 0x010104EC
ATTR_ALLOW_BACKUP = 0x01010280
ATTR_SUPPORTS_RTL = 0x010103AF
ATTR_VERSION_CODE = 0x0101021B
ATTR_VERSION_NAME = 0x0101021C
ATTR_MIN_SDK = 0x0101020C
ATTR_TARGET_SDK = 0x01010270
ATTR_COMPILE_SDK = 0x01010572
ATTR_COMPILE_SDK_CODENAME = 0x01010573
ATTR_DEBUGGABLE = 0x0101000F
ATTR_ENABLED = 0x0101000E
ATTR_REQUIRED = 0x0101028E
ATTR_RESIZEABLE = 0x010104F6
ATTR_LARGE_HEAP = 0x0101035A
ATTR_APP_CATEGORY = 0x01010545
ATTR_IS_GAME = 0x010103F4
ATTR_ALLOW_CLEAR_USER_DATA = 0x01010005
ATTR_HAS_CODE = 0x0101000C

# framework styles
STYLE_NO_ACTION_BAR = 0x0103022E          # Theme.Material.NoActionBar
STYLE_NO_ACTION_BAR_FULLSCREEN = 0x0103022F  # Theme.Material.NoActionBar.Fullscreen

PACKAGE = 'tv.qrmzi.client'
APP_LABEL = 'قرمزي TV'

# ActivityInfo.CONFIG_* : mcc|mnc|locale|keyboard|keyboardHidden|navigation|
# orientation|screenLayout|uiMode|screenSize|smallestScreenSize
CONFIG_CHANGES = 0x0FF0  # keyboard|keyboardHidden|navigation|orientation|screenLayout|uiMode|screenSize|smallestScreenSize


def build(debuggable=False, label=APP_LABEL, icon=None, banner=None):
    manifest = Node('manifest', [
        A('versionCode', ATTR_VERSION_CODE, D(2)),
        A('versionName', ATTR_VERSION_NAME, S('1.1')),
        A('compileSdkVersion', ATTR_COMPILE_SDK, D(34)),
        A('compileSdkVersionCodename', ATTR_COMPILE_SDK_CODENAME, S('REL')),
        axml.Attr('package', S(PACKAGE), ns=None),
    ])

    manifest.el('uses-sdk', [
        A('minSdkVersion', ATTR_MIN_SDK, D(29)),
        A('targetSdkVersion', ATTR_TARGET_SDK, D(29)),
    ])
    for perm in ('android.permission.INTERNET',
                 'android.permission.ACCESS_NETWORK_STATE'):
        manifest.el('uses-permission', [
            A('name', ATTR_NAME, S(perm)),
        ])
    manifest.el('uses-feature', [
        A('name', ATTR_NAME, S('android.software.leanback')),
        A('required', ATTR_REQUIRED, B(True)),
    ])
    manifest.el('uses-feature', [
        A('name', ATTR_NAME, S('android.hardware.touchscreen')),
        A('required', ATTR_REQUIRED, B(False)),
    ])

    app_attrs = [
        A('name', ATTR_NAME, S(PACKAGE + '.App')),
        A('theme', ATTR_THEME, R(STYLE_NO_ACTION_BAR)),
        A('label', ATTR_LABEL, icon and R(icon[0]) or S(label)),
        A('allowBackup', ATTR_ALLOW_BACKUP, B(False)),
        A('allowClearUserData', ATTR_ALLOW_CLEAR_USER_DATA, B(True)),
        A('supportsRtl', ATTR_SUPPORTS_RTL, B(True)),
        A('hardwareAccelerated', ATTR_HARDWARE_ACCELERATED, B(True)),
        A('usesCleartextTraffic', ATTR_USES_CLEARTEXT, B(False)),
        A('largeHeap', ATTR_LARGE_HEAP, B(False)),
        A('resizeableActivity', ATTR_RESIZEABLE, B(True)),
        A('appCategory', ATTR_APP_CATEGORY, D(2)),   # CATEGORY_VIDEO
        A('isGame', ATTR_IS_GAME, B(False)),
        A('debuggable', ATTR_DEBUGGABLE, B(debuggable)),
    ]
    if icon:
        app_attrs.append(A('icon', ATTR_ICON, R(icon[0])))
    if banner:
        app_attrs.append(A('banner', ATTR_BANNER, R(banner[0])))
    app = manifest.el('application', app_attrs)

    def activity(name, exported, theme=None, filter_actions=()):
        attrs = [
            A('name', ATTR_NAME, S(name)),
            A('exported', ATTR_EXPORTED, B(exported)),
            A('enabled', ATTR_ENABLED, B(True)),
            A('screenOrientation', ATTR_SCREEN_ORIENTATION, D(0)),  # landscape
            A('configChanges', ATTR_CONFIG_CHANGES, H(CONFIG_CHANGES)),
            A('launchMode', ATTR_LAUNCH_MODE, D(0)),               # standard
        ]
        if theme:
            attrs.append(A('theme', ATTR_THEME, R(theme)))
        act = app.el('activity', attrs)
        for action, category in filter_actions:
            f = act.el('intent-filter')
            f.el('action', [A('name', ATTR_NAME, S(action))])
            f.el('category', [A('name', ATTR_NAME, S(category))])
        return act

    activity('.MainActivity', True, filter_actions=(
        ('android.intent.action.MAIN', 'android.intent.category.LEANBACK_LAUNCHER'),
        ('android.intent.action.MAIN', 'android.intent.category.LAUNCHER'),
    ))
    activity('.player.PlayerActivity', False, theme=STYLE_NO_ACTION_BAR_FULLSCREEN)
    return manifest


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'build/AndroidManifest.xml'
    debuggable = '--debug' in sys.argv
    icon = None
    banner = None
    if '--res' in sys.argv:
        # resources.arsc ids: package 0x7f, type 1 = drawable
        icon = (0x7F010000,)
        banner = (0x7F010001,)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    data = axml.write_axml(build(debuggable, icon=icon, banner=banner), out)
    root = axml.read_axml(data)
    # self-check: attribute ids ascending, byte size consistent
    def check(n):
        ids = [a.resid for a in n.attrs if a.resid]
        assert ids == sorted(ids), (n.name, ids)
        for c in n.children:
            check(c)
    check(root)
    print('wrote %s (%d bytes), package=%s' % (out, len(data), PACKAGE))


if __name__ == '__main__':
    main()
