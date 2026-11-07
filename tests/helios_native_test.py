"""Offline deployment configuration checks; run on the build computer with JDK 17+."""
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = str(Path(os.environ['JAVA_HOME'], 'bin/java')) if os.getenv('JAVA_HOME') else 'java'
CONFIGURATOR = ROOT / 'deploy/helios/ConfigurePayara.java'
WARS = ('organizations.war', 'orgmanager.war', 'client.war')
COMMANDS = ('--command=:shutdown', 'stop-domain', 'start-domain', 'deploy')

DOMAIN = '''<domain>
<hazelcast-runtime-configuration/>
<configs><config name="server-config">
<java-config><jvm-options>-Xmx2g</jvm-options><jvm-options>-Xms1g</jvm-options>
<jvm-options>-XX:MaxMetaspaceSize=128m</jvm-options><jvm-options>-XX:+UseG1GC</jvm-options>
<jvm-options>-Dorg.glassfish.additionalOSGiBundlesToStart=org.apache.felix.shell,org.apache.felix.shell.remote,org.apache.felix.fileinstall</jvm-options>
<jvm-options>--add-opens=java.base/java.lang=ALL-UNNAMED</jvm-options></java-config>
<network-config><network-listeners>
<network-listener name="http-listener-1" port="8080"/>
<network-listener name="http-listener-2" port="8181"/>
<network-listener name="admin-listener" port="4848"/>
</network-listeners><protocols><protocol name="http-listener-2"><http/><ssl cert-nickname="s1as"/></protocol></protocols></network-config>
<iiop-service><iiop-listener id="orb-listener-1"/></iiop-service>
<admin-service><jmx-connector name="system"/></admin-service>
<jms-service type="EMBEDDED"/>
<thread-pools><thread-pool name="http-thread-pool" max-thread-pool-size="200"/></thread-pools>
</config></configs></domain>'''


class NativePayaraConfiguration(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name, 'domain.xml')
        self.path.write_text(DOMAIN)

    def configure(self, https='26125', admin='26124'):
        env = {k: v for k, v in os.environ.items() if k not in ('_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS')}
        return subprocess.run([JAVA, '-Xmx128m', str(CONFIGURATOR), str(self.path), https, admin],
                              capture_output=True, text=True, env=env)

    def test_configures_first_boot_with_bounded_memory_and_https_only(self):
        result = self.configure()
        self.assertEqual(0, result.returncode, result.stderr)
        root = ET.parse(self.path).getroot()
        options = [node.text for node in root.findall('.//java-config/jvm-options')]
        self.assertIn('-Xmx256m', options)
        self.assertIn('-XX:MaxMetaspaceSize=192m', options)
        self.assertIn('-XX:+UseSerialGC', options)
        self.assertNotIn('-Xmx2g', options)
        self.assertNotIn('-XX:+UseG1GC', options)
        self.assertNotIn('-XX:MaxMetaspaceSize=128m', options)
        self.assertIn('--add-opens=java.base/java.lang=ALL-UNNAMED', options)
        self.assertIn('-Dorg.glassfish.additionalOSGiBundlesToStart=org.apache.felix.shell,org.apache.felix.fileinstall', options)
        listeners = {node.get('name'): node for node in root.findall('.//network-listener')}
        self.assertEqual('false', listeners['http-listener-1'].get('enabled'))
        self.assertEqual('26125', listeners['http-listener-2'].get('port'))
        self.assertEqual('127.0.0.1', listeners['http-listener-2'].get('address'))
        self.assertEqual('26124', listeners['admin-listener'].get('port'))
        self.assertEqual('127.0.0.1', listeners['admin-listener'].get('address'))
        self.assertEqual('true', root.find('.//protocol').get('security-enabled'))
        self.assertEqual('server', root.find('.//ssl').get('cert-nickname'))
        self.assertEqual('false', root.find('./hazelcast-runtime-configuration').get('enabled'))
        self.assertEqual('false', root.find('.//iiop-listener').get('enabled'))
        self.assertEqual('false', root.find('.//jmx-connector').get('enabled'))
        self.assertEqual('REMOTE', root.find('.//jms-service').get('type'))
        self.assertEqual('16', root.find('.//thread-pool').get('max-thread-pool-size'))

    def test_reconfiguration_does_not_duplicate_options(self):
        self.assertEqual(0, self.configure().returncode)
        self.assertEqual(0, self.configure().returncode)
        options = [node.text for node in ET.parse(self.path).findall('.//jvm-options')]
        self.assertEqual(1, options.count('-Xmx256m'))
        self.assertEqual(1, options.count('-XX:+UseSerialGC'))

    def test_accepts_a_domain_without_an_optional_jms_service(self):
        self.path.write_text(DOMAIN.replace('<jms-service type="EMBEDDED"/>', ''))
        result = self.configure()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIsNone(ET.parse(self.path).find('.//jms-service'))

    def test_invalid_ports_and_unexpected_xml_leave_the_original_untouched(self):
        for https, admin in [('80', '26124'), ('65536', '26124'), ('26125', '26125')]:
            self.assertNotEqual(0, self.configure(https, admin).returncode)
            self.assertEqual(DOMAIN, self.path.read_text())
        self.path.write_text('<unexpected/>')
        self.assertNotEqual(0, self.configure().returncode)
        self.assertEqual('<unexpected/>', self.path.read_text())


class NativeShutdown(unittest.TestCase):
    def test_stops_payara_even_when_wildfly_is_unavailable(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory)
            payara = app / 'payara/bin/asadmin'
            wildfly = app / 'wildfly/bin/jboss-cli.sh'
            payara.parent.mkdir(parents=True)
            wildfly.parent.mkdir(parents=True)
            # The external manager CLI cannot connect; the Payara stop still has work to do.
            wildfly.write_text('#!/bin/sh\nexit 7\n')
            payara.write_text('#!/bin/sh\nrm "$SOA_HOME/payara-running"\n')
            payara.chmod(0o700)
            wildfly.chmod(0o700)
            (app / 'payara-running').touch()
            config = (ROOT / 'deploy/helios/helios.env.example').read_text()
            config += f'\nPAYARA_HOME="{app}/payara"\nWILDFLY_HOME="{app}/wildfly"\n'
            (app / 'helios.env').write_text(config)
            env = dict(os.environ, SOA_HOME=str(app))
            result = subprocess.run(['sh', str(ROOT / 'deploy/helios/run.sh'), 'stop'],
                                    env=env, capture_output=True, text=True)
            self.assertFalse((app / 'payara-running').exists(), 'Payara must be stopped despite the manager CLI error')
            self.assertEqual(7, result.returncode, result.stderr)


class NativeDatabaseSettings(unittest.TestCase):
    """Both services share the account's only schema but never a Flyway history."""

    def launch(self, command):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory)
            payara = app / 'payara/bin/asadmin'
            wildfly = app / 'wildfly/bin/standalone.sh'
            seen = app / 'seen'
            dump = f'#!/bin/sh\necho "$DATABASE_URL $FLYWAY_HISTORY_TABLE" > "{seen}"\n'
            for launcher in (payara, wildfly):
                launcher.parent.mkdir(parents=True)
                launcher.write_text(dump)
                launcher.chmod(0o700)
            (app / 'wildfly/standalone/deployments').mkdir(parents=True)
            (app / 'artifacts').mkdir()
            (app / 'artifacts/orgmanager.war').touch()
            config = (ROOT / 'deploy/helios/helios.env.example').read_text()
            config += f'\nPAYARA_HOME="{app}/payara"\nWILDFLY_HOME="{app}/wildfly"\n'
            (app / 'helios.env').write_text(config)
            result = subprocess.run(['sh', str(ROOT / 'deploy/helios/run.sh'), command],
                                    env=dict(os.environ, SOA_HOME=str(app)), capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            return seen.read_text().split()

    def test_services_use_the_personal_schema_with_separate_histories(self):
        organizations_url, organizations_history = self.launch('organizations')
        manager_url, manager_history = self.launch('manager')
        self.assertIn('currentSchema=s409411&', organizations_url)
        self.assertIn('currentSchema=s409411&', manager_url)
        self.assertEqual('organizations_schema_history', organizations_history)
        self.assertEqual('manager_schema_history', manager_history)


class NativeUpdate(unittest.TestCase):
    """Updating replaces only project files and never reinstalls the servers."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.app = self.base / 'install'
        self.calls = self.app / 'calls.log'
        self.run_sh = self.app / 'deploy/helios/run.sh'
        self.run_sh.parent.mkdir(parents=True)
        for name in ('run.sh', 'ConfigurePayara.java', 'wildfly.cli'):
            shutil.copyfile(ROOT / 'deploy/helios' / name, self.app / 'deploy/helios' / name)
        for name, launcher in (('asadmin', 'runtimes/payara6/bin/asadmin'),
                               ('jboss-cli', 'runtimes/wildfly-35.0.1.Final/bin/jboss-cli.sh'),
                               ('standalone', 'runtimes/wildfly-35.0.1.Final/bin/standalone.sh')):
            path = self.app / launcher
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f'#!/bin/sh\necho "{name} $*" >> "{self.calls}"\n')
            path.chmod(0o700)
        (self.app / 'runtimes/wildfly-35.0.1.Final/standalone/deployments').mkdir(parents=True)
        (self.app / 'artifacts').mkdir()
        for war in WARS:
            (self.app / 'artifacts' / war).write_text('old ' + war)
        self.preserved = {'helios.env': (ROOT / 'deploy/helios/helios.env.example').read_text(),
                          'domains/organizations/config/domain.xml': 'domain',
                          'tls/organizations/server.p12': 'key',
                          'logs/old.log': 'log'}
        for name, content in self.preserved.items():
            path = self.app / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)

    def bundle(self, extra=(), without=(), wildfly_cli=None):
        files = {'artifacts/' + war: 'new ' + war for war in WARS}
        files['helios-native.md'] = 'new guide'
        files['deploy/helios/run.sh'] = (ROOT / 'deploy/helios/run.sh').read_text() + '# new version\n'
        files['deploy/helios/ConfigurePayara.java'] = (ROOT / 'deploy/helios/ConfigurePayara.java').read_text()
        files['deploy/helios/wildfly.cli'] = wildfly_cli or (ROOT / 'deploy/helios/wildfly.cli').read_text()
        files.update(extra)
        source = self.base / 'source'
        path = self.base / 'soa-lab2-native.tgz'
        with tarfile.open(path, 'w:gz') as archive:
            for name, content in files.items():
                if name not in without:
                    file = source / name
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_text(content)
                    archive.add(file, arcname=name)
        return path

    def update(self, *args):
        env = {k: v for k, v in os.environ.items() if k != 'SOA_HOME'}
        return subprocess.run(['sh', str(self.run_sh), 'update', *map(str, args)],
                              env=env, capture_output=True, text=True, timeout=60)

    def logged(self):
        return self.calls.read_text() if self.calls.exists() else ''

    def wait_for_manager(self):
        deadline = time.monotonic() + 15
        while 'standalone' not in self.logged() and time.monotonic() < deadline:
            time.sleep(0.05)

    def assert_nothing_changed(self, old_script):
        self.assertEqual(old_script, self.run_sh.read_text())
        for war in WARS:
            self.assertEqual('old ' + war, (self.app / 'artifacts' / war).read_text())
        self.assertEqual('', self.logged(), 'Servers must keep running after a rejected bundle')
        self.assertEqual([], list(self.app.glob('.update.*')))

    def test_replaces_project_files_keeps_server_state_and_restarts(self):
        payara = self.app / 'runtimes/payara6/bin/asadmin'
        launcher = payara.read_text()
        old_script = self.run_sh.stat().st_ino
        result = self.update(self.bundle())
        self.assertEqual(0, result.returncode, result.stderr)
        self.wait_for_manager()
        for war in WARS:
            self.assertEqual('new ' + war, (self.app / 'artifacts' / war).read_text())
        self.assertTrue(self.run_sh.read_text().endswith('# new version\n'))
        self.assertNotEqual(old_script, self.run_sh.stat().st_ino, 'A running script must not be rewritten in place')
        self.assertEqual(launcher, payara.read_text())
        for name, content in self.preserved.items():
            self.assertEqual(content, (self.app / name).read_text(), name)
        self.assertEqual('new orgmanager.war', (self.app / 'runtimes/wildfly-35.0.1.Final'
                                                / 'standalone/deployments/ROOT.war').read_text())
        order = [line.split()[0] + ' ' + next((word for word in line.split()[1:] if word in COMMANDS), '')
                 for line in self.logged().splitlines()]
        self.assertEqual(['jboss-cli --command=:shutdown', 'asadmin stop-domain', 'asadmin start-domain', 'asadmin deploy',
                          'asadmin deploy', 'standalone '], order)
        self.assertEqual([], list(self.app.glob('.update.*')))
        self.assertNotIn('configure', result.stdout)

    def test_rejects_a_bundle_that_would_touch_server_state(self):
        old_script = self.run_sh.read_text()
        for name in ('runtimes/payara6/bin/asadmin', 'helios.env', 'domains/organizations/config/domain.xml'):
            result = self.update(self.bundle(extra={name: 'overwritten'}))
            self.assertNotEqual(0, result.returncode)
            self.assertIn(name, result.stderr)
            self.assert_nothing_changed(old_script)
            shutil.rmtree(self.base / 'source')

    def test_rejects_incomplete_corrupt_or_missing_bundles_before_stopping(self):
        old_script = self.run_sh.read_text()
        incomplete = self.bundle(without={'artifacts/client.war'})
        corrupt = self.base / 'corrupt.tgz'
        corrupt.write_bytes(b'not an archive')
        for bundle in (incomplete, corrupt, self.base / 'missing.tgz'):
            self.assertNotEqual(0, self.update(bundle).returncode, bundle)
            self.assert_nothing_changed(old_script)
        self.assertNotEqual(0, self.update().returncode)
        self.assert_nothing_changed(old_script)

    def test_continues_when_servers_are_already_stopped(self):
        failing = '#!/bin/sh\necho "$0 $*" >> "%s"\ncase "$*" in *:shutdown*|*stop-domain*) exit 1 ;; esac\n' % self.calls
        for launcher in ('runtimes/payara6/bin/asadmin', 'runtimes/wildfly-35.0.1.Final/bin/jboss-cli.sh'):
            (self.app / launcher).write_text(failing)
        result = self.update(self.bundle())
        self.assertEqual(0, result.returncode, result.stderr)
        self.wait_for_manager()
        self.assertIn('start-domain', self.logged())
        self.assertEqual('new client.war', (self.app / 'artifacts/client.war').read_text())

    def test_manager_start_clears_stale_deployment_markers(self):
        # WildFly aborts its boot when .failed from an earlier deployment is beside .dodeploy.
        deployments = self.app / 'runtimes/wildfly-35.0.1.Final/standalone/deployments'
        for marker in ('failed', 'deployed', 'undeployed', 'isdeploying', 'pending'):
            (deployments / f'ROOT.war.{marker}').touch()
        (deployments / 'README.txt').write_text('unrelated')
        env = {k: v for k, v in os.environ.items() if k != 'SOA_HOME'}
        result = subprocess.run(['sh', str(self.run_sh), 'manager'], env=env, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({'README.txt', 'ROOT.war', 'ROOT.war.dodeploy'}, {path.name for path in deployments.iterdir()})
        self.assertEqual('old orgmanager.war', (deployments / 'ROOT.war').read_text())

    def test_announces_when_server_configuration_changed(self):
        result = self.update(self.bundle(wildfly_cli='# changed\n'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.wait_for_manager()
        self.assertIn('run.sh configure', result.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
