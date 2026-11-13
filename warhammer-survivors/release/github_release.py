"""Publish a Warhammer Survivors Tweaks zip as a GitHub release the DroidDeck Mod Updater can install.

prepare: writes WHSurvivorsTweaks-<version>.json next to the zip (every member's SHA-256, the archive
         digest and the committed source revision).
publish: creates tag warhammer-survivors-v<version> with the zip and the manifest.
verify:  downloads both assets again and checks them against the local files.

    python warhammer-survivors/release/github_release.py prepare --zip warhammer-survivors/release/WHSurvivorsTweaks-1.3.0.zip --version 1.3.0
"""
from pathlib import Path
import argparse, hashlib, json, subprocess, urllib.request, zipfile

ROOT = Path(__file__).resolve().parents[2]
REPO = 'Darkaxt/DroidDeck-Mod-Updater'
PRODUCT = 'WHSurvivorsTweaks'
LAUNCH = 'WINEDLLOVERRIDES="version=n,b" %command%'
MINIMUM_UPDATER = '0.3.0'
INSTALLABLE = ('MelonLoader/', 'Mods/', 'UserLibs/', 'Plugins/')

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument('action', choices=['prepare', 'publish', 'verify'])
parser.add_argument('--zip', required=True, type=Path)
parser.add_argument('--version', required=True)
parser.add_argument('--notes', type=Path, help='Release notes (publish)')
args = parser.parse_args()
sha = lambda data: hashlib.sha256(data).hexdigest()
tag = 'warhammer-survivors-v' + args.version
manifest_path = args.zip.with_name(f'{PRODUCT}-{args.version}.json')
assert args.zip.name == f'{PRODUCT}-{args.version}.zip', 'The zip name must match the version'

def git(*command): return subprocess.check_output(['git', *command], cwd=ROOT).decode().strip()

if args.action == 'prepare':
    files = {}
    with zipfile.ZipFile(args.zip) as archive:
        for entry in archive.infolist():
            if entry.is_dir(): continue
            name = entry.filename
            assert '\\' not in name and not name.startswith('/') and '..' not in name.split('/'), name
            assert name in ('version.dll', PRODUCT + '.txt') or name.startswith(INSTALLABLE), 'Not installable: ' + name
            assert name not in files, 'Duplicate member: ' + name
            files[name] = sha(archive.read(entry))
    assert 'version.dll' in files and f'Mods/{PRODUCT}.dll' in files
    assert not git('status', '--porcelain'), 'Commit the source first; the manifest records the revision'
    manifest = {'schema': 1, 'product': PRODUCT, 'version': args.version, 'source_commit': git('rev-parse', 'HEAD'),
                'archive_sha256': sha(args.zip.read_bytes()), 'minimum_updater': MINIMUM_UPDATER, 'launch_options': LAUNCH,
                'files': dict(sorted(files.items()))}
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf8')
    print(f'{manifest_path.name}: {len(files)} files, archive {manifest["archive_sha256"]}')
elif args.action == 'publish':
    manifest = json.loads(manifest_path.read_text())
    assert manifest['archive_sha256'] == sha(args.zip.read_bytes()) and manifest['version'] == args.version
    assert git('ls-remote', 'origin', 'refs/heads/main').split()[0] == manifest['source_commit'], 'Push the recorded source first'
    assert args.notes and args.notes.is_file(), 'Provide --notes'
    subprocess.run(['gh', 'release', 'create', tag, str(args.zip), str(manifest_path), '--repo', REPO, '--target', manifest['source_commit'],
                    '--title', f'Warhammer Survivors Tweaks {args.version}', '--notes-file', str(args.notes), '--latest=false'], cwd=ROOT, check=True)
else:
    release = json.load(urllib.request.urlopen(urllib.request.Request(f'https://api.github.com/repos/{REPO}/releases/tags/{tag}', headers={'User-Agent': 'DroidDeck-Mod-Updater-Release'})))
    assert not release['draft'] and not release['prerelease']
    assets = {asset['name']: asset for asset in release['assets']}
    for path in (args.zip, manifest_path):
        asset = assets[path.name]
        with urllib.request.urlopen(asset['browser_download_url']) as stream: data = stream.read()
        assert asset['digest'] == 'sha256:' + sha(data) == 'sha256:' + sha(path.read_bytes()), path.name
    print(f'{tag}: both assets downloaded and match the local files')
