package lab2.organizations.web.error;

import lab2.organizations.application.error.ServiceException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class ApiExceptionHandler {
    @ExceptionHandler(RequestException.class)
    public ResponseEntity<lab2.organizations.generated.model.Error> api(RequestException e) {
        return ResponseEntity.status(e.status)
                .contentType(MediaType.APPLICATION_XML)
                .body(e.body());
    }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<lab2.organizations.generated.model.Error> service(
            ServiceException error) {
        return ResponseEntity.status(ErrorResponseMapper.status(error))
                .contentType(MediaType.APPLICATION_XML)
                .body(ErrorResponseMapper.body(error));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<lab2.organizations.generated.model.Error> binding(
            MethodArgumentTypeMismatchException e) {
        return api(
                RequestException.invalid(
                        "path",
                        e.getName(),
                        "INVALID_TYPE",
                        "Value does not match the declared type"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<lab2.organizations.generated.model.Error> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse error
                && error.getStatusCode().is4xxClientError()) {
            int status = error.getStatusCode().value();
            String code =
                    switch (status) {
                        case 404 -> "NOT_FOUND";
                        case 405 -> "METHOD_NOT_ALLOWED";
                        case 406 -> "NOT_ACCEPTABLE";
                        case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                        default -> "INVALID_REQUEST";
                    };
            return ResponseEntity.status(status)
                    .headers(error.getHeaders())
                    .contentType(MediaType.APPLICATION_XML)
                    .body(
                            new RequestException(
                                            status,
                                            code,
                                            "Request does not match an available operation")
                                    .body());
        }
        java.util.logging.Logger.getLogger(ApiExceptionHandler.class.getName())
                .log(java.util.logging.Level.SEVERE, "Request failed", e);
        return api(
                new RequestException(
                        500,
                        "INTERNAL_ERROR",
                        "Request failed; check current state before repeating a change"));
    }
}
