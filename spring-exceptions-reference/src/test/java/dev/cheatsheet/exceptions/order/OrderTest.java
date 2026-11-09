package dev.cheatsheet.exceptions.order;

import dev.cheatsheet.exceptions.shared.error.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class OrderTest {

    private static Order newOrder() {
        return Order.create("cust-1", List.of(new OrderItem("SKU", 2, new BigDecimal("10.00"))));
    }

    @Test
    void naoCancelaPedidoPago() {
        Order order = newOrder();
        order.markPaid("pay_1");

        // Teste o CÓDIGO, não a mensagem: a mensagem é para humanos e pode mudar.
        ConflictException ex = catchThrowableOfType(ConflictException.class, order::cancel);
        assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_INVALID_STATE);
        assertThat(ex.getDetails()).containsEntry("currentStatus", OrderStatus.PAID);
    }

    @Test
    void invarianteViolada_lancaExcecaoPadraoDoJdk() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderItem("SKU", 0, BigDecimal.ONE));
        assertThatIllegalArgumentException().isThrownBy(() -> Order.create("cust-1", List.of()));
    }
}
