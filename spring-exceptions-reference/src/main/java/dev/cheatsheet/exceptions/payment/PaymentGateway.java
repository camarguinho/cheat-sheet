package dev.cheatsheet.exceptions.payment;

import java.math.BigDecimal;

/**
 * Porta de saída para pagamentos. O contrato só lança exceções da própria aplicação:
 * <ul>
 *   <li>{@code BusinessRuleException(PAYMENT_DECLINED)} quando o pagamento é recusado;</li>
 *   <li>{@code IntegrationException} quando o provedor falha, demora ou está fora do ar.</li>
 * </ul>
 * Nenhuma exceção do SDK do provedor atravessa esta interface.
 */
public interface PaymentGateway {

    /** @return identificador do pagamento no provedor */
    String charge(String paymentToken, BigDecimal amount);
}
