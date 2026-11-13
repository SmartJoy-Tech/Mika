"""Publish an already verified TinyFox build; no signing key is read or uploaded.

Default: local validation only. --publish explicitly performs GitHub mutations.
Only this fork, three generated/public assets and the supplied GPL source bundle
are supported. Credentials stay in memory and authenticated redirects are denied.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

REPO = 'SmartJoy-Tech/Mika'
PACKAGE = 'io.github.smartjoytech.tinyfox'
SERVICE = PACKAGE + '.a11y.TinyFoxAccessibilityService'
CERTIFICATE = 'aa6809bcc98f10faacddc36351fb30e84bf721eabf61517c83318ef0d2472192'
VERSION = re.compile(r'^(\d+)\.(\d+)\.(\d+)-tinyfox\.([1-9]\d?)$')
SHA = re.compile(r'^[0-9a-f]{64}$')
COMMIT = re.compile(r'^[0-9a-f]{40}$')


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def version_key(value):
    match = VERSION.fullmatch(value.removeprefix('v'))
    require(match is not None, 'Unexpected TinyFox version: ' + value)
    return tuple(map(int, match.groups()))


def trusted_api_url(url, upload=False):
    parsed = urllib.parse.urlsplit(url)
    expected = 'uploads.github.com' if upload else 'api.github.com'
    require(parsed.scheme == 'https' and parsed.hostname == expected and parsed.port in (None, 443)
            and not parsed.username and not parsed.password and not parsed.fragment,
            'Refusing authenticated request outside the exact GitHub API host')
    require(parsed.path.startswith('/repos/' + REPO + '/'), 'Refusing another repository')
    if upload:
        require(re.fullmatch('/repos/' + re.escape(REPO) + r'/releases/\d+/assets', parsed.path)
                is not None, 'Unexpected asset upload path')
    return url


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


class GitHub:
    def __init__(self):
        process = subprocess.run(['git', 'credential', 'fill'],
            input='protocol=https\nhost=github.com\n\n', text=True, capture_output=True,
            timeout=20, env=dict(os.environ, GIT_TERMINAL_PROMPT='0', GCM_INTERACTIVE='never'))
        require(process.returncode == 0, 'Existing GitHub credential unavailable')
        fields = dict(line.split('=', 1) for line in process.stdout.splitlines() if '=' in line)
        self.token = fields.get('password')
        require(bool(self.token), 'Existing GitHub credential unavailable')
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, method, path, body=None, *, asset=None, missing=False):
        url = ('https://uploads.github.com' if asset else 'https://api.github.com') + '/repos/' + REPO + path
        trusted_api_url(url, upload=asset is not None)
        headers = {'Authorization': 'Bearer ' + self.token,
                   'Accept': 'application/vnd.github+json', 'User-Agent': 'TinyFox-local-release',
                   'X-GitHub-Api-Version': '2022-11-28'}
        stream = None
        try:
            if asset:
                # Stream the source bundle; never load a several-hundred-MB archive into memory.
                stream = asset.path.open('rb')
                data = stream
                headers.update({'Content-Type': asset.mime, 'Content-Length': str(asset.size)})
            else:
                data = json.dumps(body).encode() if body is not None else None
                if data is not None:
                    headers['Content-Type'] = 'application/json'
            request = urllib.request.Request(url, data=data, method=method, headers=headers)
            try:
                with self.opener.open(request, timeout=300 if asset else 30) as response:
                    payload = response.read()
                    return json.loads(payload) if payload else {}
            except urllib.error.HTTPError as error:
                if missing and error.code == 404:
                    return None
                # No credential, payload or arbitrary server body enters the log.
                raise RuntimeError(f'GitHub {method} {path.split("?")[0]}: HTTP {error.code}') from None
        finally:
            if stream:
                stream.close()

    def get(self, path, missing=False):
        return self.request('GET', path, missing=missing)


@dataclass(frozen=True)
class Asset:
    path: Path
    name: str
    mime: str
    size: int
    sha256: str

    @classmethod
    def from_path(cls, path, name, mime):
        path = Path(path).resolve()
        require(path.is_file() and path.stat().st_size > 0, 'Missing or empty public release asset')
        require(re.fullmatch(r'[A-Za-z0-9._-]+', name) is not None, 'Unexpected asset name')
        return cls(path, name, mime, path.stat().st_size, digest(path))

    def public(self):
        return {'name': self.name, 'size': self.size, 'sha256': self.sha256}


def check_remote_asset(remote, asset):
    require(remote.get('name') == asset.name and remote.get('state') == 'uploaded'
            and remote.get('size') == asset.size
            and str(remote.get('digest') or '').lower() == 'sha256:' + asset.sha256,
            'Existing or uploaded asset differs; refusing replacement: ' + asset.name)


def verify_source_archive(path, expected_hash):
    require(SHA.fullmatch(expected_hash) is not None and digest(path) == expected_hash,
            'GPL source bundle digest mismatch')
    names = set()
    with tarfile.open(path, 'r:gz') as archive:
        for member in archive:
            name = PurePosixPath(member.name)
            require(not name.is_absolute() and '..' not in name.parts, 'Unsafe source archive member')
            names.add(name.as_posix())
    required = ('sources.lock', 'build-native.sh', 'GPL-2.0.txt', 'GPL-3.0.txt', 'LGPL-3.0.txt')
    require(all(any(PurePosixPath(name).name == wanted for name in names) for wanted in required),
            'GPL source bundle is missing its source lock, build script or licenses')
    require(any('/patches/' in name for name in names)
            and any(name.endswith('/runtime/SOURCES.txt') for name in names),
            'GPL source bundle is missing native patches or runtime source manifest')


def validate_ci_apk_payload(signed_apk, artifact_zip, provenance):
    """Bind the signed APK's payload to the verified unsigned CI artifact."""
    require(artifact_zip and Path(artifact_zip).is_file(), '--ci-artifact-zip is required for CI provenance')
    require(SHA.fullmatch(provenance.get('archive_sha256', '')) is not None
            and digest(artifact_zip) == provenance['archive_sha256'], 'CI artifact archive hash mismatch')
    entry = provenance.get('unsigned_apk_entry', '')
    require(entry.endswith('-unsigned.apk') and '..' not in PurePosixPath(entry).parts,
            'Unexpected unsigned APK entry')
    require(SHA.fullmatch(provenance.get('unsigned_apk_sha256', '')) is not None,
            'Unsigned APK digest missing')
    def zip_hash(archive, name):
        h = hashlib.sha256()
        with archive.open(name) as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                h.update(chunk)
        return h.hexdigest()
    with zipfile.ZipFile(artifact_zip) as artifact, tempfile.TemporaryFile() as temporary:
        require(artifact.namelist().count(entry) == 1, 'Missing or duplicate CI APK entry')
        h = hashlib.sha256()
        with artifact.open(entry) as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                temporary.write(chunk)
                h.update(chunk)
        require(h.hexdigest() == provenance['unsigned_apk_sha256'], 'Unsigned APK hash mismatch')
        temporary.seek(0)
        with zipfile.ZipFile(temporary) as unsigned, zipfile.ZipFile(signed_apk) as signed:
            unsigned_names, signed_names = unsigned.namelist(), signed.namelist()
            require(len(set(unsigned_names)) == len(unsigned_names)
                    and len(set(signed_names)) == len(signed_names), 'Duplicate APK payload entries')
            extra = set(signed_names) - set(unsigned_names)
            signature_metadata = re.compile(r'^META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$', re.I)
            require(all(signature_metadata.fullmatch(name) for name in extra),
                    'Signed APK contains payload absent from CI')
            require(set(unsigned_names) <= set(signed_names), 'Signed APK is missing CI payload')
            for name in unsigned_names:
                require(zip_hash(unsigned, name) == zip_hash(signed, name),
                        'Signed APK payload differs from CI: ' + name)
    return {key: provenance[key] for key in ('kind', 'commit', 'ci_run_id', 'artifact_id',
            'archive_sha256', 'unsigned_apk_entry', 'unsigned_apk_sha256')}


def validate_local(args):
    require(COMMIT.fullmatch(args.expected_commit) is not None, 'Expected a full commit SHA')
    verification, provenance = read_json(args.verification), read_json(args.provenance)
    pin = Path(args.certificate_pin).read_text(encoding='utf-8-sig').strip().lower()
    require(pin == CERTIFICATE, 'Unexpected certificate pin; existing TinyFox identity must remain')
    require(verification.get('source_identity') == 'passed' and verification.get('package') == PACKAGE
            and verification.get('service') == SERVICE, 'Source/APK identity verification missing')
    require(provenance.get('commit') == args.expected_commit, 'Build source provenance mismatch')
    ci_artifact = None
    if provenance.get('kind') == 'github-actions-artifact':
        require(provenance.get('ci_run_id') == args.ci_run_id
                and isinstance(provenance.get('artifact_id'), int) and provenance['artifact_id'] > 0,
                'CI artifact provenance identifies the wrong run')
        ci_artifact = validate_ci_apk_payload(args.apk, args.ci_artifact_zip, provenance)
    else:
        require(provenance.get('build_source_files_match') is True, 'Local build source verification missing')
    version = verification.get('versionName', '')
    version_key(version)
    require(isinstance(verification.get('versionCode'), int)
            and verification['versionCode'] > args.previous_version_code, 'Version code must increase')
    require(verification.get('certificate_sha256', '').lower() == pin, 'Verification signer mismatch')
    apk = Asset.from_path(args.apk, 'TinyFox-v' + version + '-arm64.apk',
                          'application/vnd.android.package-archive')
    require(apk.sha256 == verification.get('sha256') and apk.size == verification.get('bytes'),
            'APK no longer matches its verification report')
    env = os.environ.copy()
    if args.java_home:
        env['JAVA_HOME'] = str(Path(args.java_home).resolve())
    signer = subprocess.run([str(Path(args.apksigner).resolve()), 'verify', '--verbose', '--print-certs', str(apk.path)],
                            capture_output=True, text=True, encoding='utf-8', errors='replace', env=env, timeout=120)
    actual = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})', signer.stdout)
    require(signer.returncode == 0 and len(actual) == 1 and actual[0].lower() == pin,
            'Independent APK signature verification failed')
    badging = subprocess.run([str(Path(args.aapt).resolve()), 'dump', 'badging', apk.path.name],
                             cwd=apk.path.parent, capture_output=True, text=True, encoding='utf-8',
                             errors='replace', timeout=60)
    expected = f"package: name='{PACKAGE}' versionCode='{verification['versionCode']}' versionName='{version}'"
    require(badging.returncode == 0 and expected in badging.stdout
            and 'application-debuggable' not in badging.stdout, 'Independent APK identity verification failed')
    verify_source_archive(args.gpl_sources, args.gpl_sha256.lower())
    sources = Asset.from_path(args.gpl_sources, 'TinyFox-v' + version + '-gpl-sources.tar.gz', 'application/gzip')
    out = Path(args.out_dir).resolve()
    out.mkdir(parents=True, exist_ok=True)
    manifest = {'version': version, 'versionCode': verification['versionCode'], 'package': PACKAGE,
                'commit': args.expected_commit, 'certificate_sha256': pin,
                'ci_run_id': args.ci_run_id, 'assets': [apk.public(), sources.public()]}
    if ci_artifact:
        manifest['ci_artifact'] = ci_artifact
    manifest_file = out / 'release-manifest.json'
    manifest_file.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    manifest_asset = Asset.from_path(manifest_file, 'release-manifest.json', 'application/json')
    sums_file = out / 'SHA256SUMS.txt'
    sums_file.write_text(''.join(a.sha256 + '  ' + a.name + '\n' for a in (apk, sources, manifest_asset)), encoding='ascii')
    return manifest, [apk, sources, manifest_asset, Asset.from_path(sums_file, 'SHA256SUMS.txt', 'text/plain')]


def tag_commit(api, tag):
    ref = api.get('/git/ref/tags/' + urllib.parse.quote(tag, safe=''), missing=True)
    if not ref:
        return None
    obj = ref['object']
    for _ in range(8):
        if obj['type'] == 'commit':
            return obj['sha']
        require(obj['type'] == 'tag', 'Unsupported Git tag object')
        obj = api.get('/git/tags/' + obj['sha'])['object']
    raise RuntimeError('Unexpected tag indirection depth')


def find_release(api, tag):
    result = api.get('/releases/tags/' + urllib.parse.quote(tag, safe=''), missing=True)
    if result:
        return result
    # Some GitHub responses omit drafts from tag lookup; authenticated listing finds them.
    for page in range(1, 11):
        releases = api.get(f'/releases?per_page=100&page={page}')
        found = [r for r in releases if r['tag_name'] == tag]
        require(len(found) <= 1, 'Duplicate Release tag')
        if found:
            return found[0]
        if len(releases) < 100:
            return None
    raise RuntimeError('Release listing exceeded bounded lookup; manual review required')


def publish(api, manifest, assets):
    version, commit = manifest['version'], manifest['commit']
    tag = 'v' + version
    require(api.get('/commits/tinyfox')['sha'] == commit, 'Product branch moved; re-review before publishing')
    run = api.get('/actions/runs/' + str(manifest['ci_run_id']))
    require(run.get('head_sha') == commit and run.get('status') == 'completed'
            and run.get('conclusion') == 'success'
            and run.get('path') == '.github/workflows/tinyfox-build.yml'
            and run.get('head_repository', {}).get('full_name', '').lower() == REPO.lower(),
            'Exact-commit TinyFox build verification has not passed')
    if artifact := manifest.get('ci_artifact'):
        remote = api.get('/actions/artifacts/' + str(artifact['artifact_id']))
        require(remote.get('workflow_run', {}).get('id') == manifest['ci_run_id']
                and remote.get('workflow_run', {}).get('head_sha') == commit
                and not remote.get('expired')
                and remote.get('name') == 'tinyfox-unsigned-' + str(manifest['ci_run_id'])
                and str(remote.get('digest') or '').lower() == 'sha256:' + artifact['archive_sha256'],
                'GitHub artifact metadata does not match the verified APK provenance')
    existing_commit = tag_commit(api, tag)
    require(existing_commit in (None, commit), 'Release tag points to different source; refusing overwrite')
    latest = api.get('/releases/latest', missing=True)
    if latest:
        require(version_key(latest['tag_name']) <= version_key(version), 'Refusing to replace a newer latest Release')
    release = find_release(api, tag)
    if release:
        require(release.get('target_commitish') == commit or existing_commit == commit,
                'Existing draft does not point to the verified commit')
        require(not release.get('prerelease'), 'Existing tag is marked prerelease; manual review required')
    else:
        body = ('TinyFox ' + version + '\n\nBased on upstream Mika v' + version.split('-')[0] + '.\n'
                'Keeps the TinyFox application identity and existing signing certificate.\n\n'
                'Verified source: https://github.com/' + REPO + '/commit/' + commit + '\n'
                'Build verification: https://github.com/' + REPO + '/actions/runs/' + str(manifest['ci_run_id']) + '\n\n'
                'The arm64 APK is for Android arm64-v8a devices. Installation retains existing TinyFox data.\n'
                'The accompanying GPL source archive and repository source provide the corresponding native/runtime sources.\n'
                'Original Mika: https://github.com/wangzan101/Mika (Apache-2.0); third-party licenses remain applicable.\n')
        release = api.request('POST', '/releases', {'tag_name': tag, 'target_commitish': commit,
            'name': 'TinyFox ' + version, 'body': body, 'draft': True, 'prerelease': False, 'make_latest': 'false'})
    release_id = release['id']
    remote_assets = api.get(f'/releases/{release_id}/assets?per_page=100')
    require(len(remote_assets) < 100, 'Unexpected number of release assets')
    wanted = {a.name for a in assets}
    require(all(a['name'] in wanted for a in remote_assets), 'Unexpected assets on existing Release; manual review required')
    for asset in assets:
        existing = [a for a in remote_assets if a['name'] == asset.name]
        require(len(existing) <= 1, 'Duplicate asset name')
        if existing:
            check_remote_asset(existing[0], asset)
            continue
        require(release['draft'], 'Published Release is incomplete; refusing to modify its assets')
        # Read-only validation was completed before opening the file for network upload.
        require(asset.path.stat().st_size == asset.size and digest(asset.path) == asset.sha256,
                'Public asset changed after validation')
        uploaded = api.request('POST', f'/releases/{release_id}/assets?name=' + urllib.parse.quote(asset.name), asset=asset)
        check_remote_asset(uploaded, asset)
    confirmed = api.get(f'/releases/{release_id}/assets?per_page=100')
    require(len(confirmed) == len(assets), 'Incomplete release upload')
    for asset in assets:
        matches = [a for a in confirmed if a['name'] == asset.name]
        require(len(matches) == 1, 'Missing or duplicate uploaded asset')
        check_remote_asset(matches[0], asset)
    # Re-check the branch before public visibility; never publish a superseded build.
    require(api.get('/commits/tinyfox')['sha'] == commit, 'Product branch moved during upload; draft left unpublished')
    if release['draft']:
        release = api.request('PATCH', f'/releases/{release_id}',
                              {'draft': False, 'prerelease': False, 'make_latest': 'true'})
    require(not release.get('draft') and not release.get('prerelease'), 'Release not publicly stable')
    require(tag_commit(api, tag) == commit, 'Published tag source mismatch')
    latest = api.get('/releases/latest')
    require(latest['id'] == release_id, 'Published release is not returned by the App latest endpoint')
    return {'published': True, 'repository': REPO, 'tag': tag, 'commit': commit,
            'release_id': release_id, 'url': release['html_url'], 'assets': [a.public() for a in assets],
            'latest_api_verified': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('apk', 'verification', 'provenance', 'expected-commit', 'certificate-pin', 'apksigner',
                 'aapt', 'gpl-sources', 'gpl-sha256', 'out-dir'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--previous-version-code', type=int, required=True)
    parser.add_argument('--ci-run-id', type=int, required=True)
    parser.add_argument('--java-home')
    parser.add_argument('--ci-artifact-zip', help='Downloaded original Actions artifact ZIP for CI provenance')
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    manifest, assets = validate_local(args)
    if not args.publish:
        print(json.dumps({'local_validation_passed': True, 'network_requests': 0,
                          'manifest': manifest, 'assets': [a.public() for a in assets]}, indent=2))
        return
    api = GitHub()
    try:
        result = publish(api, manifest, assets)
    finally:
        api.token = None
    (Path(args.out_dir) / 'publication.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
