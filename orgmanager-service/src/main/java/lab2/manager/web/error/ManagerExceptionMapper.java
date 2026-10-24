package lab2.manager.web.error;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import lab2.manager.application.error.ServiceException;
import lab2.manager.web.xml.ManagerResponseWriter;

@Provider
public class ManagerExceptionMapper implements ExceptionMapper<Exception> {
    public static Response response(RequestException error) {
        return Response.status(error.status)
                .type(MediaType.APPLICATION_XML_TYPE)
                .entity(ManagerResponseWriter.write(error.body(), "error"))
                .build();
    }

    public static Response response(ServiceException error) {
        return Response.status(ErrorResponseMapper.status(error))
                .type(MediaType.APPLICATION_XML_TYPE)
                .entity(ManagerResponseWriter.write(ErrorResponseMapper.body(error), "error"))
                .build();
    }

    @Override
    public Response toResponse(Exception e) {
        if (e instanceof ServiceException error) return response(error);
        if (e instanceof RequestException error) return response(error);
        if (e instanceof NotFoundException)
            return response(new RequestException(404, "NOT_FOUND", "Resource was not found"));
        if (e instanceof NotAcceptableException)
            return response(
                    new RequestException(406, "NOT_ACCEPTABLE", "Allow application/xml responses"));
        if (e instanceof BadRequestException)
            return response(new RequestException(400, "INVALID_REQUEST", "Invalid request"));
        if (e instanceof WebApplicationException web
                && web.getResponse().getStatus() >= 400
                && web.getResponse().getStatus() < 500) {
            int status = web.getResponse().getStatus();
            var result =
                    Response.fromResponse(
                            response(
                                    new RequestException(
                                            status,
                                            status == 405
                                                    ? "METHOD_NOT_ALLOWED"
                                                    : "INVALID_REQUEST",
                                            "Request does not match an available operation")));
            if (status == 405) result.allow(web.getResponse().getAllowedMethods());
            return result.build();
        }
        java.util.logging.Logger.getLogger(ManagerExceptionMapper.class.getName())
                .log(java.util.logging.Level.SEVERE, "Manager request failed", e);
        return response(
                new RequestException(
                        500,
                        "INTERNAL_ERROR",
                        "Request failed; check current state before repeating a change"));
    }
}
