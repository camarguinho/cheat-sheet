package dev.cheatsheet.exceptions.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório devolve {@link Optional}: "não achei" é um resultado normal para quem consulta.
 * Quem decide se a ausência é um erro (404) é o caso de uso, não o repositório (ver docs/02).
 */
public interface OrderRepository {

    Optional<Order> findById(UUID id);

    List<Order> findByCustomerId(String customerId);

    Order save(Order order);
}
