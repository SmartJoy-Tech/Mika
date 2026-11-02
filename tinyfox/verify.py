"""Check TinyFox's installed identity and keep upstream update checks intact."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APP_ID = 'io.github.smartjoytech.tinyfox'
SERVICE = APP_ID + '.a11y.TinyFoxAccessibilityService'
REPO = 'SmartJoy-Tech/Mika'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def read(relative):
    return (ROOT / relative).read_text(encoding='utf-8')


def versions():
    gradle = read('app/build.gradle.kts')
    upstream_code = int(re.search(r'^\s*versionCode = (\d+)\s*$', gradle, re.M)[1])
    upstream_name = re.search(r'^\s*versionName = "([\d.]+)"\s*$', gradle, re.M)[1]
    revision = int(read('tinyfox/revision.txt').strip())
    require(1 <= revision <= 99, 'TinyFox revision must be 1..99')
    require(json.loads(read('tinyfox/upstream.json'))['tag'] == 'v' + upstream_name,
            'Upstream state does not match the Android version')
    return upstream_code * 100 + revision, f'{upstream_name}-tinyfox.{revision}'


def verify_sources():
    branding = json.loads(read('tinyfox/branding.json'))
    brand_asset = ROOT / 'tinyfox' / branding['asset']
    require(hashlib.sha256(brand_asset.read_bytes()).hexdigest() == branding['sha256'],
            'SmartJoy artwork differs from the reviewed asset')
    require(f'applicationId = "{APP_ID}"' in read('app/build.gradle.kts'), 'Wrong applicationId')
    require('buildConfigField("boolean", "TINYFOX_ADVANCED_SETTINGS", "true")' in read('app/build.gradle.kts')
            and 'BuildConfig.TINYFOX_ADVANCED_SETTINGS' in read('app/src/main/java/com/wangzan101/mika/ui/SettingsScreen.kt'),
            'Personal advanced preferences are not exposed')
    manifest = ET.fromstring(read('app/src/main/AndroidManifest.xml'))
    permissions = {e.get(ANDROID + 'name') for e in manifest.findall('uses-permission')}
    require(permissions == set(json.loads(read('tinyfox/permissions.json'))),
            'Requested permissions changed; review the new upstream permissions before promotion')
    services = manifest.findall('./application/service')
    accessibility = [s for s in services if s.get(ANDROID + 'permission') == 'android.permission.BIND_ACCESSIBILITY_SERVICE']
    require(len(accessibility) == 1 and accessibility[0].get(ANDROID + 'name') == SERVICE,
            'Manifest must register exactly the TinyFox accessibility service')
    require(f'val service = "{SERVICE}"' in read('core/src/main/kotlin/com/wangzan101/mika/core/shell/RecoveryPermissions.kt'),
            'Recovery service identity drifted')
    require('class TinyFoxAccessibilityService : ScreenAccessibility()' in
            read('app/src/main/java/io/github/smartjoytech/tinyfox/a11y/TinyFoxAccessibilityService.kt'), 'Missing service implementation')
    require(f'/Android/data/{APP_ID}/files' in read('tools/mika/scripts/mika_cli.py'), 'Computer CLI package drifted')
    links = read('app/src/main/java/com/wangzan101/mika/about/MikaLinks.kt')
    require(f'"https://github.com/{REPO}"' in links and
            f'"https://api.github.com/repos/{REPO}/releases/latest"' in links, 'Update channel drifted')
    versions()


def run_tool(executable, *args, cwd=None):
    return subprocess.check_output([str(executable), *map(str, args)], cwd=cwd, text=True, encoding='utf-8', errors='replace')


def verify_apk(apk, aapt, certificate=None, apksigner=None):
    apk = Path(apk).resolve()
    # aapt on Windows cannot open Chinese absolute filenames. Use the APK's cwd.
    badging = run_tool(aapt, 'dump', 'badging', apk.name, cwd=apk.parent)
    tree = run_tool(aapt, 'dump', 'xmltree', apk.name, 'AndroidManifest.xml', cwd=apk.parent)
    code, name = versions()
    require(f"package: name='{APP_ID}' versionCode='{code}' versionName='{name}'" in badging,
            'APK package or version mismatch')
    require("application-label:'TinyFox'" in badging, 'APK launcher name is not TinyFox')
    require('application-debuggable' not in badging, 'Do not distribute a debuggable APK')
    require(f'"{SERVICE}"' in tree, 'APK does not contain the registered TinyFox service')
    require('com.wangzan101.mika.a11y.MikaAccessibilityService' not in tree, 'Old service remains registered')
    require('com.wangzan101.mika.helper' not in tree, 'Provider authority points to the original app')
    permissions = set(re.findall(r"uses-permission: name='([^']+)'", badging))
    allowed = set(json.loads(read('tinyfox/permissions.json'))) | {APP_ID + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}
    require(permissions == allowed, 'Merged APK permissions differ from the reviewed baseline')
    branding = json.loads(read('tinyfox/branding.json'))
    resources = run_tool(aapt, 'dump', '--values', 'resources', apk.name, cwd=apk.parent)
    icon = re.search(r'(?m)^\s+resource [^\n]+:drawable/mika_launcher_art:[^\n]*\n\s+\(string8\) "([^"]+)"', resources)
    require(icon is not None, 'Cannot locate the packaged SmartJoy artwork')
    with zipfile.ZipFile(apk) as archive:
        icon_hash = hashlib.sha256(archive.read(icon[1])).hexdigest()
    require(icon_hash == branding['compiled_png_sha256'], 'APK contains unexpected logo artwork')
    signature = None
    if certificate:
        require(apksigner is not None, 'apksigner required for signed APK verification')
        output = run_tool(apksigner, 'verify', '--verbose', '--print-certs', apk)
        actual = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', output)[1]
        require(actual.lower() == certificate.lower(), 'APK signer differs from the pinned TinyFox certificate')
        signature = actual
    return {'package': APP_ID, 'service': SERVICE, 'versionCode': code, 'versionName': name,
            'apk': apk.name, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
            'bytes': apk.stat().st_size, 'certificate_sha256': signature,
            'brand': branding['brand'], 'compiled_icon_sha256': icon_hash}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk')
    parser.add_argument('--aapt')
    parser.add_argument('--certificate')
    parser.add_argument('--apksigner')
    parser.add_argument('--output')
    args = parser.parse_args()
    verify_sources()
    result = {'source_identity': 'passed'}
    if args.apk:
        require(args.aapt, '--aapt is required with --apk')
        result.update(verify_apk(args.apk, args.aapt, args.certificate, args.apksigner))
    if args.output:
        Path(args.output).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
