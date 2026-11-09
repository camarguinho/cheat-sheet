package dev.cheatsheet.exceptions.order;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Invariantes do domínio usam exceções padrão do JDK ({@link IllegalArgumentException}).
 * Se uma delas chegar ao handler global, vira 500: significa que a validação da borda
 * deixou passar algo, ou seja, é um bug, não um erro do cliente (ver docs/07).
 */
public record OrderItem(String sku, int quantity, BigDecimal unitPrice) {

    public OrderItem {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity deve ser positiva: " + quantity);
        }
        if (unitPrice.signum() <= 0) {
            throw new IllegalArgumentException("unitPrice deve ser positivo: " + unitPrice);
        }
    }

    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
