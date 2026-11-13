"""Network-free tests for publication safety boundaries and resumability."""
import copy
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

import publish_tinyfox_release as publisher

SHA = '1' * 40
VERSION = '0.1.41-tinyfox.1'


class FakeGitHub:
    def __init__(self, release=None, assets=None):
        self.release = release
        self.assets = list(assets or [])
        self.writes = []
        self.commit = SHA
        self.ci_success = True
        self.move_after_upload = False
        self.tag_sha = SHA if release and not release['draft'] else None

    def get(self, path, missing=False):
        if path == '/commits/tinyfox':
            return {'sha': self.commit}
        if path.startswith('/actions/runs/'):
            return {'head_sha': SHA, 'status': 'completed',
                    'conclusion': 'success' if self.ci_success else 'failure',
                    'path': '.github/workflows/tinyfox-build.yml',
                    'head_repository': {'full_name': publisher.REPO}}
        if path.startswith('/git/ref/tags/'):
            return {'object': {'type': 'commit', 'sha': self.tag_sha}} if self.tag_sha else None
        if path == '/releases/latest':
            return copy.deepcopy(self.release) if self.release and not self.release['draft'] else None
        if path.startswith('/releases/tags/'):
            return copy.deepcopy(self.release)
        if path.startswith('/releases?'):
            return [copy.deepcopy(self.release)] if self.release else []
        if path.startswith('/releases/7/assets'):
            return copy.deepcopy(self.assets)
        raise AssertionError('Unexpected read: ' + path)

    def request(self, method, path, body=None, asset=None):
        self.writes.append((method, path, body))
        if method == 'POST' and path == '/releases':
            self.release = dict(body, id=7, html_url='https://github.com/' + publisher.REPO + '/releases/tag/v' + VERSION)
            return copy.deepcopy(self.release)
        if method == 'POST' and path.startswith('/releases/7/assets?'):
            self.assert_draft()
            result = {'name': asset.name, 'size': asset.size,
                      'state': 'uploaded', 'digest': 'sha256:' + asset.sha256}
            self.assets.append(result)
            if self.move_after_upload:
                self.commit = '2' * 40
            return copy.deepcopy(result)
        if method == 'PATCH' and path == '/releases/7':
            self.assert_draft()
            self.release.update(body)
            self.tag_sha = SHA
            return copy.deepcopy(self.release)
        raise AssertionError('Unexpected write')

    def assert_draft(self):
        assert self.release['draft'], 'Never upload into a public release'


class PublisherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        p = Path(self.temp.name) / 'sample.apk'
        p.write_bytes(b'a signed APK would already have passed local verification')
        self.asset = publisher.Asset.from_path(p, 'TinyFox-v' + VERSION + '-arm64.apk', 'application/octet-stream')
        self.manifest = {'version': VERSION, 'commit': SHA, 'ci_run_id': 10}

    def existing_release(self, draft=True):
        return {'id': 7, 'tag_name': 'v' + VERSION, 'target_commitish': SHA,
                'draft': draft, 'prerelease': False, 'html_url': 'https://github.com/' + publisher.REPO + '/releases/tag/v' + VERSION}

    def remote_asset(self):
        return {'name': self.asset.name, 'size': self.asset.size, 'state': 'uploaded', 'digest': 'sha256:' + self.asset.sha256}

    def test_allow_only_exact_api_hosts_and_repository(self):
        publisher.trusted_api_url('https://api.github.com/repos/' + publisher.REPO + '/releases')
        publisher.trusted_api_url('https://uploads.github.com/repos/' + publisher.REPO + '/releases/7/assets?name=foo', True)
        for url in ('http://api.github.com/repos/' + publisher.REPO + '/releases',
                    'https://api.github.com.evil.test/repos/' + publisher.REPO + '/releases',
                    'https://x@api.github.com/repos/' + publisher.REPO + '/releases',
                    'https://api.github.com:444/repos/' + publisher.REPO + '/releases',
                    'https://api.github.com/repos/other/repo/releases',
                    'https://github.com/repos/' + publisher.REPO + '/releases'):
            with self.subTest(url=url), self.assertRaises(RuntimeError):
                publisher.trusted_api_url(url)

    def test_upload_host_does_not_accept_arbitrary_endpoint(self):
        with self.assertRaises(RuntimeError):
            publisher.trusted_api_url('https://uploads.github.com/repos/' + publisher.REPO + '/issues', True)

    def test_versions_compare_numeric_tinyfox_revision(self):
        self.assertGreater(publisher.version_key('v0.1.41-tinyfox.10'), publisher.version_key('0.1.41-tinyfox.9'))
        self.assertGreater(publisher.version_key(VERSION), publisher.version_key('0.1.39-tinyfox.99'))
        for value in ('v0.1.41', '0.1.41-tinyfox.0', '0.1.41-tinyfox.100', '../bad'):
            with self.assertRaises(RuntimeError):
                publisher.version_key(value)

    def test_uploads_verified_asset_while_draft_then_publishes_stable(self):
        api = FakeGitHub()
        result = publisher.publish(api, self.manifest, [self.asset])
        self.assertTrue(result['published'])
        self.assertEqual([m for m, _, _ in api.writes], ['POST', 'POST', 'PATCH'])
        self.assertTrue(api.writes[0][2]['draft'])
        self.assertEqual(api.writes[-1][2], {'draft': False, 'prerelease': False, 'make_latest': 'true'})

    def test_existing_identical_published_release_is_read_only(self):
        api = FakeGitHub(self.existing_release(False), [self.remote_asset()])
        self.assertTrue(publisher.publish(api, self.manifest, [self.asset])['published'])
        self.assertEqual(api.writes, [])

    def test_resumes_identical_draft_without_uploading_again(self):
        api = FakeGitHub(self.existing_release(), [self.remote_asset()])
        publisher.publish(api, self.manifest, [self.asset])
        self.assertEqual(len(api.writes), 1)
        self.assertEqual(api.writes[0][0], 'PATCH')

    def test_different_asset_digest_cannot_be_overwritten(self):
        wrong = dict(self.remote_asset(), digest='sha256:' + '0' * 64)
        api = FakeGitHub(self.existing_release(), [wrong])
        with self.assertRaises(RuntimeError):
            publisher.publish(api, self.manifest, [self.asset])
        self.assertEqual(api.writes, [])

    def test_missing_remote_digest_is_rejected(self):
        for value in (None, '', 'sha512:' + '1' * 64):
            with self.assertRaises(RuntimeError):
                publisher.check_remote_asset(dict(self.remote_asset(), digest=value), self.asset)

    def test_failed_ci_does_not_create_draft(self):
        api = FakeGitHub()
        api.ci_success = False
        with self.assertRaises(RuntimeError):
            publisher.publish(api, self.manifest, [self.asset])
        self.assertEqual(api.writes, [])

    def test_moved_product_head_does_not_create_draft(self):
        api = FakeGitHub()
        api.commit = '3' * 40
        with self.assertRaises(RuntimeError):
            publisher.publish(api, self.manifest, [self.asset])
        self.assertEqual(api.writes, [])

    def test_moved_tag_does_not_create_draft(self):
        api = FakeGitHub()
        api.tag_sha = '3' * 40
        with self.assertRaises(RuntimeError):
            publisher.publish(api, self.manifest, [self.asset])
        self.assertEqual(api.writes, [])

    def test_head_move_during_upload_leaves_draft(self):
        api = FakeGitHub()
        api.move_after_upload = True
        with self.assertRaises(RuntimeError):
            publisher.publish(api, self.manifest, [self.asset])
        self.assertTrue(api.release['draft'])
        self.assertNotIn('PATCH', [m for m, _, _ in api.writes])

    def test_no_authenticated_redirect_following(self):
        self.assertIsNone(publisher.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://example.test'))

    def artifact_payload(self, altered=False, extra=False):
        unsigned = io.BytesIO()
        with zipfile.ZipFile(unsigned, 'w') as z:
            z.writestr('classes.dex', b'original executable payload')
            z.writestr('AndroidManifest.xml', b'package and version')
        artifact = Path(self.temp.name) / 'artifact.zip'
        with zipfile.ZipFile(artifact, 'w') as z:
            z.writestr('app-arm64-v8a-release-unsigned.apk', unsigned.getvalue())
        signed = Path(self.temp.name) / 'signed.apk'
        with zipfile.ZipFile(signed, 'w') as z:
            z.writestr('classes.dex', b'changed executable payload' if altered else b'original executable payload')
            z.writestr('AndroidManifest.xml', b'package and version')
            z.writestr('META-INF/SIGNER.SF', b'v1 signature file')
            if extra:
                z.writestr('assets/unreviewed.sh', b'unreviewed payload')
        provenance = {'kind': 'github-actions-artifact', 'commit': SHA, 'ci_run_id': 10,
            'artifact_id': 20, 'archive_sha256': publisher.digest(artifact),
            'unsigned_apk_entry': 'app-arm64-v8a-release-unsigned.apk',
            'unsigned_apk_sha256': hashlib.sha256(unsigned.getvalue()).hexdigest()}
        return signed, artifact, provenance

    def test_ci_payload_allows_only_added_signature_metadata(self):
        signed, artifact, provenance = self.artifact_payload()
        self.assertEqual(publisher.validate_ci_apk_payload(signed, artifact, provenance), provenance)

    def test_ci_payload_rejects_changed_bytecode(self):
        with self.assertRaises(RuntimeError):
            publisher.validate_ci_apk_payload(*self.artifact_payload(altered=True))

    def test_ci_payload_rejects_added_unreviewed_file(self):
        with self.assertRaises(RuntimeError):
            publisher.validate_ci_apk_payload(*self.artifact_payload(extra=True))

    def test_ci_artifact_hash_must_match_original_download(self):
        signed, artifact, provenance = self.artifact_payload()
        provenance['archive_sha256'] = '0' * 64
        with self.assertRaises(RuntimeError):
            publisher.validate_ci_apk_payload(signed, artifact, provenance)


if __name__ == '__main__':
    unittest.main()
