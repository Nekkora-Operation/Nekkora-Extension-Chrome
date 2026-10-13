/*
 * Utilitários das páginas do laboratório. Os hooks de teste são DECLARADOS explicitamente pela
 * página (marca __nekkoraLab) e são somente leitura: a extensão só lê hooks com esta marca, e
 * somente em páginas locais.
 */
window.NekkoraLab = {
  actions: [],
  logAction(name) {
    this.actions.push(name);
    const el = document.getElementById('action-log');
    if (el) {
      el.textContent = this.actions.join('\n');
    }
  },
  hooks(extra) {
    window.nekkoraTestHooks = Object.freeze(Object.assign({
      __nekkoraLab: true,
      getActionLog: () => window.NekkoraLab.actions.slice(),
    }, extra || {}));
  },
  randomId(prefix) {
    return prefix + '-' + Math.floor(10000 + Math.random() * 89999);
  },
};
window.NekkoraLab.hooks();
