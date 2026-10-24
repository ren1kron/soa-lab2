package lab2.organizations.web.xml;

import java.io.*;
import java.lang.reflect.Type;
import java.util.Collection;
import org.springframework.http.*;
import org.springframework.http.converter.AbstractGenericHttpMessageConverter;

public final class XmlMessageConverter extends AbstractGenericHttpMessageConverter<Object> {
    public XmlMessageConverter() {
        super(MediaType.APPLICATION_XML);
    }

    @Override
    protected boolean supports(Class<?> type) {
        return type.getName().startsWith("lab2.organizations.generated.model.")
                || Collection.class.isAssignableFrom(type);
    }

    @Override
    protected Object readInternal(Class<?> type, HttpInputMessage input) throws IOException {
        return XmlRequestReader.read(input.getBody().readAllBytes(), type);
    }

    @Override
    public Object read(Type type, Class<?> context, HttpInputMessage input) throws IOException {
        return readInternal((Class<?>) type, input);
    }

    @Override
    protected void writeInternal(Object value, Type type, HttpOutputMessage output)
            throws IOException {
        String root;
        if (value instanceof Collection<?> list) {
            boolean employee =
                    !list.isEmpty()
                            ? list.iterator().next().getClass().getSimpleName().equals("Employee")
                            : type != null && type.getTypeName().contains("Employee");
            root = employee ? "employees" : "organizations";
        } else
            root =
                    switch (value.getClass().getSimpleName()) {
                        case "Organization" -> "organization";
                        case "Employee" -> "employee";
                        case "Count" -> "countResult";
                        default -> "error";
                    };
        output.getBody().write(XmlResponseWriter.write(value, root));
    }
}
