/*
 * Registro de ids TEMPORÁRIOS de nós DOM. Um id vale enquanto o nó existir nesta sessão de página;
 * nunca é identidade lógica. WeakMap/WeakRef evitam reter nós destruídos (controle de memória).
 */
(function (root) {
  const NS = root.NekkoraCS;
  if (NS.registry) {
    return;
  }
  const ids = new WeakMap();
  const refs = new Map();
  let counter = 0;
  const pageSessionId = 'ps-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);

  NS.registry = {
    pageSessionId,
    idFor(el) {
      let id = ids.get(el);
      if (!id) {
        id = 'n' + (++counter);
        ids.set(el, id);
        refs.set(id, new WeakRef(el));
      }
      return id;
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
