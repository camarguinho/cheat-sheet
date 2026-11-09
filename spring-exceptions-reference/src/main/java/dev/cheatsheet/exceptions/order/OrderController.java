package dev.cheatsheet.exceptions.order;

import dev.cheatsheet.exceptions.order.OrderDtos.CreateOrderRequest;
import dev.cheatsheet.exceptions.order.OrderDtos.OrderResponse;
import dev.cheatsheet.exceptions.order.OrderDtos.PayOrderRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

/**
 * Controller fino: sem try/catch, sem montar resposta de erro. Toda exceção sobe para o
 * {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/orders")
class OrderController {

    private final OrderService service;

    OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        Order order = service.create(request.customerId(), request.toDomain());
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(order.id()).toUri();
        return ResponseEntity.created(location).body(OrderResponse.from(order));
    }

    /** {@code id} que não é UUID → 400 (type mismatch); UUID inexistente → 404. */
    @GetMapping("/{id}")
    OrderResponse get(@PathVariable UUID id) {
        return OrderResponse.from(service.get(id));
    }

    /** Constraint direto no parâmetro → validação de método (HandlerMethodValidationException). */
    @GetMapping
    List<OrderResponse> listByCustomer(@RequestParam @Size(min = 3, max = 40) String customerId) {
        return service.listByCustomer(customerId).stream().map(OrderResponse::from).toList();
    }

    @PostMapping("/{id}/payment")
    OrderResponse pay(@PathVariable UUID id, @Valid @RequestBody PayOrderRequest request) {
        return OrderResponse.from(service.pay(id, request.paymentToken()));
    }

    @PostMapping("/{id}/cancel")
    OrderResponse cancel(@PathVariable UUID id) {
        return OrderResponse.from(service.cancel(id));
    }
}
