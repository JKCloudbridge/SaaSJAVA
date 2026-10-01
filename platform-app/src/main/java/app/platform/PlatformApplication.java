package app.platform;

import java.time.ZoneOffset;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the modular monolith. Logical modules are the sub-packages of this package. */
@SpringBootApplication
public class PlatformApplication {

    public static void main(String[] args) {
        // The platform works in UTC everywhere (storage, logs, tests). Pinning it here keeps start-up independent
        // of the machine: some systems report legacy zone names that the database server refuses at connect time.
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
        SpringApplication.run(PlatformApplication.class, args);
    }
}
