# 03 — Error codes

[← Desenhando exceções](02-desenhando-excecoes-da-aplicacao.md) · [Índice](README.md) · [Próximo: HTTP status codes →](04-http-status-codes.md)

---

## 3.1 Por que o HTTP status não basta

Um app recebe `422 Unprocessable Content` ao tentar pagar. O que ele mostra ao usuário?

- "Cartão recusado, tente outro"?
- "Limite do pedido excedido"?
- "Endereço fora da área de entrega"?

O status diz a **família** do problema; não diz **qual** problema. E a mensagem (`detail`) é
texto para humanos: muda com tradução, com ajuste de redação, com teste A/B. **Ninguém deveria
fazer `if (detail.contains("recusado"))`.**

O **error code** é o identificador estável, legível por máquina, que o cliente usa para decidir.

```
           ┌───────────────┬───────────────────────────┬────────────────────────────┐
           │  Para quem    │  Estabilidade             │  Exemplo                   │
┌──────────┼───────────────┼───────────────────────────┼────────────────────────────┤
│ status   │ HTTP/proxies  │ fixo pelo protocolo       │ 422                        │
│ code     │ código cliente│ contrato: nunca muda      │ PAYMENT_DECLINED           │
│ title    │ humano        │ fixo por code             │ "Pagamento recusado"       │
│ detail   │ humano        │ varia por ocorrência      │ "O pagamento foi recusado…"│
└──────────┴───────────────┴───────────────────────────┴────────────────────────────┘
```

## 3.2 Três camadas: code → categoria → status

O projeto separa três conceitos que costumam ser misturados:

| Camada | Exemplo | Quem define | Muda quando |
|---|---|---|---|
| **Code** | `ORDER_INVALID_STATE` | Cada contexto de negócio | Nunca (só se cria um novo) |
| **Categoria** | `CONFLICT` | Modelo de erro compartilhado | Raramente |
| **Status** | `409` | `HttpStatusMapper` (borda web) | Decisão de protocolo |

Vários codes compartilham uma categoria. A categoria determina o status. O cliente decide pelo code e,
se não conhece o code, pelo status (ver 3.6).

## 3.3 Formato do código

| Estilo | Exemplo | Prós | Contras |
|---|---|---|---|
| Numérico | `E1023`, `40401` | Curto, "parece sério" | Ilegível; exige tabela para entender; tende a codificar o status no número |
| Semântico | `ORDER_NOT_FOUND`, `card_declined` | Autoexplicativo no log e no código do cliente | Mais longo |
| Hierárquico | `order.payment.declined` | Agrupa por domínio | Tentação de parsear a string |

**Recomendação:** semântico, com **prefixo do contexto** (`ORDER_`, `PAYMENT_`), em `UPPER_SNAKE_CASE`.
APIs públicas maduras (Stripe, por exemplo) usam códigos semânticos legíveis.

O projeto valida o formato com uma regex (`ErrorCode.FORMAT`) no startup.

## 3.4 Implementação: enum por contexto + interface

```java
public interface ErrorCode {
    String code();               // "ORDER_NOT_FOUND"  ← contrato
    ErrorCategory category();    // NOT_FOUND          ← vira 404 na web
    String title();              // "Pedido não encontrado"
    String messageTemplate();    // "O pedido {0} não foi encontrado."
}

public enum OrderErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(NOT_FOUND, "Pedido não encontrado", "O pedido {0} não foi encontrado."),
    ORDER_TOTAL_LIMIT_EXCEEDED(BUSINESS_RULE, "Limite do pedido excedido",
            "O total do pedido ({0}) excede o limite permitido de {1}."),
    ORDER_INVALID_STATE(CONFLICT, "Operação incompatível com o estado do pedido",
            "Não é possível {2} o pedido {0}: o status atual é {1}.");
    // ...
}
```

Por que **um enum por contexto** em vez de um enum gigante?

- Cada módulo é dono dos seus erros (coesão; *package by feature*).
- Um `ErrorCodes.java` com 300 constantes vira gargalo de merge e ninguém sabe quem é dono do quê.

Arquivos: [`ErrorCode`](../src/main/java/dev/cheatsheet/exceptions/shared/error/ErrorCode.java),
[`CommonErrorCode`](../src/main/java/dev/cheatsheet/exceptions/shared/error/CommonErrorCode.java),
[`OrderErrorCode`](../src/main/java/dev/cheatsheet/exceptions/order/OrderErrorCode.java),
[`PaymentErrorCode`](../src/main/java/dev/cheatsheet/exceptions/payment/PaymentErrorCode.java).

## 3.5 O catálogo: único, validado e publicado

Com vários enums, surge o risco de dois módulos criarem `INVALID_STATE`. O
[`ErrorCatalog`](../src/main/java/dev/cheatsheet/exceptions/catalog/ErrorCatalog.java) resolve:

1. **Registra** todos os enums ([`ErrorCatalogConfig`](../src/main/java/dev/cheatsheet/exceptions/catalog/ErrorCatalogConfig.java)).
2. **Valida no startup** (fail-fast): formato, unicidade, título e mensagem preenchidos.
   Código duplicado derruba a aplicação ao subir, não em produção.
3. **Publica** em `GET /errors` e `GET /errors/{code}`. O campo `type` de cada erro aponta para lá,
   então o erro é "desreferenciável", como recomenda a RFC 9457.

```bash
curl localhost:8080/errors/ORDER_NOT_FOUND
{"code":"ORDER_NOT_FOUND","category":"NOT_FOUND","httpStatus":404,
 "title":"Pedido não encontrado","messageTemplate":"O pedido {0} não foi encontrado.","transientError":false}
```

## 3.6 Evolução do catálogo (compatibilidade)

| Mudança | Compatível? |
|---|---|
| Criar um code novo | ✅ **se** os clientes têm fallback por status (ver abaixo) |
| Mudar `title`/`messageTemplate` | ✅ (é texto para humanos) |
| Mudar a categoria/status de um code | ⚠️ Breaking: clientes podem depender do status |
| Renomear um code | ❌ Breaking. Crie um novo e deprecie o antigo |
| Reutilizar um code aposentado com outro significado | ❌ Nunca |

**Regra para o lado cliente** (documente isso na sua API):

```
1. Conheço o code?   → trato especificamente.
2. Não conheço?      → trato pela família do status (4xx: mostro detail; 5xx: "tente mais tarde").
```

Isso é o que permite adicionar codes sem quebrar ninguém.

## 3.7 Dados estruturados: `details`

O code diz **qual** erro; às vezes o cliente precisa de **dados** para reagir:

```json
{ "code": "ORDER_TOTAL_LIMIT_EXCEEDED", "limit": 10000.00, "total": 15000.00 }
```

No projeto, `ApplicationException` aceita um mapa `details`, serializado como membros de extensão
do Problem Detail. Regras:

- Só dados **seguros para o cliente**.
- Nunca sobrescrever campos do contrato: o construtor recusa chaves como `status`, `code`, `errors`
  (`RESERVED_DETAIL_KEYS`).
- Os nomes das chaves também são contrato.

## 3.8 Mensagens e internacionalização

O `messageTemplate` do projeto é a mensagem **padrão**. Para i18n, use o code como chave do `MessageSource`:

```properties
# messages_pt_BR.properties
error.ORDER_NOT_FOUND.title=Pedido não encontrado
error.ORDER_NOT_FOUND.detail=O pedido {0} não foi encontrado.
# messages_en.properties
error.ORDER_NOT_FOUND.title=Order not found
error.ORDER_NOT_FOUND.detail=Order {0} was not found.
```

```java
String detail = messageSource.getMessage(
        "error." + code.code() + ".detail", args, code.format(args), LocaleContextHolder.getLocale());
```

Para as exceções do **próprio Spring** (`MethodArgumentNotValidException`, `NoResourceFoundException`...),
`ResponseEntityExceptionHandler` já resolve título e detalhe via `MessageSource` com as chaves
`problemDetail.title.<classe.completa>` e `problemDetail.<classe.completa>`.

> Os argumentos da mensagem são formatados como texto puro (`ErrorCode.format`): `MessageFormat`
> aplicaria separador de milhar ("15,000") dependendo do locale. Formatação de número/data por
> idioma é responsabilidade da camada de i18n.

---

### ✅ Resumo do capítulo

- Status = família; **code = contrato**; title/detail = humano.
- Code semântico com prefixo de contexto, um enum por contexto.
- Catálogo central, validado no startup e publicado em `/errors`.
- Code nunca muda de significado; clientes precisam de fallback por status.
- `details` para dados estruturados seguros.
