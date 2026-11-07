"""Project bundle checks without Java/Node builds or PostgreSQL."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('build_helios', ROOT / 'scripts/build_helios.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class HeliosProjectBundle(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'project with spaces'
        self.output = self.root / 'artifacts/helios'
        self.output.mkdir(parents=True)
        self.frontend = self.root / 'built-client'
        (self.frontend / 'assets').mkdir(parents=True)
        (self.frontend / 'index.html').write_text('<script src="/client/assets/app.js"></script>')
        (self.frontend / 'assets/app.js').write_text('current frontend')
        for service, war in [('organizations-service', 'organizations.war'),
                             ('orgmanager-service', 'orgmanager.war')]:
            path = self.root / service / 'build/libs' / war
            path.parent.mkdir(parents=True)
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('WEB-INF/web.xml', '<web-app/>')
        helpers = self.root / 'deploy/helios'
        helpers.mkdir(parents=True)
        for name in builder.DEPLOYMENT_FILES:
            (helpers / name).write_text('deployment helper ' + name)
        (helpers / 'helios.env').write_text('DATABASE_PASSWORD=do-not-package')
        (helpers / 'server.p12').write_bytes(b'private key')
        (helpers / 'README-FIRST.md').write_text('Project update instructions')
        (self.root / 'docs').mkdir()
        (self.root / 'docs/helios-native.md').write_text('Full deployment guide')

    def package(self):
        builder.package_project(self.root, self.frontend, self.output, {'commands_passed': ['fixture build']})

    def test_packages_fresh_wars_static_assets_and_only_public_deployment_files(self):
        self.package()
        with tarfile.open(self.output / 'soa-lab2-native.tgz') as archive:
            names = set(archive.getnames())
            expected = {'artifacts/organizations.war', 'artifacts/orgmanager.war',
                        'artifacts/client.war', 'helios-native.md',
                        'deploy/helios/ConfigurePayara.java', 'deploy/helios/helios.env.example',
                        'deploy/helios/run.sh', 'deploy/helios/check-schema.sql', 'deploy/helios/wildfly.cli'}
            self.assertEqual(expected, names)
            self.assertEqual(b'Full deployment guide', archive.extractfile('helios-native.md').read())
            client = archive.extractfile('artifacts/client.war').read()
            with zipfile.ZipFile(io.BytesIO(client)) as war:
                self.assertEqual(b'current frontend', war.read('assets/app.js'))
        checksum = hashlib.sha256((self.output / 'soa-lab2-native.tgz').read_bytes()).hexdigest()
        self.assertEqual(f'{checksum}  soa-lab2-native.tgz\n', (self.output / 'SHA256SUMS').read_text())
        manifest = json.loads((self.output / 'manifest.json').read_text())
        self.assertEqual(checksum, manifest['files'][0]['sha256'])
        self.assertEqual(['fixture build'], manifest['verification']['commands_passed'])
        self.assertEqual('Project update instructions', (self.output / 'README-FIRST.md').read_text())

    def test_refreshes_project_files_and_leaves_existing_server_archives_alone(self):
        runtime = self.output / 'payara-6.2025.11.zip'
        runtime.write_bytes(b'existing one-time distribution')
        original_mtime = runtime.stat().st_mtime_ns
        self.package()
        before = (self.output / 'soa-lab2-native.tgz').read_bytes()
        (self.frontend / 'assets/app.js').write_text('changed frontend')
        self.package()
        self.assertNotEqual(before, (self.output / 'soa-lab2-native.tgz').read_bytes())
        with tarfile.open(self.output / 'soa-lab2-native.tgz') as archive:
            with zipfile.ZipFile(io.BytesIO(archive.extractfile('artifacts/client.war').read())) as client:
                self.assertEqual(b'changed frontend', client.read('assets/app.js'))
        self.assertEqual(b'existing one-time distribution', runtime.read_bytes())
        self.assertEqual(original_mtime, runtime.stat().st_mtime_ns)

    def test_missing_or_corrupt_build_artifacts_preserve_the_previous_bundle(self):
        self.package()
        previous = {name: (self.output / name).read_bytes()
                    for name in ('soa-lab2-native.tgz', 'SHA256SUMS', 'manifest.json', 'README-FIRST.md')}
        manager = self.root / 'orgmanager-service/build/libs/orgmanager.war'
        manager.unlink()
        with self.assertRaises((FileNotFoundError, ValueError)):
            self.package()
        manager.write_bytes(b'not a WAR')
        with self.assertRaises((zipfile.BadZipFile, ValueError)):
            self.package()
        for name, content in previous.items():
            self.assertEqual(content, (self.output / name).read_bytes(), name)


if __name__ == '__main__':
    unittest.main(verbosity=2)
