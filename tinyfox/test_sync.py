"""Exercise actual Git merges in temporary repositories, without network access."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import sync_upstream as sync


def command(path, *args):
    return subprocess.check_output(['git', '-C', str(path), *args], text=True, stderr=subprocess.DEVNULL).strip()


class SyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.upstream = self.root / 'upstream'
        self.fork = self.root / 'fork.git'
        self.work = self.root / 'work'
        self.upstream.mkdir()
        command(self.upstream, 'init', '-b', 'main')
        for key, value in [('user.name', 'Test'), ('user.email', 'test@example.invalid'), ('core.autocrlf', 'false')]:
            command(self.upstream, 'config', key, value)
        (self.upstream / 'feature.txt').write_text('base\n')
        command(self.upstream, 'add', '.')
        command(self.upstream, 'commit', '-m', 'base')
        command(self.upstream, 'tag', 'v0.1.0')
        self.base_upstream = command(self.upstream, 'rev-parse', 'HEAD')
        command(self.root, 'clone', '--bare', str(self.upstream), str(self.fork))
        command(self.root, 'clone', str(self.fork), str(self.work))
        command(self.work, 'switch', '-c', 'tinyfox')
        command(self.work, 'config', 'user.name', 'Test')
        command(self.work, 'config', 'user.email', 'test@example.invalid')
        (self.work / 'tinyfox').mkdir()
        (self.work / 'tinyfox/upstream.json').write_text(json.dumps(
            {'repository': sync.UPSTREAM, 'tag': 'v0.1.0', 'commit': self.base_upstream}))
        (self.work / 'brand.txt').write_text('TinyFox\n')
        command(self.work, 'add', '.')
        command(self.work, 'commit', '-m', 'TinyFox branding')
        command(self.work, 'push', 'origin', 'tinyfox')
        self.base = command(self.work, 'rev-parse', 'HEAD')
        self.release_tag = 'v0.1.0'
        self.out = self.root / 'outputs'

    def update(self, file='feature.txt', text='upstream update\n'):
        target = self.upstream / file
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        command(self.upstream, 'add', '.')
        command(self.upstream, 'commit', '-m', 'update')
        command(self.upstream, 'tag', 'v0.1.1')
        self.release_tag = 'v0.1.1'

    def run_sync(self):
        original_git = sync.git

        def local_git(*args, **kwargs):
            args = tuple(str(self.upstream) if a == f'https://github.com/{sync.UPSTREAM}.git' else a for a in args)
            return original_git(*args, **kwargs)

        body = {'tag_name': self.release_tag, 'draft': False, 'prerelease': False}
        with patch.object(sync, 'ROOT', self.work), patch.object(sync, 'git', local_git), \
             patch.dict(os.environ, {'GITHUB_REPOSITORY': sync.FORK, 'GITHUB_OUTPUT': str(self.out)}), \
             patch.object(sync.urllib.request, 'urlopen', return_value=io.BytesIO(json.dumps(body).encode())), \
             contextlib.redirect_stdout(io.StringIO()):
            sync.main()
        return dict(line.split('=', 1) for line in self.out.read_text().splitlines())

    def test_unchanged_release_does_not_push(self):
        self.assertEqual(self.run_sync()['changed'], 'false')
        self.assertEqual(command(self.fork, 'rev-parse', 'tinyfox'), self.base)

    def test_candidate_preserves_brand_and_does_not_promote_before_tests(self):
        self.update()
        result = self.run_sync()
        self.assertEqual(result['changed'], 'true')
        self.assertEqual((self.work / 'brand.txt').read_text(), 'TinyFox\n')
        self.assertEqual((self.work / 'feature.txt').read_text(), 'upstream update\n')
        self.assertEqual(command(self.fork, 'rev-parse', 'tinyfox'), self.base)
        self.assertEqual(command(self.fork, 'rev-parse', result['branch']), result['sha'])
        command(self.work, 'switch', 'tinyfox')
        self.assertEqual(self.run_sync()['sha'], result['sha'])

    def test_conflict_keeps_current_branch_on_remote(self):
        (self.work / 'feature.txt').write_text('local customization\n')
        command(self.work, 'add', '.')
        command(self.work, 'commit', '-m', 'local customization')
        command(self.work, 'push', 'origin', 'tinyfox')
        current = command(self.work, 'rev-parse', 'HEAD')
        self.update()
        with self.assertRaisesRegex(RuntimeError, 'Merge conflict'):
            self.run_sync()
        self.assertEqual(command(self.fork, 'rev-parse', 'tinyfox'), current)

    def test_upstream_automation_change_requires_review(self):
        self.update('.github/workflows/untrusted.yml')
        with self.assertRaisesRegex(RuntimeError, 'manual review'):
            self.run_sync()
        self.assertEqual(command(self.fork, 'rev-parse', 'tinyfox'), self.base)

    def test_moved_accepted_tag_requires_review(self):
        self.update()
        command(self.upstream, 'tag', '-f', 'v0.1.0')
        self.release_tag = 'v0.1.0'
        with self.assertRaisesRegex(RuntimeError, 'tag moved'):
            self.run_sync()


if __name__ == '__main__':
    unittest.main()
