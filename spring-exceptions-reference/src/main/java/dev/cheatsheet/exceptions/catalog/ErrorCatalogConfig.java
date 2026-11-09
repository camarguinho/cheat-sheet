package dev.cheatsheet.exceptions.catalog;

import dev.cheatsheet.exceptions.order.OrderErrorCode;
import dev.cheatsheet.exceptions.payment.PaymentErrorCode;
import dev.cheatsheet.exceptions.shared.error.CommonErrorCode;
import dev.cheatsheet.exceptions.shared.error.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
class ErrorCatalogConfig {

    /** Ao criar um novo enum de erros, registre-o aqui. O teste de catálogo garante a consistência. */
    @Bean
    ErrorCatalog errorCatalog() {
        List<ErrorCode> codes = new ArrayList<>();
        codes.addAll(List.of(CommonErrorCode.values()));
        codes.addAll(List.of(OrderErrorCode.values()));
        codes.addAll(List.of(PaymentErrorCode.values()));
        return new ErrorCatalog(codes);
    }
}
