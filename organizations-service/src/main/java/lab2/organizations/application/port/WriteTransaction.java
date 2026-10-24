package lab2.organizations.application.port;

import java.util.function.Supplier;

public interface WriteTransaction {
    <T> T execute(Supplier<T> operation);
}
