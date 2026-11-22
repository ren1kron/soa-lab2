"""Exercise the published PHP gateway against two certificate-verified loopback APIs."""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Upstream(BaseHTTPRequestHandler):
    def respond(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        self.server.requests.append((self.command, self.path, self.headers.get('Content-Type'), body))
        status = {'GET': 200, 'POST': 201, 'PUT': 422, 'DELETE': 204}[self.command]
        content = b'' if status == 204 else b'<result/>'
        self.send_response(status)
        self.send_header('Content-Type', 'application/xml')
        self.send_header('Content-Length', str(len(content)))
        self.send_header('X-Total-Count', '9223372036854775807')
        self.send_header('X-Page', '1')
        self.send_header('X-Page-Size', '20')
        self.send_header('Retry-After', '2')
        self.end_headers()
        self.wfile.write(content)

    do_GET = do_POST = do_PUT = do_DELETE = respond

    def log_message(self, *args):
        pass


@unittest.skipUnless(shutil.which('php') and shutil.which('openssl'), 'requires PHP CLI and OpenSSL')
class PublicGateway(unittest.TestCase):
    def upstream(self, app, name):
        tls = app / 'tls' / name
        tls.mkdir(parents=True)
        cert, key = tls / 'server.crt', tls / 'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost',
                        '-keyout', str(key), '-out', str(cert)], check=True, capture_output=True)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        server.requests = []
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server

    def test_publication_round_trips_xml_and_rejects_untrusted_upstream(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory)
            orgs = self.upstream(app, 'organizations')
            manager = self.upstream(app, 'manager')
            shutil.copytree(ROOT / 'deploy/helios', app / 'deploy/helios')
            frontend = app / 'artifacts/frontend'
            (frontend / 'assets').mkdir(parents=True)
            (frontend / 'assets/app.js').write_text('app')
            (frontend / 'index.html').write_text('<script src="./assets/app.js"></script>')
            env = dict(os.environ, SOA_HOME=str(app), HOME=str(app), ORGANIZATIONS_PORT=str(orgs.server_port),
                       MANAGER_PORT=str(manager.server_port))
            subprocess.run(['sh', str(app / 'deploy/helios/publish.sh')], env=env, check=True, capture_output=True)
            site = app / 'public_html/soa'
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0))
                port = sock.getsockname()[1]
            php = subprocess.Popen(['php', '-S', f'127.0.0.1:{port}', '-t', str(site.parent)],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                deadline = time.monotonic() + 5
                while True:
                    try:
                        with socket.create_connection(('127.0.0.1', port), timeout=1):
                            break
                    except OSError:
                        if php.poll() is not None or time.monotonic() >= deadline:
                            self.fail('PHP web server did not start')
                        time.sleep(0.05)

                def request(method, path, body=None):
                    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
                    try:
                        connection.request(method, '/soa/' + path, body=body,
                                           headers={'Accept': 'application/xml', 'Content-Type': 'application/xml'})
                        response = connection.getresponse()
                        return response.status, response.headers, response.read()
                    finally:
                        connection.close()

                query = '/employees?sort=name&sort=id&filter=name%3Aeq%3A%D0%98'
                payload = '<employee><name>Иван</name></employee>'.encode()
                self.assertEqual(b'app', request('GET', 'assets/app.js')[2])
                for method, path, body, status in [('GET', query, None, 200), ('POST', '/employees', payload, 201),
                                                   ('PUT', '/employees/9', payload, 422), ('DELETE', '/employees/9', None, 204)]:
                    actual, headers, content = request(method, 'api.php/organizations' + path, body)
                    self.assertEqual(status, actual)
                    self.assertEqual(b'' if status == 204 else b'<result/>', content)
                    self.assertEqual('application/xml', headers.get_content_type())
                    self.assertEqual('9223372036854775807', headers['X-Total-Count'])
                    self.assertEqual('20', headers['X-Page-Size'])
                    self.assertEqual((method, path, 'application/xml', body or b''), orgs.requests[-1])
                hire = '/orgmanager/hire/9223372036854775807?employeeId=1'
                self.assertEqual(201, request('POST', 'api.php/manager' + hire)[0])
                self.assertEqual([('POST', hire, 'application/xml', b'')], manager.requests)
                self.assertEqual(404, request('GET', 'api.php/manager/employees')[0])
                shutil.copyfile(site / 'organizations.crt', site / 'manager.crt')
                self.assertEqual(502, request('POST', 'api.php/manager' + hire)[0])
                self.assertEqual(1, len(manager.requests))
                self.assertEqual(4, len(orgs.requests))
            finally:
                php.terminate()
                php.wait(timeout=5)
