package dev.cheatsheet.exceptions.shared.web;

/**
 * Item da lista {@code errors} em respostas de validação.
 * O valor rejeitado <b>não</b> é ecoado: pode ser senha, documento ou cartão.
 */
public record FieldViolation(String field, String message) {
}
