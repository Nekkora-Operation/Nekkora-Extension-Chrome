/*
 * Política de privacidade aplicada NO NAVEGADOR (primeira barreira): valores de campos não saem da página — nem os de
 * pesquisa (CHROME-SECURITY-001). A mesma regra é reaplicada no Java.
 */
(function (root) {
  const SENSITIVE_NAME = /(senha|password|passwd|pwd|secret|segredo|token|cpf|cnpj|rg\b|ssn|cartao|cartão|card|cc-?num|cvv|cvc|pin\b|otp|iban|conta)/i;
  const VALUE_ATTRIBUTE_ALLOWED = new Set(['button', 'submit', 'reset', 'checkbox', 'radio', 'image']);

  const policy = {
    isSensitive(el) {
      const type = (el.getAttribute('type') || '').toLowerCase();
      if (type === 'password') {
        return true;
      }
      const ac = (el.getAttribute('autocomplete') || '').toLowerCase();
      if (ac.includes('password') || ac.startsWith('cc-') || ac.includes(' cc-') || ac.includes('one-time-code')) {
        return true;
      }
      if (el.getAttribute('data-nekkora-sensitive') === 'true') {
        return true;
      }
      return [el.getAttribute('name'), el.id, el.getAttribute('autocomplete')]
        .some((v) => v && SENSITIVE_NAME.test(v));
    },
    /** O atributo value pode ser exportado? (valores padrão de campos de texto podem conter dados) */
    valueAttributeAllowed(el) {
      const tag = el.tagName.toLowerCase();
      if (tag === 'option' || tag === 'button' || tag === 'li' || tag === 'param' || tag === 'data' || tag === 'meter' || tag === 'progress') {
        return true;
      }
      if (tag === 'input') {
        return VALUE_ATTRIBUTE_ALLOWED.has((el.getAttribute('type') || 'text').toLowerCase()) && !policy.isSensitive(el);
      }
      return false;
    },
  };

  if (root.NekkoraCS) {
    root.NekkoraCS.sensitivity = policy;
  }
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = policy;
  }
})(globalThis);
