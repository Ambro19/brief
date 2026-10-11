package net.onetechly.brief;

import net.onetechly.brief.config.DatabaseUrl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts the application.
 *
 * {@code @SpringBootApplication} tells Spring to scan this package and everything under it for
 * components (@RestController, @Service, repositories, ...) and to configure itself from what it
 * finds on the classpath: a web server because spring-web is there, a database connection because
 * a JDBC driver and Spring Data JPA are there.
 */
@SpringBootApplication
public class BriefApplication {
    public static void main(String[] args) {
        // A hosting platform hands over the database as one DATABASE_URL variable. Translate it
        // into the settings Spring reads, before Spring starts. Without that variable this does nothing.
        DatabaseUrl.apply(System.getenv(), System.getProperties());
        SpringApplication.run(BriefApplication.class, args);
    }
}
