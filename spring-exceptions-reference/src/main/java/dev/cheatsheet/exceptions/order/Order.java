package dev.cheatsheet.exceptions.order;

import dev.cheatsheet.exceptions.shared.error.ConflictException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Agregado de pedido. As transições de estado inválidas lançam {@link ConflictException}. */
public class Order {

    private final UUID id;
    private final String customerId;
    private final List<OrderItem> items;
    private final Instant createdAt;
    private OrderStatus status;
    private String paymentId;

    private Order(UUID id, String customerId, List<OrderItem> items, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.customerId = Objects.requireNonNull(customerId);
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Um pedido precisa de ao menos um item");
        }
        this.items = List.copyOf(items);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.status = OrderStatus.CREATED;
    }

    public static Order create(String customerId, List<OrderItem> items) {
        return new Order(UUID.randomUUID(), customerId, items, Instant.now());
    }

    public BigDecimal total() {
        return items.stream().map(OrderItem::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Valida, sem efeitos colaterais, que o pedido pode ser pago (útil antes de cobrar). */
    public void ensureCanBePaid() {
        requireStatus(OrderStatus.CREATED, "pagar");
    }

    public void markPaid(String paymentId) {
        ensureCanBePaid();
        this.paymentId = Objects.requireNonNull(paymentId);
        this.status = OrderStatus.PAID;
    }

    public void cancel() {
        requireStatus(OrderStatus.CREATED, "cancelar");
        this.status = OrderStatus.CANCELLED;
    }

    private void requireStatus(OrderStatus expected, String action) {
        if (status != expected) {
            throw new ConflictException(OrderErrorCode.ORDER_INVALID_STATE,
                    Map.of("currentStatus", status, "action", action),
                    id, status, action);
        }
    }

    public UUID id() { return id; }
    public String customerId() { return customerId; }
    public List<OrderItem> items() { return items; }
    public Instant createdAt() { return createdAt; }
    public OrderStatus status() { return status; }
    public String paymentId() { return paymentId; }
}
