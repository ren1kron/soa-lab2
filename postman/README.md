# SOA Lab 2 in Postman

Import [the collection](Lab2.postman_collection.json) and [the Helios environment](Helios.postman_environment.json) into Postman Desktop. Select **Helios** in the environment picker. The requests use the public HTTPS gateway. Postman verifies the university certificate using its usual trust settings.

| Variable | Default |
| --- | --- |
| `organizations_base_url` | `https://se.ifmo.ru/~s409411/soa/api.php/organizations` |
| `manager_base_url` | `https://se.ifmo.ru/~s409411/soa/api.php/manager` |

If you imported the older environment, update both active values in Postman. Substitute your own login when using another account. Public deployment and gateway checks are in the [native Helios guide](../docs/helios-native.md).

## Present the backend

1. Run **Preflight** to check pagination on Payara and the documented missing-organization error through WildFly. Both requests leave data intact. If the connection succeeds but the XML comparison fails, check that Helios has the build with the contract fixes. [Native Helios update instructions](../docs/helios-native.md#shutdown-updates-and-diagnosis)
2. In **Live contract errors**, send **400 · Invalid page and size**, **422 · Multiple organization violations**, and **406 · Unsupported Accept**. Open the response **Body** and **Test Results**. Under the request, open its saved **Contract** example to show the expected XML from the specification. Tests compare the status, media type, XML fields, Russian messages, and ordered details. XML indentation and empty-element syntax may vary.
3. In **Guided demo**, send the **Workflow** requests in order. The workflow creates its own organizations and employee, demonstrates both business conflicts and a rejected update, then shows the acquisition and transferred employee. You can also run **Guided demo** in Collection Runner for one iteration to include both **Workflow** and **Cleanup**. Enable **Keep variable values** so the IDs remain available if the run stops early.
4. If you sent the workflow manually, send the **Cleanup** requests in order. They use the IDs captured for this demo. A `204` means deletion succeeded, and a `404` means the fixture was already absent. When all three fixtures are gone, the demo variables are cleared.

The **400 · Delete by type with an invalid enum** request shows that endpoint on Helios. A successful delete-by-type can choose any organization of that type. Use an isolated deployment to demonstrate its `204` response.

If a run is interrupted, inspect the fixture IDs and `demo_prefix` in the active environment before sending another write. If a create response was lost, search for the unique prefix with `GET /organizations?filter=name:startsWith:<prefix>` or `GET /employees?filter=name:startsWith:<prefix>` and reconcile the IDs before cleanup. If acquisition failed after changes might have applied, inspect both organizations and the employee assignment before continuing. Start a fresh run after resolving the previous run's fixtures.
