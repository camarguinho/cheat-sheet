package dev.cheatsheet.exceptions.catalog;

import dev.cheatsheet.exceptions.shared.error.CommonErrorCode;
import dev.cheatsheet.exceptions.shared.error.ErrorCode;
import dev.cheatsheet.exceptions.shared.error.NotFoundException;
import dev.cheatsheet.exceptions.shared.web.HttpStatusMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Documentação viva dos erros. O campo {@code type} de cada Problem Detail aponta para
 * {@code /errors/{code}}, então o cliente consegue "desreferenciar" o erro (RFC 9457 §3.1.1).
 */
@RestController
@RequestMapping("/errors")
class ErrorCatalogController {

    private final ErrorCatalog catalog;

    ErrorCatalogController(ErrorCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    List<ErrorCodeDoc> list() {
        return catalog.all().stream().map(ErrorCodeDoc::from).toList();
    }

    @GetMapping("/{code}")
    ErrorCodeDoc get(@PathVariable String code) {
        return catalog.find(code)
                .map(ErrorCodeDoc::from)
                .orElseThrow(() -> new NotFoundException(CommonErrorCode.RESOURCE_NOT_FOUND, "errors/" + code));
    }

    record ErrorCodeDoc(String code, String category, int httpStatus, String title, String messageTemplate, boolean transientError) {
        static ErrorCodeDoc from(ErrorCode code) {
            return new ErrorCodeDoc(code.code(), code.category().name(),
                    HttpStatusMapper.toStatus(code.category()).value(),
                    code.title(), code.messageTemplate(), code.category().isTransient());
        }
    }
}
