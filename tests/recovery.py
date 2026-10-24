"""Seed an interrupted-operation fixture, then verify recovery after a real restart."""
import json
from pathlib import Path
import sys

from acceptance import Contracts

state_file = Path("/state/recovery.json")
client = Contracts()
client.setUp()

if sys.argv[1] == "prepare":
    target = client.organization("restart-target")
    source = client.organization("restart-source")
    moved = client.employee("already-transferred", source)
    pending = client.employee("pending-transfer", source)
    client.request("PUT", "/employees/" + moved,
                   f"<employee><name>already-transferred</name><organizationId>{target}</organizationId></employee>")
    state_file.write_text(json.dumps({"target": target, "source": source, "employees": [moved, pending]}))
    # Test fixture represents a committed snapshot plus one completed transfer.
    # Every interpolated value comes from server-generated numeric IDs.
    ids = json.dumps([int(moved), int(pending)])
    print(f"INSERT INTO acquisition_journal(source_id,acquirer_id,employee_ids) VALUES ({int(source)},{int(target)},'{ids}'::jsonb);")
elif sys.argv[1] == "verify":
    state = json.loads(state_file.read_text())
    target, source = state["target"], state["source"]
    client.organizations = [target, source]
    client.employees = state["employees"]
    try:
        result, _ = client.request("POST", f"/orgmanager/acquise/{target}/{source}", manager=True)
        client.assertEqual("2", result.findtext("employeeCount"))
        for id_, name in zip(state["employees"], ["already-transferred", "pending-transfer"]):
            employee, _ = client.request("GET", "/employees/" + id_)
            client.assertEqual(target, employee.findtext("organizationId"))
            client.assertEqual(name, employee.findtext("name"))
        client.request("GET", "/organizations/" + source, expected=404)
        client.request("POST", f"/orgmanager/acquise/{target}/{source}", expected=404, manager=True)
        print("Restart recovery passed: saved IDs resumed, completed transfer preserved, source removed, journal completed")
    finally:
        client.tearDown()
else:
    raise SystemExit("Expected prepare or verify")
