package lab2.manager.config;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;
import lab2.manager.web.error.ManagerExceptionMapper;
import lab2.manager.web.resource.ManagerResource;
import lab2.manager.web.validation.ManagerRequestValidator;

@ApplicationPath("/")
public class RestApplication extends Application {
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(
                ManagerResource.class, ManagerRequestValidator.class, ManagerExceptionMapper.class);
    }
}
