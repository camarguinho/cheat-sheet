package dev.cheatsheet.exceptions.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTOs da borda. Bean Validation aqui = erro 400 com lista de campos (ver docs/07). */
public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(
            @NotBlank @Size(max = 40) String customerId,
            @NotEmpty @Size(max = 50) List<@Valid @NotNull ItemRequest> items) {

        List<OrderItem> toDomain() {
            return items.stream().map(ItemRequest::toDomain).toList();
        }
    }

    public record ItemRequest(
            @NotBlank @Size(max = 30) String sku,
            @NotNull @Positive @Max(1000) Integer quantity,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice) {

        OrderItem toDomain() {
            return new OrderItem(sku, quantity, unitPrice);
        }
    }

    public record PayOrderRequest(@NotBlank String paymentToken) {
    }

    public record OrderResponse(UUID id, String customerId, OrderStatus status, BigDecimal total,
                                String paymentId, Instant createdAt, List<ItemResponse> items) {

        static OrderResponse from(Order order) {
            return new OrderResponse(order.id(), order.customerId(), order.status(), order.total(),
                    order.paymentId(), order.createdAt(),
                    order.items().stream().map(i -> new ItemResponse(i.sku(), i.quantity(), i.unitPrice())).toList());
        }
    }

    public record ItemResponse(String sku, int quantity, BigDecimal unitPrice) {
    }
}
