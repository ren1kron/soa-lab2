#!/usr/bin/env python3
"""Build the project's native Helios bundle; server distributions are installed once."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

DEPLOYMENT_FILES = (
    'ConfigurePayara.java', 'helios.env.example', 'run.sh', 'check-schema.sql', 'wildfly.cli',
)
ORGANIZATIONS_URL = 'https://localhost:36125'
MANAGER_URL = 'https://localhost:36126'


def checksum(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def git_value(root, *args):
    try:
        result = subprocess.run(['git', *args], cwd=root, capture_output=True, text=True)
        return result.stdout.strip() if result.returncode == 0 else None
    except FileNotFoundError:
        return None


def package_project(root, frontend, output, verification):
    """Stage and validate everything before replacing the published project files."""
    root, frontend, output = Path(root), Path(frontend), Path(output)
    wars = {
        'organizations.war': root / 'organizations-service/build/libs/organizations.war',
        'orgmanager.war': root / 'orgmanager-service/build/libs/orgmanager.war',
    }
    for path in wars.values():
        with zipfile.ZipFile(path) as archive:
            bad = archive.testzip()
            if bad:
                raise ValueError(f'Corrupt WAR entry: {path}: {bad}')
    index = frontend / 'index.html'
    if not index.is_file() or '/client/assets/' not in index.read_text():
        raise ValueError('Build the frontend with --base=/client/ before packaging')
    helpers = root / 'deploy/helios'
    inputs = [helpers / name for name in DEPLOYMENT_FILES]
    inputs += [helpers / 'README-FIRST.md', root / 'docs/helios-native.md']
    for path in inputs:
        if not path.is_file():
            raise FileNotFoundError(path)
    cache = root / '.build-cache/helios-native'
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='package-', dir=cache) as directory:
        stage = Path(directory)
        project = stage / 'project'
        (project / 'artifacts').mkdir(parents=True)
        (project / 'deploy/helios').mkdir(parents=True)
        for name, path in wars.items():
            shutil.copyfile(path, project / 'artifacts' / name)
        with zipfile.ZipFile(project / 'artifacts/client.war', 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n\r\n')
            for path in sorted(frontend.rglob('*')):
                if path.is_file():
                    archive.write(path, path.relative_to(frontend).as_posix())
        for name in DEPLOYMENT_FILES:
            shutil.copyfile(helpers / name, project / 'deploy/helios' / name)
        shutil.copyfile(root / 'docs/helios-native.md', project / 'helios-native.md')
        bundle = stage / 'soa-lab2-native.tgz'
        with tarfile.open(bundle, 'w:gz', format=tarfile.PAX_FORMAT) as archive:
            for path in sorted(project.rglob('*')):
                if path.is_file():
                    archive.add(path, arcname=path.relative_to(project), recursive=False)
        digest = checksum(bundle)
        (stage / 'SHA256SUMS').write_text(f'{digest}  {bundle.name}\n')
        dirty = git_value(root, 'status', '--porcelain')
        manifest = {
            'built_at_utc': datetime.now(timezone.utc).isoformat(),
            'source_commit': git_value(root, 'rev-parse', 'HEAD'),
            'working_tree_has_changes': bool(dirty) if dirty is not None else None,
            'frontend': {'context_path': '/client/', 'organizations_url': ORGANIZATIONS_URL,
                         'manager_url': MANAGER_URL},
            'verification': verification,
            'files': [{'file': bundle.name, 'bytes': bundle.stat().st_size, 'sha256': digest}],
            'server_distributions': 'Installed once; this command does not download or modify them.',
        }
        (stage / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        shutil.copyfile(helpers / 'README-FIRST.md', stage / 'README-FIRST.md')
        output.mkdir(parents=True, exist_ok=True)
        for name in ('soa-lab2-native.tgz', 'SHA256SUMS', 'README-FIRST.md', 'manifest.json'):
            os.replace(stage / name, output / name)
    print(f'Bundle ready: {output / "soa-lab2-native.tgz"}', flush=True)


def build_environment():
    env = dict(os.environ)
    for key in ('_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'):
        env.pop(key, None)
    if not env.get('JAVA_HOME') and sys.platform == 'darwin':
        env['JAVA_HOME'] = subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip()
    java = str(Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else 'java'
    version = subprocess.run([java, '-version'], env=env, capture_output=True, text=True, check=True)
    if 'version "17.' not in version.stderr + version.stdout:
        raise ValueError('Set JAVA_HOME to a JDK 17 installation before building')
    if env.get('JAVA_HOME'):
        env['PATH'] = str(Path(env['JAVA_HOME']) / 'bin') + os.pathsep + env.get('PATH', '')
    return env


def main():
    parser = argparse.ArgumentParser(description='Rebuild the project bundle in artifacts/helios/; keep existing server ZIPs.')
    parser.add_argument('--docker', action='store_true', help='build Java services with the pinned local Gradle Docker image')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    env = build_environment()
    for command in ('node', 'npm') + (('docker',) if args.docker else ()):
        if shutil.which(command, path=env['PATH']) is None:
            raise ValueError(f'Missing build tool: {command}')
    node_version = subprocess.check_output(['node', '--version'], env=env, text=True).strip()
    if int(node_version.lstrip('v').split('.')[0]) < 24:
        raise ValueError('Use Node 24 or newer for the frontend build and tests')
    passed = []

    def run(command, cwd=root, environment=env):
        print(f'Running in {cwd.name}: ' + ' '.join(str(item) for item in command), flush=True)
        subprocess.run(command, cwd=cwd, env=environment, check=True)
        passed.append(' '.join(str(item) for item in command))

    if args.docker:
        run(['docker', 'info'])
    run([sys.executable, '-m', 'unittest', 'discover', '-s', 'tests', '-p', 'helios_*_test.py'])
    for service, cache in (('organizations-service', 'gradle-container'), ('orgmanager-service', 'gradle-manager')):
        if args.docker:
            run(['docker', 'run', '--rm', '-v', f'{root}:/work', '-w', f'/work/{service}',
                 'gradle:8.14.3-jdk17', 'gradle', '-g', f'/work/.build-cache/{cache}',
                 '--no-daemon', 'clean', 'build'])
        else:
            run(['./gradlew', '-g', str(root / '.build-cache/gradle'), '--no-daemon', 'clean', 'build'], root / service)
    run(['npm', 'ci', '--cache', str(root / '.build-cache/npm')], root / 'frontend')
    run(['npm', 'test'], root / 'frontend')
    cache = root / '.build-cache/helios-native'
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='frontend-', dir=cache) as directory:
        frontend = Path(directory) / 'dist'
        client_env = dict(env, VITE_ORGANIZATIONS_URL=ORGANIZATIONS_URL, VITE_MANAGER_URL=MANAGER_URL)
        run(['npm', 'run', 'build', '--', '--base=/client/', f'--outDir={frontend}', '--emptyOutDir'],
            root / 'frontend', client_env)
        package_project(root, frontend, root / 'artifacts/helios',
                        {'commands_passed': passed, 'postgresql_integration_tests': 'Run separately with scripts/test.sh'})


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f'Helios bundle build failed: {error}', file=sys.stderr)
        sys.exit(1)
