"""Gera contracts/privacy/html-elements.json (CHROME-SECURITY-001, 2ª revisão dos PRs #7).

Catálogo FECHADO dos nomes de elemento padronizados (HTML, incluindo os obsoletos ainda reconhecidos pelos navegadores,
SVG e MathML), em minúsculas. O nome de um elemento personalizado (<ficha-cliente-joao>) ou desconhecido (<joao>) é
escolhido pelo autor da página e pode carregar dado de negócio: fora deste catálogo, o nome só atravessa se for
identificador INTEIRO do vocabulário controlado; senão vai o marcador neutro "custom-element".
Uso: python scripts/gen-html-elements.py   (depois: node scripts/gen-ui-vocabulary.mjs)
"""
import json
import pathlib

HTML = ('a abbr address area article aside audio b base bdi bdo blockquote body br button canvas caption cite code col '
        'colgroup data datalist dd del details dfn dialog div dl dt em embed fieldset figcaption figure footer form h1 h2 '
        'h3 h4 h5 h6 head header hgroup hr html i iframe img input ins kbd label legend li link main map mark menu meta '
        'meter nav noscript object ol optgroup option output p picture pre progress q rp rt ruby s samp script search '
        'section select selectedcontent slot small source span strong style sub summary sup table tbody td template '
        'textarea tfoot th thead time title tr track u ul var video wbr')
HTML_OBSOLETE = ('acronym applet basefont bgsound big blink center dir font frame frameset image isindex keygen listing '
                 'marquee menuitem multicol nextid nobr noembed noframes param plaintext rb rtc spacer strike tt xmp')
SVG = ('svg animate animatemotion animatetransform circle clippath defs desc discard ellipse feblend fecolormatrix '
       'fecomponenttransfer fecomposite feconvolvematrix fediffuselighting fedisplacementmap fedistantlight fedropshadow '
       'feflood fefunca fefuncb fefuncg fefuncr fegaussianblur feimage femerge femergenode femorphology feoffset '
       'fepointlight fespecularlighting fespotlight fetile feturbulence filter foreignobject g line lineargradient marker '
       'mask metadata mpath path pattern polygon polyline radialgradient rect set stop switch symbol text textpath tspan '
       'use view')
MATHML = ('math annotation annotation-xml maction menclose merror mfenced mfrac mi mmultiscripts mn mo mover mpadded '
          'mphantom mprescripts mroot mrow ms mspace msqrt mstyle msub msubsup msup mtable mtd mtext mtr munder '
          'munderover none semantics')

elements = sorted(set((HTML + ' ' + HTML_OBSOLETE + ' ' + SVG + ' ' + MATHML).split()))
assert 'custom-element' not in elements
out = {
    'version': 'nekkora-html-elements/1',
    'note': ('CHROME-SECURITY-001 (2ª revisão dos PRs #7): catálogo FECHADO de nomes de elemento padronizados (HTML, '
             'SVG, MathML). Fora dele, o nome só atravessa se for identificador inteiro do vocabulário controlado; senão '
             'vai o marcador neutro. Gerado por scripts/gen-html-elements.py; não editar à mão.'),
    'neutral': 'custom-element',
    'elements': elements,
}
p = pathlib.Path(__file__).resolve().parent.parent / 'contracts' / 'privacy' / 'html-elements.json'
p.write_text(json.dumps(out, ensure_ascii=False, indent=1) + '\n', encoding='utf-8', newline='\n')
print(f'html-elements.json: {len(elements)} elementos')
