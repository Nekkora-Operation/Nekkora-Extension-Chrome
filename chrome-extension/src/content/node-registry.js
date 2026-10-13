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
  const pageSessionId = 'ps-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);

  NS.registry = {
    pageSessionId,
    idFor(el) {
      let id = ids.get(el);
      if (!id) {
        id = prefix + 'n' + (++counter);
        ids.set(el, id);
        refs.set(id, new WeakRef(el));
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
  };
})(globalThis);
