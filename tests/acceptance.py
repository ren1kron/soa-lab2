"""Black-box contract checks against real Payara and WildFly over verified TLS."""
import http.client
import os
import socket
import ssl
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ORG = os.getenv("ORGANIZATIONS_URL", "https://organizations-service:8181")
MANAGER = os.getenv("MANAGER_URL", "https://orgmanager-service:8443")
ORG_TLS = ssl.create_default_context(cafile="/tls/organizations/server.crt")
MANAGER_TLS = ssl.create_default_context(cafile="/tls/manager/server.crt")


class Contracts(unittest.TestCase):
    def setUp(self):
        self.organizations = []
        self.employees = []
        self.prefix = "acceptance-" + uuid.uuid4().hex

    def request(self, method, path, body=None, expected=200, manager=False, headers=None):
        base, tls = (MANAGER, MANAGER_TLS) if manager else (ORG, ORG_TLS)
        request_headers = {"Accept": "application/xml"}
        if body is not None:
            request_headers["Content-Type"] = "application/xml"
        request_headers.update(headers or {})
        req = urllib.request.Request(base + path, data=None if body is None else body.encode(), method=method, headers=request_headers)
        try:
            response = urllib.request.urlopen(req, context=tls, timeout=30)
        except urllib.error.HTTPError as e:
            response = e
        with response:
            data, status, metadata = response.read(), response.status, response.headers
        self.assertEqual(expected, status, (method, path, data.decode(errors="replace")))
        if status == 204:
            self.assertFalse(data)
            return None, metadata
        self.assertEqual("application/xml", metadata.get_content_type(), (method, path, status, data.decode(errors="replace")))
        return ET.fromstring(data), metadata

    def organization(self, suffix, kind="PUBLIC"):
        root, _ = self.request("POST", "/organizations", f"<organization><name>{self.prefix}-{suffix}</name><coordinates><x>9223372036854775807</x><y>-2147483648</y></coordinates><annualTurnover>2.5</annualTurnover><type>{kind}</type><officialAddress><zipCode/><town><x>-9223372036854775808</x><y>0</y><name/></town></officialAddress></organization>", 201)
        id_ = root.findtext("id")
        self.organizations.append(id_)
        self.assertEqual("9223372036854775807", root.findtext("coordinates/x"))
        return id_

    def employee(self, name, organization=None):
        assignment = "" if organization is None else f"<organizationId>{organization}</organizationId>"
        root, _ = self.request("POST", "/employees", f"<employee><name>{name}</name>{assignment}</employee>", 201)
        id_ = root.findtext("id")
        self.employees.append(id_)
        return id_

    def tearDown(self):
        for id_ in self.employees:
            try:
                self.request("DELETE", "/employees/" + id_, expected=204)
            except AssertionError:
                pass  # A scenario can already have deleted its own fixture.
        for id_ in self.organizations:
            try:
                self.request("DELETE", "/organizations/" + id_, expected=204)
            except AssertionError:
                pass

    def test_all_fourteen_operations(self):
        a = self.organization("a")
        b = self.organization("b", "TRUST")
        e = self.employee(" original ")
        before, _ = self.request("GET", "/organizations/" + a)
        after, _ = self.request("PUT", "/organizations/" + a, f"<organization><name>{self.prefix}-renamed</name><coordinates><x>1</x><y>2</y></coordinates><type>PUBLIC</type></organization>")
        self.assertEqual(before.findtext("creationDate"), after.findtext("creationDate"))
        self.assertIsNone(after.find("annualTurnover"))
        query = urllib.parse.urlencode({"filter": f"name:contains:{self.prefix}", "sort": "-name", "page": 1, "size": 1})
        page, metadata = self.request("GET", "/organizations?" + query)
        self.assertEqual("organizations", page.tag)
        self.assertEqual("2", metadata["X-Total-Count"])
        self.assertEqual("1", metadata["X-Page-Size"])
        page, metadata = self.request("GET", "/organizations?page=999999999999999999999999")
        self.assertEqual(0, len(page))
        self.assertEqual("999999999999999999999999", metadata["X-Page"])
        self.request("GET", "/employees/" + e)
        self.request("PUT", "/employees/" + e, "<employee><name> changed </name></employee>")
        hired, _ = self.request("POST", f"/orgmanager/hire/{a}?employeeId={e}", manager=True)
        self.assertEqual("headcount", hired.tag)
        self.assertEqual("1", hired.findtext("employeeCount"))
        self.request("POST", f"/orgmanager/hire/{a}?employeeId={e}", manager=True)
        self.request("POST", f"/orgmanager/hire/{b}?employeeId={e}", expected=409, manager=True)
        self.request("DELETE", "/organizations/" + a, expected=409)
        workforce, metadata = self.request("GET", "/employees?" + urllib.parse.urlencode({"filter": f"organizationId:eq:{a}", "sort": "-name", "page": 1, "size": 1}))
        self.assertEqual("employees", workforce.tag)
        self.assertEqual("1", metadata["X-Total-Count"])
        self.assertEqual(" changed ", workforce[0].findtext("name"))
        self.employee("source-worker", b)
        acquired, _ = self.request("POST", f"/orgmanager/acquise/{a}/{b}", manager=True)
        self.assertEqual("acquisition", acquired.tag)
        self.assertEqual("2", acquired.findtext("employeeCount"))
        self.assertEqual(a, acquired.findtext("organization/id"))
        self.request("GET", "/organizations/" + b, expected=404)
        self.request("POST", f"/orgmanager/acquise/{a}/{b}", expected=404, manager=True)
        count, _ = self.request("GET", "/organizations/count?typeGreaterThan=PUBLIC")
        self.assertEqual("countResult", count.tag)
        self.assertGreaterEqual(int(count.findtext("count")), 0)
        government = self.organization("delete-by-type", "GOVERNMENT")
        matches, _ = self.request("GET", "/organizations?filter=type:eq:GOVERNMENT")
        # Arbitrary-match deletion must not delete another user's data.
        if len(matches) == 1 and matches[0].findtext("id") == government:
            self.request("DELETE", "/organizations/type/GOVERNMENT", expected=204)
            self.request("GET", "/organizations/" + government, expected=404)
        else:
            self.fail("Run acceptance in a fresh test deployment to exercise delete-by-type safely")
        self.request("DELETE", "/employees/" + e, expected=204)

    def test_acquisition_reads_all_employee_pages(self):
        a = self.organization("acquirer")
        b = self.organization("source")
        ids = [self.employee("worker-" + str(i), b) for i in range(105)]
        acquired, _ = self.request("POST", f"/orgmanager/acquise/{a}/{b}", manager=True)
        self.assertEqual("105", acquired.findtext("employeeCount"))
        for id_ in (ids[0], ids[99], ids[104]):
            employee, _ = self.request("GET", "/employees/" + id_)
            self.assertEqual(a, employee.findtext("organizationId"))

    def test_empty_collection_and_empty_acquisition(self):
        a = self.organization("empty-acquirer")
        b = self.organization("empty-source")
        employees, metadata = self.request("GET", "/employees?filter=organizationId:eq:" + b)
        self.assertEqual("employees", employees.tag)
        self.assertEqual(0, len(employees))
        self.assertEqual("0", metadata["X-Total-Count"])
        result, _ = self.request("POST", f"/orgmanager/acquise/{a}/{b}", manager=True)
        self.assertEqual("0", result.findtext("employeeCount"))
        self.request("GET", "/organizations/" + b, expected=404)

    def test_validation_and_xml_errors(self):
        cases = [
            ("GET", "/organizations?sort=name,-name", None, 400),
            ("GET", "/organizations?sort=id&sort=name", None, 400),
            ("GET", "/organizations?filter=creationDate:eq:2026-02-30", None, 400),
            ("GET", "/employees?size=101", None, 400),
            ("GET", "/employees/9223372036854775808", None, 400),
            ("GET", "/unknown", None, 404),
            ("POST", "/employees", "<employee>", 400),
            ("POST", "/employees", "<wrong><name>x</name></wrong>", 422),
            ("POST", "/employees", "<employee><id>1</id><name/></employee>", 422),
            ("GET", "/organizations", "unexpected", 400),
        ]
        for method, path, body, status in cases:
            error, _ = self.request(method, path, body, status)
            self.assertEqual("error", error.tag)
            self.assertIsNotNone(error.find("details"))
        for path in ("/orgmanager/hire/0?employeeId=1", "/orgmanager/hire/1", "/orgmanager/hire/1?employeeId=1&employeeId=2", "/orgmanager/acquise/1/2?unknown=1"):
            error, _ = self.request("POST", path, expected=400, manager=True)
            self.assertGreater(len(error.find("details")), 0)
        self.request("POST", "/orgmanager/acquise/1/1", expected=409, manager=True)
        self.request("POST", "/orgmanager/hire/1?employeeId=1", "x", 400, True)

    def test_tls_and_disabled_http_listeners(self):
        for host, port in (("organizations-service", 8080), ("orgmanager-service", 8080)):
            with self.assertRaises(OSError):
                with socket.create_connection((host, port), timeout=2):
                    pass
        for base in (ORG, MANAGER):
            url = urllib.parse.urlsplit(base)
            connection = http.client.HTTPConnection(url.hostname, url.port, timeout=3)
            try:
                with self.assertRaises((OSError, http.client.HTTPException)):
                    connection.request("GET", "/organizations")
                    connection.getresponse()
            finally:
                connection.close()
        with self.assertRaises(urllib.error.URLError):
            urllib.request.urlopen(ORG + "/organizations", context=ssl.create_default_context(), timeout=5)


if __name__ == "__main__":
    unittest.main(verbosity=2)
