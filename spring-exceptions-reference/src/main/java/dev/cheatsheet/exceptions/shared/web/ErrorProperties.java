package dev.cheatsheet.exceptions.shared.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

/**
 * @param typeBaseUri prefixo do campo {@code type} dos Problem Details. Em produção, use uma
 *                    URL absoluta e estável da sua documentação, ex.: https://api.suaempresa.com/errors/
 */
@ConfigurationProperties("app.errors")
public record ErrorProperties(@DefaultValue("/errors/") String typeBaseUri) {

    public URI typeFor(String code) {
        return URI.create(typeBaseUri + code);
    }
}
