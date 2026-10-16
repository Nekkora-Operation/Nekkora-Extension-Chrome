/*
 * Registro de ids TEMPORÁRIOS de nós DOM. Um id vale enquanto o nó existir nesta sessão de página;
 * nunca é identidade lógica. WeakMap/WeakRef evitam reter nós destruídos (controle de memória).
 */
(function (root) {
  const NS = root.NekkoraCS;
  if (NS.registry) {
    return;
  }
  let ids = new WeakMap();
  let refs = new Map();
  let counter = 0;
  let prefix = '';
  /*
   * D7 (CHROME-SECURITY-001): ÉPOCA deste registro — token aleatório renovado a cada instância do content script (novo
   * documento, frame recarregado) e a cada reinicialização dos mapas (configurePrefix). Mesmo id + mesma época = mesmo
   * objeto DOM; sem a mesma época o id NÃO prova continuidade (os contadores recomeçam).
   */
  const newEpoch = () => Math.random().toString(36).slice(2, 12).replace(/[^a-z0-9]/g, '') || 'e0';
  let epoch = newEpoch();
  const pageSessionId = 'ps-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);

  NS.registry = {
    pageSessionId,
    epoch() {
      return epoch;
    },
    idFor(el) {
      let id = ids.get(el);
      if (!id) {
        id = prefix + 'n' + (++counter);
        ids.set(el, id);
        refs.set(id, new WeakRef(el));
      } else if (!refs.has(id)) {
        refs.set(id, new WeakRef(el)); // nó reinserido após uma varredura: mantém o mesmo id temporário
      }
      return id;
    },
    /**
     * Prefixa os ids desta instância (content script de iframe: x<frameId>). Ids já atribuídos sem prefixo são
     * descartados para nunca colidirem com os do documento principal.
     */
    configurePrefix(p) {
      if (prefix === p) {
        return;
      }
      prefix = p;
      ids = new WeakMap();
      refs = new Map();
      counter = 0;
      epoch = newEpoch(); // ids recomeçam: continuidade anterior invalidada
    },
    prefix() {
      return prefix;
    },
    peek(el) {
      return ids.get(el) || null;
    },
    elementFor(id) {
      const ref = refs.get(id);
      const el = ref ? ref.deref() : undefined;
      if (!el) {
        refs.delete(id);
        return null;
      }
      return el;
    },
    isConnected(id) {
      const el = this.elementFor(id);
      return !!(el && el.isConnected);
    },
    /** Remove referências a nós coletados ou desconectados há muito tempo. */
    sweep() {
      let removed = 0;
      for (const [id, ref] of refs) {
        const el = ref.deref();
        if (!el || !el.isConnected) {
          refs.delete(id);
          removed++;
        }
      }
      return removed;
    },
    size() {
      return refs.size;
    },
    /** Elementos registrados ainda conectados (iteração sem reter nós coletados). */
    *connectedElements() {
      for (const ref of refs.values()) {
        const el = ref.deref();
        if (el && el.isConnected) {
          yield el;
        }
      }
    },
  };
})(globalThis);
