package dev.cheatsheet.exceptions.shared.error;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Contrato de um código de erro: identificador estável, legível por máquina, que o cliente
 * usa para decidir o que fazer. Implemente com um {@code enum} por contexto (ex.: pedidos,
 * pagamentos) e registre no {@code ErrorCatalog}.
 *
 * <p>Regras de ouro:
 * <ul>
 *   <li>{@link #code()} é contrato público: depois de publicado, nunca muda de significado.</li>
 *   <li>{@link #messageTemplate()} é para humanos e pode mudar à vontade.</li>
 *   <li>A mensagem é exibida ao cliente; portanto nunca coloque dado sensível nos argumentos.</li>
 * </ul>
 */
public interface ErrorCode {

    /** Formato aceito: PREFIXO_NOME em maiúsculas, ex.: {@code ORDER_NOT_FOUND}. */
    Pattern FORMAT = Pattern.compile("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)+$");

    /** Identificador estável, ex.: {@code ORDER_NOT_FOUND}. */
    String code();

    /** Significado do erro, usado para derivar o status do protocolo. */
    ErrorCategory category();

    /** Título curto e fixo, igual para toda ocorrência desse código. */
    String title();

    /** Mensagem detalhada no formato {@link MessageFormat}, ex.: {@code "Pedido {0} não encontrado."}. */
    String messageTemplate();

    default String format(Object... args) {
        if (args == null || args.length == 0) {
            return messageTemplate();
        }
        // Números viram texto puro: MessageFormat aplicaria separador de milhar ("15,000").
        // Formatação por idioma é responsabilidade de i18n (MessageSource), não do código de erro.
        Object[] safeArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            safeArgs[i] = switch (args[i]) {
                case java.math.BigDecimal decimal -> decimal.toPlainString();
                case Number number -> number.toString();
                case null -> "null";
                default -> args[i];
            };
        }
        return new MessageFormat(messageTemplate(), Locale.ROOT).format(safeArgs);
    }
}
