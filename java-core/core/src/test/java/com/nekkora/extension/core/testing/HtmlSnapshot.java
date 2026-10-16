package com.nekkora.extension.core.testing;

import com.nekkora.extension.core.snapshot.CaptureStats;
import com.nekkora.extension.core.snapshot.DocumentInfo;
import com.nekkora.extension.core.snapshot.FrameInfo;
import com.nekkora.extension.core.snapshot.NodeState;
import com.nekkora.extension.core.snapshot.RawNode;
import com.nekkora.extension.core.snapshot.RawSnapshot;
import com.nekkora.extension.core.snapshot.Rect;
import com.nekkora.extension.core.snapshot.ShadowRootInfo;
import com.nekkora.extension.core.snapshot.Viewport;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converte HTML em {@link RawSnapshot} emulando as regras do content script, para testes unitários
 * do motor sem navegador. A fidelidade com o navegador real é validada separadamente pelos testes
 * de integração em Chrome.
 *
 * <p>Convenções de fixture:
 * <ul>
 *   <li>{@code <template shadowrootmode="open|closed">} vira shadow root do elemento pai;</li>
 *   <li>{@code <iframe data-fixture-frame="nome">} recebe o documento registrado com {@link #frame};</li>
 *   <li>{@code data-fixture-modal} em {@code <dialog open>} emula {@code showModal()}.</li>
 * </ul>
 * O mesmo elemento jsoup recebe sempre o mesmo id temporário ao longo do objeto {@code HtmlSnapshot},
 * o que permite simular capturas sucessivas da mesma sessão de página.
 */
public final class HtmlSnapshot {

    private static final Set<String> SKIP = Set.of("head", "script", "style", "noscript", "template", "meta", "link", "title");
    private static final Set<String> LABELABLE = Set.of("input", "select", "textarea", "button", "meter", "output", "progress");

    private final Document doc;
    private final Map<String, FrameSpec> frames = new LinkedHashMap<>();
    private final IdentityHashMap<Element, String> ids = new IdentityHashMap<>();
    private int counter = 0;
    /*
     * D7: simula o registro de nós do content script — a ÉPOCA muda a cada recarga do documento, reinicialização do registro
     * (ids recomeçam e podem ser REUSADOS) e recarga de um frame; textRevision conta mudanças do texto próprio, como na página.
     */
    private int epochSeq = 1;
    private String epoch = "hs1";
    private final Map<String, String> frameEpochs = new LinkedHashMap<>();
    private final Map<Element, Integer> revisions = new java.util.IdentityHashMap<>();
    private final Map<Element, String> lastOwn = new java.util.IdentityHashMap<>();
    private int captures = 0;
    private String url = "http://127.0.0.1:8765/lab/test.html";
    private String pageSession = "ps-test-1";

    private record FrameSpec(Document doc, boolean accessible, String reason, String url) {
    }

    private HtmlSnapshot(String html) {
        this.doc = Jsoup.parse(html);
    }

    public static HtmlSnapshot of(String html) {
        return new HtmlSnapshot(html);
    }

    public HtmlSnapshot url(String u) {
        this.url = u;
        return this;
    }

    public HtmlSnapshot frame(String name, String html, boolean accessible, String reason) {
        frames.put(name, new FrameSpec(Jsoup.parse(html), accessible, reason, "http://127.0.0.1:8765/lab/frame-" + name + ".html"));
        return this;
    }

    /** Frame com URL efetiva explícita (ex.: {@code about:blank}, {@code about:srcdoc}, outra origem). */
    public HtmlSnapshot frame(String name, String html, boolean accessible, String reason, String url) {
        frames.put(name, new FrameSpec(Jsoup.parse(html), accessible, reason, url));
        return this;
    }

    /** Id temporário de um elemento do documento de um frame registrado. */
    public String idInFrame(String frameName, String cssQuery) {
        Element e = frames.get(frameName).doc().selectFirst(cssQuery);
        if (e == null) {
            throw new IllegalArgumentException("Sem elemento para " + cssQuery + " no frame " + frameName);
        }
        return ids.get(e);
    }

    /** Documento jsoup para mutações entre capturas (simula a página mudando). */
    public Document dom() {
        return doc;
    }

    /** Simula recarga de página: ids temporários e sessão mudam. */
    public HtmlSnapshot reload() {
        ids.clear();
        pageSession = "ps-test-" + (counter + 1000);
        epoch = "hs" + (++epochSeq);
        return this;
    }

    /** Reinicialização do registro de nós (configurePrefix): mapas limpos, contador recomeça — ids podem ser REUSADOS. */
    public HtmlSnapshot resetRegistry() {
        ids.clear();
        counter = 0;
        epoch = "hs" + (++epochSeq);
        return this;
    }

    /** Recarga de um frame: novo content script no frame, nova época do registro dele. */
    public HtmlSnapshot reloadFrame(String name) {
        frameEpochs.put(name, "fr" + (++epochSeq));
        return this;
    }

    private List<com.nekkora.extension.core.snapshot.ResourceRef> resources = new ArrayList<>();
    private final Map<String, String> frameTitles = new LinkedHashMap<>();

    public RawSnapshot capture() {
        captures++;
        resources = new ArrayList<>();
        List<RawNode> nodes = new ArrayList<>();
        List<FrameInfo> frameInfos = new ArrayList<>();
        List<ShadowRootInfo> shadows = new ArrayList<>();
        frameInfos.add(new FrameInfo("f0", null, null, url, true, null, null, null, epoch));
        Element html = doc.selectFirst("html");
        int[] frameCounter = {0};
        frameTitles.clear();
        emit(html, null, "f0", "doc:f0", false, nodes, frameInfos, shadows, frameCounter);
        for (int i = 0; i < frameInfos.size(); i++) {
            FrameInfo f = frameInfos.get(i);
            if (frameTitles.containsKey(f.frameId())) {
                frameInfos.set(i, new FrameInfo(f.frameId(), f.parentFrameId(), f.hostNodeId(), f.url(), f.accessible(), f.reason(),
                        frameTitles.get(f.frameId()), "complete", f.registryEpoch()));
            }
        }
        DocumentInfo info = new DocumentInfo(url, doc.title(), "complete", new Viewport(1280, 800, 0, 0, 1), "MANUAL", pageSession);
        return new RawSnapshot("cap-" + captures + "-" + pageSession, "2026-10-13T00:00:0" + (captures % 10) + "Z", info,
                frameInfos, shadows, nodes, List.of(), new CaptureStats(nodes.size(), nodes.size(), 1.0, false, 20000), resources);
    }

    public String idOf(String cssQuery) {
        Element e = doc.selectFirst(cssQuery);
        if (e == null) {
            throw new IllegalArgumentException("Sem elemento para " + cssQuery);
        }
        return ids.get(e);
    }

    public String idOf(Element e) {
        return ids.get(e);
    }

    private String idFor(Element e) {
        return ids.computeIfAbsent(e, k -> "n" + (++counter));
    }

    private String emit(Element e, String parentId, String frameId, String scopeId, boolean hiddenAncestor,
                        List<RawNode> out, List<FrameInfo> frameInfos, List<ShadowRootInfo> shadows, int[] frameCounter) {
        String tag = e.tagName().toLowerCase(Locale.ROOT);
        String id = idFor(e);
        int index = out.size();
        out.add(null); // reserva posição (pré-ordem)
        boolean hidden = hiddenAncestor || isHiddenSelf(e);
        List<String> children = new ArrayList<>();

        for (Element child : e.children()) {
            String ct = child.tagName().toLowerCase(Locale.ROOT);
            if (ct.equals("template") && child.hasAttr("shadowrootmode")) {
                String mode = child.attr("shadowrootmode");
                boolean open = mode.equals("open");
                shadows.add(new ShadowRootInfo(id, mode, open, open ? "ELEMENT_SHADOW_ROOT" : "EXTENSION_API",
                        open ? null : "shadow root fechado: encapsulamento intencional"));
                if (open) {
                    Document content = Jsoup.parseBodyFragment(child.html());
                    for (Element sc : content.body().children()) {
                        children.add(emit(sc, id, frameId, "shadow:" + id, hidden, out, frameInfos, shadows, frameCounter));
                    }
                }
                continue;
            }
            if (SKIP.contains(ct)) {
                if (ct.equals("head")) {
                    for (Element sc : child.select("script[src]")) {
                        resources.add(new com.nekkora.extension.core.snapshot.ResourceRef("SCRIPT",
                                com.nekkora.extension.core.frames.UrlSanitizer.sanitize(sc.attr("src")), "DOM", frameId,
                                sc.hasAttr("type") ? sc.attr("type") : null, null,
                                com.nekkora.extension.core.frames.UrlSanitizer.queryKeys(sc.attr("src"))));
                    }
                    if (child.selectFirst("title") != null && !frameId.equals("f0")) {
                        frameTitles.put(frameId, child.selectFirst("title").text());
                    }
                }
                if (ct.equals("script") && child.hasAttr("src")) {
                    // Espelho do content script: referência de script com URL sanitizada (sem query) e nomes dos parâmetros.
                    String src = child.attr("src");
                    resources.add(new com.nekkora.extension.core.snapshot.ResourceRef("SCRIPT",
                            com.nekkora.extension.core.frames.UrlSanitizer.sanitize(src), "DOM", frameId,
                            child.hasAttr("type") ? child.attr("type") : null, null,
                            com.nekkora.extension.core.frames.UrlSanitizer.queryKeys(src)));
                }
                continue;
            }
            children.add(emit(child, id, frameId, scopeId, hidden, out, frameInfos, shadows, frameCounter));
        }
        if ((tag.equals("iframe") || tag.equals("frame")) && e.hasAttr("data-fixture-frame")) {
            FrameSpec spec = frames.get(e.attr("data-fixture-frame"));
            String fid = "f" + (++frameCounter[0]);
            String frameName = e.attr("data-fixture-frame");
            frameInfos.add(new FrameInfo(fid, frameId, id, spec == null ? null : spec.url(), spec != null && spec.accessible(),
                    spec == null ? "NOT_LOADED" : spec.reason(), null, null,
                    spec != null && spec.accessible() ? frameEpochs.computeIfAbsent(frameName, k -> "fr" + k.replaceAll("[^a-z0-9]", "")) : null));
            if (spec != null && spec.accessible()) {
                children.add(emit(spec.doc().selectFirst("html"), id, fid, "doc:" + fid, hidden, out, frameInfos, shadows, frameCounter));
            }
        }

        Map<String, String> attrs = new LinkedHashMap<>();
        for (Attribute a : e.attributes()) {
            if (a.getKey().startsWith("data-fixture")) {
                continue;
            }
            attrs.put(a.getKey(), a.getValue());
        }
        boolean textEntry = tag.equals("textarea") || (tag.equals("input") && !Set.of("checkbox", "radio", "submit", "button",
                "reset", "image", "file", "hidden", "range", "color").contains(e.attr("type").toLowerCase(Locale.ROOT)));
        String exposed = null;
        if (textEntry) {
            String v = attrs.remove("value");
            boolean search = "search".equals(e.attr("type")) || e.attr("name").matches("(?i)(q|query|search|pesquisa|busca|filtro).*");
            if (search && v != null) {
                exposed = v;
            }
            attrs.put("__hasValue", Boolean.toString(v != null && !v.isEmpty()));
        }
        boolean hasValue = Boolean.parseBoolean(attrs.remove("__hasValue"));
        String own = tag.equals("svg") && e.selectFirst("title") != null
                ? e.selectFirst("title").text() // o content script captura o <title> do SVG como texto próprio
                : ownText(e);
        boolean disabled = isDisabled(e);
        boolean editable = isEditable(e);
        boolean modal = e.hasAttr("data-fixture-modal");
        NodeState state = new NodeState(!hidden, hidden ? "fixture" : null, true, false,
                isFormControl(tag) ? disabled : null,
                textEntry ? e.hasAttr("readonly") : null,
                isFormControl(tag) ? e.hasAttr("required") : null,
                tag.equals("input") ? e.hasAttr("checked") : null,
                tag.equals("option") ? isSelected(e) : null,
                editable, focusable(e), false, e.hasClass("card") || e.hasAttr("data-fixture-boxed"),
                textEntry ? hasValue : null, null,
                tag.equals("dialog") || tag.equals("details") ? e.hasAttr("open") : null,
                tag.equals("dialog") ? modal : null,
                false);
        String prev = lastOwn.put(e, own);
        int rev = revisions.getOrDefault(e, 0) + (prev != null && !prev.equals(own) ? 1 : 0);
        revisions.put(e, rev);
        out.set(index, new RawNode(id, parentId, children, tag, frameId, scopeId, attrs, own, state,
                new Rect(0, index * 10.0, 100, 20), labelIds(e), null, exposed, segments(e, children, own), null, null, null, rev));
        return id;
    }

    private List<String> labelIds(Element e) {
        String tag = e.tagName().toLowerCase(Locale.ROOT);
        if (!LABELABLE.contains(tag) || (tag.equals("input") && "hidden".equals(e.attr("type")))) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        String id = e.id();
        if (!id.isEmpty()) {
            Element first = e.ownerDocument() == null ? null : e.ownerDocument().getElementById(id);
            if (first == e) {
                for (Element l : e.ownerDocument().select("label[for]")) {
                    if (l.attr("for").equals(id)) {
                        out.add(idFor(l));
                    }
                }
            }
        }
        for (Element p = e.parent(); p != null; p = p.parent()) {
            if (p.tagName().equals("label")) {
                if (!p.hasAttr("for")) {
                    out.add(idFor(p));
                }
                break;
            }
        }
        return out;
    }

    /** Espelho do content script: trechos de texto intercalados com os filhos emitidos (conteúdo misto). */
    private List<String> segments(Element e, List<String> children, String own) {
        if (own.isEmpty() || children.isEmpty() || e.tagName().equals("svg")) {
            return null;
        }
        List<String> segs = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int matched = 0;
        for (Node n : e.childNodes()) {
            if (n instanceof TextNode t) {
                cur.append(' ').append(t.getWholeText());
            } else if (n instanceof Element c) {
                String cid = ids.get(c);
                if (cid != null && matched < children.size() && cid.equals(children.get(matched))) {
                    segs.add(cur.toString().replaceAll("\s+", " ").trim());
                    cur.setLength(0);
                    matched++;
                }
            }
        }
        if (matched != children.size()) {
            return null; // filhos compostos (shadow/frame) não correspondem aos filhos de luz
        }
        segs.add(cur.toString().replaceAll("\s+", " ").trim());
        return segs;
    }

    private static String ownText(Element e) {
        StringBuilder sb = new StringBuilder();
        for (Node n : e.childNodes()) {
            if (n instanceof TextNode t) {
                sb.append(' ').append(t.getWholeText());
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static boolean isHiddenSelf(Element e) {
        String style = e.attr("style").replace(" ", "").toLowerCase(Locale.ROOT);
        return e.hasAttr("hidden") || style.contains("display:none") || style.contains("visibility:hidden")
                || ("input".equals(e.tagName()) && "hidden".equals(e.attr("type")))
                || (e.tagName().equals("dialog") && !e.hasAttr("open"));
    }

    private static boolean isFormControl(String tag) {
        return Set.of("input", "select", "textarea", "button", "option", "optgroup", "fieldset").contains(tag);
    }

    private static boolean isDisabled(Element e) {
        if (e.hasAttr("disabled")) {
            return true;
        }
        for (Element p = e.parent(); p != null; p = p.parent()) {
            if (p.tagName().equals("fieldset") && p.hasAttr("disabled")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEditable(Element e) {
        for (Element p = e; p != null; p = p.parent()) {
            if (p.hasAttr("contenteditable")) {
                return !"false".equalsIgnoreCase(p.attr("contenteditable"));
            }
        }
        return false;
    }

    private static boolean isSelected(Element option) {
        if (option.hasAttr("selected")) {
            return true;
        }
        Element select = option.closest("select");
        if (select == null || select.hasAttr("multiple")) {
            return false;
        }
        boolean anySelected = !select.select("option[selected]").isEmpty();
        return !anySelected && select.selectFirst("option") == option;
    }

    private static boolean focusable(Element e) {
        String tag = e.tagName();
        if (e.hasAttr("tabindex")) {
            try {
                return Integer.parseInt(e.attr("tabindex").trim()) >= 0;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return switch (tag) {
            case "button", "select", "textarea" -> !e.hasAttr("disabled");
            case "input" -> !"hidden".equals(e.attr("type")) && !e.hasAttr("disabled");
            case "a" -> e.hasAttr("href");
            default -> false;
        };
    }
}
