package dev.cheatsheet.exceptions.order;

import dev.cheatsheet.exceptions.payment.PaymentGateway;
import dev.cheatsheet.exceptions.shared.error.BusinessRuleException;
import dev.cheatsheet.exceptions.shared.error.NotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Caso de uso. Repare no que <b>não</b> tem aqui: nenhum try/catch, nenhum HTTP status,
 * nenhum log de erro. O serviço lança exceções com significado e deixa o handler global
 * decidir protocolo, formato e log (ver docs/05).
 */
@Service
public class OrderService {

    static final BigDecimal MAX_ORDER_TOTAL = new BigDecimal("10000.00");

    private final OrderRepository repository;
    private final PaymentGateway paymentGateway;

    public OrderService(OrderRepository repository, PaymentGateway paymentGateway) {
        this.repository = repository;
        this.paymentGateway = paymentGateway;
    }

    public Order create(String customerId, List<OrderItem> items) {
        Order order = Order.create(customerId, items);
        BigDecimal total = order.total();
        if (total.compareTo(MAX_ORDER_TOTAL) > 0) {
            throw new BusinessRuleException(OrderErrorCode.ORDER_TOTAL_LIMIT_EXCEEDED,
                    Map.of("total", total, "limit", MAX_ORDER_TOTAL),
                    total, MAX_ORDER_TOTAL);
        }
        return repository.save(order);
    }

    public Order get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException(OrderErrorCode.ORDER_NOT_FOUND, id));
    }

    public List<Order> listByCustomer(String customerId) {
        // Lista vazia não é erro: é 200 com []. Só um recurso identificado que não existe é 404.
        return repository.findByCustomerId(customerId);
    }

    public Order pay(UUID id, String paymentToken) {
        Order order = get(id);
        order.ensureCanBePaid();              // falha rápido (409) ANTES de cobrar o cliente
        String paymentId = paymentGateway.charge(paymentToken, order.total());
        order.markPaid(paymentId);
        return repository.save(order);
    }

    public Order cancel(UUID id) {
        Order order = get(id);
        order.cancel();
        return repository.save(order);
    }
}
