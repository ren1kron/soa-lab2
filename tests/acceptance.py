"""Black-box contract checks against real Payara and WildFly over verified TLS."""
import http.client
import os
import re
import socket
import ssl
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

ORG = os.getenv("ORGANIZATIONS_URL", "https://organizations-service:8181")
MANAGER = os.getenv("MANAGER_URL", "https://orgmanager-service:8443")
ORG_TLS = ssl.create_default_context(cafile="/tls/organizations/server.crt")
MANAGER_TLS = ssl.create_default_context(cafile="/tls/manager/server.crt")
CONTRACTS = Path("/contracts")


def error_example(name, manager):
    contract = CONTRACTS / ("openapi-manager.yaml" if manager else "openapi.yaml")
    # Component examples in these contracts use literal XML blocks.
    examples = contract.read_text(encoding="utf-8").split("\n  examples:\n", 1)[1]
    match = re.search(rf"^    {re.escape(name)}:\n      summary: [^\n]*\n      value: \|\n((?:        [^\n]*\n)+)", examples, re.M)
    if match is None:
        raise AssertionError(f"Missing contract example {name} in {contract}")
    return ET.fromstring(match.group(1).strip())


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

    def assert_xml_equal(self, expected, actual):
        self.assertEqual(expected.tag, actual.tag)
        self.assertEqual(expected.attrib, actual.attrib)
        self.assertEqual(len(expected), len(actual), ET.tostring(actual, encoding="unicode"))
        if len(expected):
            self.assertFalse((actual.text or "").strip())
            for expected_child, actual_child in zip(expected, actual):
                self.assert_xml_equal(expected_child, actual_child)
                self.assertFalse((actual_child.tail or "").strip())
        else:
            self.assertEqual(expected.text, actual.text)

    def assert_error_example(self, name, status, method, path, body=None, manager=False, headers=None):
        with self.subTest(example=name, path=path, manager=manager):
            actual, _ = self.request(method, path, body, status, manager, headers)
            self.assert_xml_equal(error_example(name, manager), actual)

    def organization(self, suffix, kind="PUBLIC"):
        root, _ = self.request("POST", "/organizations", f"<organization><name>{self.prefix}-{suffix}</name><coordinates><x>9223372036854775807</x><y>-2147483648</y></coordinates><annualTurnover>2.5</annualTurnover><type>{kind}</type><officialAddress><zipCode/><town><x>-9223372036854775808</x><y>0</y><name/></town></officialAddress></organization>", 201)
        id_ = root.findtext("id")
        self.organizations.append(id_)
        self.assertEqual("9223372036854775807", root.findtext("coordinates/x"))
        self.assertEqual("-2147483648", root.findtext("coordinates/y"))
        self.assertEqual("", root.findtext("officialAddress/zipCode"))
        self.assertEqual("-9223372036854775808", root.findtext("officialAddress/town/x"))
        self.assertEqual("", root.findtext("officialAddress/town/name"))
        self.assertEqual("0", root.findtext("employeeCount"))
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
        self.assertEqual("1", metadata["X-Page"])
        self.assertEqual("1", metadata["X-Page-Size"])
        self.assertEqual("0", page[0].findtext("employeeCount"))
        page, metadata = self.request("GET", "/organizations?page=999999999999999999999999")
        self.assertEqual(0, len(page))
        self.assertEqual("999999999999999999999999", metadata["X-Page"])
        self.request("GET", "/employees/" + e)
        self.request("PUT", "/employees/" + e, "<employee><name> changed </name></employee>")
        hired, _ = self.request("POST", f"/orgmanager/hire/{a}?employeeId={e}", manager=True)
        self.assertEqual("headcount", hired.tag)
        self.assertEqual(a, hired.findtext("organizationId"))
        self.assertEqual("1", hired.findtext("employeeCount"))
        self.request("POST", f"/orgmanager/hire/{a}?employeeId={e}", manager=True)
        self.request("POST", f"/orgmanager/hire/{b}?employeeId={e}", expected=409, manager=True)
        self.request("DELETE", "/organizations/" + a, expected=409)
        workforce, metadata = self.request("GET", "/employees?" + urllib.parse.urlencode({"filter": f"organizationId:eq:{a}", "sort": "-name", "page": 1, "size": 1}))
        self.assertEqual("employees", workforce.tag)
        self.assertEqual("1", metadata["X-Total-Count"])
        self.assertEqual("1", metadata["X-Page"])
        self.assertEqual("1", metadata["X-Page-Size"])
        self.assertEqual(" changed ", workforce[0].findtext("name"))
        self.employee("source-worker", b)
        acquired, _ = self.request("POST", f"/orgmanager/acquise/{a}/{b}", manager=True)
        self.assertEqual("acquisition", acquired.tag)
        self.assertEqual(b, acquired.findtext("acquiredId"))
        self.assertEqual("2", acquired.findtext("employeeCount"))
        self.assertEqual("2", acquired.findtext("organization/employeeCount"))
        self.assertEqual(a, acquired.findtext("organization/id"))
        organizations, metadata = self.request("GET", "/organizations?" + urllib.parse.urlencode({
            "filter": f"name:contains:{self.prefix},employeeCount:gte:1", "sort": "-employeeCount", "size": 1,
        }))
        self.assertEqual("1", metadata["X-Total-Count"])
        self.assertEqual(a, organizations[0].findtext("id"))
        self.assertEqual("2", organizations[0].findtext("employeeCount"))
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
        self.assertEqual("0", result.findtext("organization/employeeCount"))
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

    def test_organizations_error_examples(self):
        org = "<organization><name>ok</name><coordinates><x>1</x><y>1</y></coordinates>{}</organization>"
        employee = "<employee><name>ok</name>{}</employee>"
        for name, path in (
            ("unknownQueryParameter", "/organizations?unexpected=1"),
            ("unknownSortField", "/organizations?sort=name,salary"),
            ("malformedSort", "/organizations?sort=--name"),
            ("emptySortTerm", "/organizations?sort=name,,id"),
            ("duplicateSort", "/organizations?sort=name,name"),
            ("conflictingSort", "/organizations?sort=name,-name"),
            ("invalidPagination", "/organizations?page=0&size=101"),
            ("invalidFilter", "/organizations?filter=name:gt:x"),
            ("malformedFilter", "/organizations?filter=name:eq"),
            ("invalidFilterValue", "/organizations?filter=id:eq:abc"),
            ("repeatedSort", "/organizations?sort=name&sort=-id"),
            ("invalid_id", "/organizations/abc"),
            ("invalid_employeeId", "/employees/abc"),
            ("invalid_typeGreaterThan", "/organizations/count?typeGreaterThan=abc"),
            ("missing_typeGreaterThan", "/organizations/count"),
        ):
            self.assert_error_example(name, 400, "GET", path)
        self.assert_error_example("invalid_type", 400, "DELETE", "/organizations/type/abc")
        self.assert_error_example("unexpectedBody", 400, "GET", "/organizations", "x")
        self.assert_error_example("missingBody", 400, "POST", "/organizations")
        self.assert_error_example("missingBody2", 400, "POST", "/employees")
        self.assert_error_example("malformedXml", 400, "POST", "/employees", "<employee>")
        for name, path, body in (
            ("multipleViolations", "/organizations", "<organization><name/><coordinates><y>1</y></coordinates><annualTurnover>0</annualTurnover></organization>"),
            ("invalidNestedField", "/organizations", org.format("<officialAddress><zipCode/><town><x>1.5</x><y>1</y><name/></town></officialAddress>")),
            ("invalidEnum", "/organizations", org.format("<type>public</type>")),
            ("readOnlyField", "/organizations", org.format("<id>1</id>")),
            ("unknownElement", "/organizations", org.format("<nickname>x</nickname>")),
            ("duplicateElement", "/organizations", org.format("<name>x</name>")),
            ("wrongRoot", "/organizations", "<wrong/>"),
            ("invalidEmployee", "/employees", "<employee><name/><organizationId>0</organizationId></employee>"),
            ("readOnlyField", "/employees", employee.format("<id>1</id>")),
            ("unknownElement", "/employees", employee.format("<nickname>x</nickname>")),
            ("duplicateElement", "/employees", employee.format("<name>x</name>")),
            ("wrongRoot2", "/employees", "<wrong/>"),
        ):
            self.assert_error_example(name, 422, "POST", path, body)
        self.assert_error_example("unsupportedAccept", 406, "GET", "/employees", headers={"Accept": "application/json"})
        self.assert_error_example("unsupportedContentType", 415, "POST", "/employees", "{}", headers={"Content-Type": "application/json"})
        self.assert_error_example("unsupportedContentEncoding", 415, "POST", "/employees", "x", headers={"Content-Encoding": "gzip"})

        a = self.organization("contract-errors")
        e = self.employee("assigned", a)
        self.assert_error_example("nonemptyOrganization", 409, "DELETE", f"/organizations/{a}")
        self.assert_error_example("missingOrganization", 404, "GET", "/organizations/9223372036854775807")
        self.assert_error_example("invalidEmployee", 422, "PUT", f"/employees/{e}", "<employee><name/><organizationId>0</organizationId></employee>")
        current, _ = self.request("GET", f"/employees/{e}")
        self.assertEqual("assigned", current.findtext("name"))
        self.assertEqual(a, current.findtext("organizationId"))

    def test_manager_error_examples(self):
        for name, path in (
            ("unknownQueryParameter", "/orgmanager/hire/1?employeeId=1&unexpected=1"),
            ("invalid_id", "/orgmanager/hire/abc?employeeId=1"),
            ("invalid_employeeId", "/orgmanager/hire/1?employeeId=abc"),
            ("missing_employeeId", "/orgmanager/hire/1"),
            ("invalid_acquirerId", "/orgmanager/acquise/abc/1"),
            ("invalid_acquiredId", "/orgmanager/acquise/1/abc"),
        ):
            self.assert_error_example(name, 400, "POST", path, manager=True)
        self.assert_error_example("unexpectedBody", 400, "POST", "/orgmanager/hire/1?employeeId=1", "x", True)
        self.assert_error_example("unsupportedAccept", 406, "POST", "/orgmanager/hire/1?employeeId=1", manager=True, headers={"Accept": "application/json"})
        self.assert_error_example("selfAcquisition", 409, "POST", "/orgmanager/acquise/1/1", manager=True)
        self.assert_error_example("missingOrganization", 404, "POST", "/orgmanager/hire/9223372036854775807?employeeId=1", manager=True)
        a = self.organization("contract-hire")
        b = self.organization("contract-employer")
        e = self.employee("assigned", b)
        self.assert_error_example("employeeAlreadyAssigned", 409, "POST", f"/orgmanager/hire/{a}?employeeId={e}", manager=True)
        employee, _ = self.request("GET", f"/employees/{e}")
        self.assertEqual(b, employee.findtext("organizationId"))

    def test_xml_text_round_trips_after_hiring_and_acquisition(self):
        name = " a\r&<б "
        xml_text = " a&#13;&amp;&lt;б "
        root, _ = self.request("POST", "/employees", f"<employee><name>{xml_text}</name></employee>", 201)
        e = root.findtext("id")
        self.employees.append(e)
        self.assertEqual(name, root.findtext("name"))
        root, _ = self.request("POST", "/organizations", f"<organization><name>{xml_text}</name><coordinates><x>1</x><y>0</y></coordinates></organization>", 201)
        a = root.findtext("id")
        self.organizations.append(a)
        self.assertEqual(name, root.findtext("name"))
        b = self.organization("contract-acquire")
        self.request("POST", f"/orgmanager/hire/{b}?employeeId={e}", manager=True)
        result, _ = self.request("POST", f"/orgmanager/acquise/{a}/{b}", manager=True)
        self.assertEqual(name, result.findtext("organization/name"))
        employee, _ = self.request("GET", f"/employees/{e}")
        self.assertEqual(name, employee.findtext("name"))
        self.assertEqual(a, employee.findtext("organizationId"))

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
