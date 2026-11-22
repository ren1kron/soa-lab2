# SOA Lab 2 in Postman

Import [the collection](Lab2.postman_collection.json) and [the Helios environment](Helios.postman_environment.json) into Postman Desktop. Select **Helios** in the environment picker. The requests use the native Helios deployment through the two local ports below.

| Variable | Default |
| --- | --- |
| `organizations_base_url` | `https://localhost:36125` |
| `manager_base_url` | `https://localhost:36126` |

## Open the tunnel and trust the servers

Keep the SSH tunnel open in a separate terminal. If your existing tunnel already exposes these ports, reuse it.

```sh
ssh -p 2222 -N -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:36125:127.0.0.1:26125 \
  -L 127.0.0.1:36126:127.0.0.1:26126 \
  s409411@se.ifmo.ru
```

Copy the two public server certificates from the Helios installation and join them into a PEM bundle on your computer.

```sh
mkdir -p .local/postman
scp -P 2222 s409411@se.ifmo.ru:soa/soa-lab2/soa-lab2-native/tls/organizations/server.crt \
  .local/postman/helios-organizations.crt
scp -P 2222 s409411@se.ifmo.ru:soa/soa-lab2/soa-lab2-native/tls/manager/server.crt \
  .local/postman/helios-manager.crt
cat .local/postman/helios-organizations.crt .local/postman/helios-manager.crt \
  > .local/postman/helios.pem
```

In **Settings → App settings → Certificates**, enable **CA certificates** and select the PEM file. Keep SSL certificate verification enabled. If you already use a custom CA bundle in Postman, combine its contents and the Helios certificates in a separate PEM file. [Postman certificate settings](https://learning.postman.com/docs/use/send-requests/authorization/certificates)

## Present the backend

1. Run **Preflight** to check pagination on Payara and the documented missing-organization error through WildFly. Both requests leave data intact. If the connection succeeds but the XML comparison fails, check that Helios has the build with the contract fixes. [Native Helios update instructions](../docs/helios-native.md#shutdown-updates-and-diagnosis)
2. In **Live contract errors**, send **400 · Invalid page and size**, **422 · Multiple organization violations**, and **406 · Unsupported Accept**. Open the response **Body** and **Test Results**. Under the request, open its saved **Contract** example to show the expected XML from the specification. Tests compare the status, media type, XML fields, Russian messages, and ordered details. XML indentation and empty-element syntax may vary.
3. In **Guided demo**, send the **Workflow** requests in order. The workflow creates its own organizations and employee, demonstrates both business conflicts and a rejected update, then shows the acquisition and transferred employee. You can also run **Guided demo** in Collection Runner for one iteration to include both **Workflow** and **Cleanup**. Enable **Keep variable values** so the IDs remain available if the run stops early.
4. If you sent the workflow manually, send the **Cleanup** requests in order. They use the IDs captured for this demo. A `204` means deletion succeeded, and a `404` means the fixture was already absent. When all three fixtures are gone, the demo variables are cleared.

The **400 · Delete by type with an invalid enum** request shows that endpoint on Helios. A successful delete-by-type can choose any organization of that type. Use an isolated deployment to demonstrate its `204` response.

If a run is interrupted, inspect the fixture IDs and `demo_prefix` in the active environment before sending another write. If a create response was lost, search for the unique prefix with `GET /organizations?filter=name:startsWith:<prefix>` or `GET /employees?filter=name:startsWith:<prefix>` and reconcile the IDs before cleanup. If acquisition failed after changes might have applied, inspect both organizations and the employee assignment before continuing. Start a fresh run after resolving the previous run's fixtures.
