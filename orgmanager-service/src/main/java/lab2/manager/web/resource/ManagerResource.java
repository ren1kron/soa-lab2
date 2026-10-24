package lab2.manager.web.resource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.*;
import lab2.manager.application.acquisition.AcquireOrganizationService;
import lab2.manager.application.hiring.HireEmployeeService;
import lab2.manager.generated.api.OrgmanagerApi;
import lab2.manager.generated.model.Headcount;
import lab2.manager.web.mapping.AcquisitionResponseMapper;
import lab2.manager.web.xml.ManagerResponseWriter;

@ApplicationScoped
@Path("/orgmanager")
public class ManagerResource implements OrgmanagerApi {
    @Inject HireEmployeeService hiring;
    @Inject AcquireOrganizationService acquisitions;

    @Override
    public Response managerHireEmployee(Long id, Long employeeId) {
        var result = new Headcount(id, hiring.hire(id, employeeId));
        return Response.ok(
                        ManagerResponseWriter.write(result, "headcount"),
                        MediaType.APPLICATION_XML_TYPE)
                .build();
    }

    @Override
    public Response managerAcquireOrganization(Long acquirerId, Long acquiredId) {
        var result =
                AcquisitionResponseMapper.response(acquisitions.acquire(acquirerId, acquiredId));
        return Response.ok(
                        ManagerResponseWriter.write(result, "acquisition"),
                        MediaType.APPLICATION_XML_TYPE)
                .build();
    }
}
