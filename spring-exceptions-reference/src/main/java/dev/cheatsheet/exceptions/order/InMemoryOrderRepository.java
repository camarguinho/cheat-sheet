package dev.cheatsheet.exceptions.order;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
class InMemoryOrderRepository implements OrderRepository {

    private final Map<UUID, Order> storage = new ConcurrentHashMap<>();

    @Override
    public Optional<Order> findById(UUID id) {
        return Optional.ofNullable(storage.get(id));
    }

    @Override
    public List<Order> findByCustomerId(String customerId) {
        return storage.values().stream()
                .filter(order -> order.customerId().equals(customerId))
                .sorted(Comparator.comparing(Order::createdAt))
                .toList();
    }

    @Override
    public Order save(Order order) {
        storage.put(order.id(), order);
        return order;
    }
}
