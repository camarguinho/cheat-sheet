package dev.cheatsheet.exceptions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ExceptionsReferenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExceptionsReferenceApplication.class, args);
    }
}
