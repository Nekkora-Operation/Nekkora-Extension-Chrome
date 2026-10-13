/*
 * Executa localizadores gerados pelo motor Java contra a DOM REAL (querySelectorAll /
 * document.evaluate) e relata contagens. Não interage com os elementos.
 */
(function (root) {
  const NS = root.NekkoraCS;
  if (NS.locators) {
    return;
  }

  /** Resolve a raiz de consulta seguindo a cadeia de escopos (iframes / shadow hosts). */
  function scopeRoot(scope) {
    let current = document;
    for (const hop of (scope && scope.chain) || []) {
      if (!hop.css) {
        return { error: 'SCOPE_HOST_WITHOUT_CSS' };
      }
      let hosts;
      try {
        hosts = current.querySelectorAll(hop.css);
      } catch (e) {
        return { error: 'INVALID_CSS: ' + e.message };
      }
      if (hosts.length !== 1) {
        return { error: hosts.length === 0 ? 'SCOPE_HOST_NOT_FOUND' : 'SCOPE_HOST_AMBIGUOUS (' + hosts.length + ')' };
      }
      const host = hosts[0];
      if (hop.kind === 'FRAME') {
        let doc = null;
        try {
          doc = host.contentDocument;
        } catch (e) {
          doc = null;
        }
        if (!doc) {
          return { error: 'SCOPE_UNAVAILABLE: iframe inacessível' };
        }
        current = doc;
      } else {
        if (!host.shadowRoot) {
          return { error: 'SCOPE_UNAVAILABLE: shadow root fechado ou ausente' };
        }
        current = host.shadowRoot;
      }
    }
    return { root: current };
  }

  function runOne(locator, target) {
    const out = { semantic: locator.semantic, cssCount: -1, xpathCount: -1, cssMatchesTarget: false, xpathMatchesTarget: false, error: null };
    const s = scopeRoot(locator.scope);
    if (s.error) {
      out.error = s.error;
      return out;
    }
    const r = s.root;
    if (locator.css) {
      try {
        const found = r.querySelectorAll(locator.css);
        out.cssCount = found.length;
        out.cssMatchesTarget = !!target && Array.prototype.includes.call(found, target);
      } catch (e) {
        out.error = 'CSS: ' + e.message;
      }
    }
    if (locator.xpath && !(r instanceof ShadowRoot)) {
      try {
        const doc = r.nodeType === Node.DOCUMENT_NODE ? r : r.ownerDocument;
        const res = doc.evaluate(locator.xpath, doc, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
        out.xpathCount = res.snapshotLength;
        for (let i = 0; i < res.snapshotLength; i++) {
          if (res.snapshotItem(i) === target) {
            out.xpathMatchesTarget = true;
            break;
          }
        }
      } catch (e) {
        out.error = (out.error ? out.error + '; ' : '') + 'XPath: ' + e.message;
      }
    }
    return out;
  }

  NS.locators = {
    /** Verifica todos os localizadores de um LocatorSet para o nó alvo. */
    verify(nodeId, locatorSet) {
      const target = NS.registry.elementFor(nodeId);
      const all = [];
      if (locatorSet && locatorSet.primary) {
        all.push(locatorSet.primary);
      }
      for (const f of (locatorSet && locatorSet.fallbacks) || []) {
        all.push(f);
      }
      return all.filter((l) => l.css || l.xpath).map((l) => runOne(l, target));
    },
    runOne,
    scopeRoot,
  };
})(globalThis);
