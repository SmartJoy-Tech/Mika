"""Prepare a candidate merge; never run upstream code with a write token."""
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM = 'wangzan101/Mika'
FORK = 'SmartJoy-Tech/Mika'


def git(*args, check=True):
    return subprocess.run(['git', *args], cwd=ROOT, text=True, capture_output=True, check=check)


def output(**values):
    print(json.dumps(values))
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as file:
        for key, value in values.items():
            file.write(f'{key}={value}\n')


def main():
    if os.environ.get('GITHUB_REPOSITORY') != FORK:
        raise RuntimeError('This workflow is scoped to the TinyFox fork')
    base = git('rev-parse', 'HEAD').stdout.strip()
    state = json.loads((ROOT / 'tinyfox/upstream.json').read_text())
    headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'TinyFox-upstream-sync'}
    if os.environ.get('GH_TOKEN'):
        headers['Authorization'] = 'Bearer ' + os.environ['GH_TOKEN']
    request = urllib.request.Request(f'https://api.github.com/repos/{UPSTREAM}/releases/latest', headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        release = json.load(response)
    tag = release['tag_name']
    if release['draft'] or release['prerelease'] or not re.fullmatch(r'v\d+\.\d+\.\d+', tag):
        raise RuntimeError('Only stable upstream version tags are accepted')
    git('fetch', '--no-tags', f'https://github.com/{UPSTREAM}.git', f'refs/tags/{tag}')
    target = git('rev-parse', 'FETCH_HEAD^{commit}').stdout.strip()
    if tag == state['tag']:
        if target != state['commit']:
            raise RuntimeError('Previously accepted upstream tag moved; manual review required')
        output(changed='false', base=base)
        return
    if git('merge-base', '--is-ancestor', state['commit'], target, check=False).returncode:
        raise RuntimeError('Upstream history is not a forward update')
    # Future upstream workflow/tool changes require review before running them.
    changed = git('diff', '--name-only', state['commit'], target).stdout.splitlines()
    guarded = ('.github/workflows/', 'tinyfox/', 'gradle/wrapper/')
    if any(p.startswith(guarded) or p in ('gradlew', 'gradlew.bat', '.gitmodules') for p in changed):
        raise RuntimeError('Upstream changed build automation; manual review required')
    branch = f'sync/upstream-{tag}-{base[:12]}'
    existing = git('ls-remote', '--heads', 'origin', branch).stdout.strip()
    if existing:
        sha = existing.split()[0]
        git('fetch', 'origin', branch)
        candidate = json.loads(git('show', f'{sha}:tinyfox/upstream.json').stdout)
        if candidate != {'repository': UPSTREAM, 'tag': tag, 'commit': target}:
            raise RuntimeError('Existing candidate branch has unexpected upstream state')
        if git('merge-base', '--is-ancestor', base, sha, check=False).returncode:
            raise RuntimeError('Existing candidate does not descend from the current TinyFox branch')
        if git('merge-base', '--is-ancestor', target, sha, check=False).returncode:
            raise RuntimeError('Existing candidate does not contain the claimed upstream commit')
        output(changed='true', sha=sha, base=base, branch=branch, tag=tag)
        return
    git('config', 'user.name', 'github-actions[bot]')
    git('config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
    git('switch', '-c', branch)
    merged = git('merge', '--no-ff', '--no-commit', target, check=False)
    if merged.returncode:
        print(merged.stdout)
        print(merged.stderr)
        git('merge', '--abort', check=False)
        raise RuntimeError('Merge conflict; TinyFox branch was not modified')
    (ROOT / 'tinyfox/upstream.json').write_text(json.dumps(
        {'repository': UPSTREAM, 'tag': tag, 'commit': target}, indent=2) + '\n', encoding='utf-8')
    git('add', 'tinyfox/upstream.json')
    git('commit', '-m', f'Sync upstream {tag} while retaining TinyFox identity')
    sha = git('rev-parse', 'HEAD').stdout.strip()
    git('push', 'origin', f'HEAD:refs/heads/{branch}')
    output(changed='true', sha=sha, base=base, branch=branch, tag=tag)


if __name__ == '__main__':
    main()
