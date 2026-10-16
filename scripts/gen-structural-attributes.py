"""Gera contracts/privacy/structural-attributes.json (CHROME-SECURITY-001, revisão dos PRs #7, P1-01).

Cada atributo estrutural tem uma regra FECHADA: o valor só atravessa se pertencer ao conjunto/forma daquele atributo.
Fora disso o atributo vai só como PRESENÇA (valor vazio). Formato da regra (uma ou mais alternativas separadas por ';'):
  boolean            presença; valor "", o próprio nome do atributo, "true" ou "false"
  enum:a b c         um dos valores (sem diferenciar maiúsculas)
  tokens:a b c       lista separada por espaço/vírgula, CADA item do conjunto
  integer:N          inteiro com até N dígitos (sinal opcional)
  decimal:N          número com até N dígitos inteiros e até 4 decimais
  dimension:N        inteiro com até N dígitos, com 'px' ou '%' opcionais
  lang               etiqueta de idioma: código ISO 639-1 (lista fechada) + região (2 letras ou 3 dígitos) opcional
  presence           nunca o valor
Uso: python scripts/gen-structural-attributes.py   (depois: node scripts/gen-ui-vocabulary.mjs e os vetores)
"""
import json
import pathlib

ROLES = ('alert alertdialog application article banner blockquote button caption cell checkbox code columnheader combobox '
         'complementary contentinfo definition deletion dialog directory document emphasis feed figure form generic grid '
         'gridcell group heading img insertion link list listbox listitem log main mark marquee math menu menubar menuitem '
         'menuitemcheckbox menuitemradio meter navigation none note option paragraph presentation progressbar radio '
         'radiogroup region row rowgroup rowheader scrollbar search searchbox separator slider spinbutton status strong '
         'subscript superscript switch tab table tablist tabpanel term textbox time timer toolbar tooltip tree treegrid treeitem')
AUTOCOMPLETE = ('on off name honorific-prefix given-name additional-name family-name honorific-suffix nickname username '
                'new-password current-password one-time-code organization-title organization street-address address-line1 '
                'address-line2 address-line3 address-level4 address-level3 address-level2 address-level1 country country-name '
                'postal-code cc-name cc-given-name cc-additional-name cc-family-name cc-number cc-exp cc-exp-month cc-exp-year '
                'cc-csc cc-type transaction-currency transaction-amount language bday bday-day bday-month bday-year sex url photo '
                'tel tel-country-code tel-national tel-area-code tel-local tel-local-prefix tel-local-suffix tel-extension email '
                'impp webauthn shipping billing home work mobile fax pager')
INPUT_TYPES = ('text password email tel url search number range date month week time datetime-local color checkbox radio file '
               'submit reset button image hidden')
MIME = ('module importmap text/javascript application/javascript application/json application/ld+json text/css text/plain '
        'text/html text/csv image/png image/jpeg image/gif image/webp image/svg+xml image/x-icon image/vnd.microsoft.icon '
        'audio/mpeg audio/ogg video/mp4 video/webm application/pdf application/zip font/woff font/woff2 '
        'application/x-nekkora-inert')
OL_TYPES = '1 a A i I disc circle square'
ACCEPT = ('image/* audio/* video/* text/* application/pdf application/zip application/json text/csv text/plain image/png '
          'image/jpeg image/gif image/webp application/vnd.ms-excel application/msword '
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet '
          'application/vnd.openxmlformats-officedocument.wordprocessingml.document '
          '.pdf .png .jpg .jpeg .gif .webp .svg .csv .txt .xml .json .zip .xls .xlsx .doc .docx .ppt .pptx .mp3 .mp4')
REL = ('alternate author bookmark canonical dns-prefetch external help icon license manifest modulepreload next nofollow '
       'noopener noreferrer opener pingback preconnect prefetch preload prev search stylesheet tag shortcut apple-touch-icon')
SANDBOX = ('allow-downloads allow-forms allow-modals allow-orientation-lock allow-pointer-lock allow-popups '
           'allow-popups-to-escape-sandbox allow-presentation allow-same-origin allow-scripts '
           'allow-storage-access-by-user-activation allow-top-navigation allow-top-navigation-by-user-activation '
           'allow-top-navigation-to-custom-protocols')
REFERRER = ('no-referrer no-referrer-when-downgrade origin origin-when-cross-origin same-origin strict-origin '
            'strict-origin-when-cross-origin unsafe-url')
TRI = 'true false mixed undefined'
BOOL = 'true false undefined'

RULES = {
    'type': 'enum:' + INPUT_TYPES + ' ' + MIME + ' ' + OL_TYPES,
    'role': 'tokens:' + ROLES,
    'autocomplete': 'tokens:' + AUTOCOMPLETE,
    'inputmode': 'enum:none text decimal numeric tel search email url',
    'method': 'enum:get post dialog',
    'target': 'enum:_self _blank _parent _top',
    'scope': 'enum:row col rowgroup colgroup',
    'dir': 'enum:ltr rtl auto',
    'lang': 'lang', 'hreflang': 'lang', 'srclang': 'lang',
    'popover': 'enum:auto manual hint;boolean',
    'contenteditable': 'enum:true false plaintext-only;boolean',
    'draggable': 'enum:true false auto',
    'spellcheck': 'enum:true false;boolean',
    'translate': 'enum:yes no;boolean',
    'autocapitalize': 'enum:off none on sentences words characters',
    'enterkeyhint': 'enum:enter done go next previous search send',
    'decoding': 'enum:sync async auto',
    'fetchpriority': 'enum:high low auto',
    'loading': 'enum:eager lazy',
    'referrerpolicy': 'enum:' + REFERRER,
    'crossorigin': 'enum:anonymous use-credentials;boolean',
    'charset': 'enum:utf-8 utf8 iso-8859-1 windows-1252',
    'rel': 'tokens:' + REL,
    'accept': 'tokens:' + ACCEPT,
    'enctype': 'enum:application/x-www-form-urlencoded multipart/form-data text/plain',
    'wrap': 'enum:soft hard off',
    'scrolling': 'enum:yes no auto',
    'frameborder': 'enum:0 1 yes no',
    'sandbox': 'tokens:' + SANDBOX + ';boolean',
    'allow': 'presence',
    'preload': 'enum:none metadata auto;boolean',
    'kind': 'enum:subtitles captions descriptions chapters metadata',
    'shape': 'enum:rect circle poly default',
    'hidden': 'enum:until-found hidden;boolean',
    # booleanos HTML
    **{b: 'boolean' for b in ('disabled checked readonly required open multiple selected novalidate autofocus async defer '
                              'nomodule allowfullscreen controls autoplay loop muted playsinline reversed').split()},
    # numéricos (posição, contagem, limites) — até 5 dígitos: um identificador de negócio não passa por "número estrutural"
    **{n: 'integer:5' for n in ('tabindex colspan rowspan maxlength minlength size rows cols span start aria-level aria-colcount '
                                'aria-rowcount aria-colindex aria-rowindex aria-posinset aria-setsize').split()},
    'min': 'decimal:5', 'max': 'decimal:5', 'step': 'decimal:5;enum:any',
    'width': 'dimension:5', 'height': 'dimension:5',
    'aria-valuenow': 'decimal:5', 'aria-valuemin': 'decimal:5', 'aria-valuemax': 'decimal:5',
    # ARIA de estado
    'aria-expanded': 'enum:' + BOOL, 'aria-pressed': 'enum:' + TRI, 'aria-checked': 'enum:' + TRI,
    'aria-selected': 'enum:' + BOOL, 'aria-disabled': 'enum:true false', 'aria-hidden': 'enum:' + BOOL,
    'aria-invalid': 'enum:true false grammar spelling', 'aria-required': 'enum:true false', 'aria-readonly': 'enum:true false',
    'aria-busy': 'enum:true false', 'aria-modal': 'enum:true false', 'aria-multiselectable': 'enum:true false',
    'aria-atomic': 'enum:true false',
    'aria-haspopup': 'enum:false true menu listbox tree grid dialog',
    'aria-live': 'enum:off polite assertive',
    'aria-current': 'enum:page step location date time true false',
    'aria-orientation': 'enum:horizontal vertical undefined',
    'aria-sort': 'enum:ascending descending none other',
    'aria-autocomplete': 'enum:inline list both none',
    'aria-relevant': 'tokens:additions removals text all',
}

LANGS = ('aa ab af ak am an ar as av ay az ba be bg bh bi bm bn bo br bs ca ce ch co cr cs cu cv cy da de dv dz ee el en eo es '
         'et eu fa ff fi fj fo fr fy ga gd gl gn gu gv ha he hi ho hr ht hu hy hz ia id ie ig ii ik io is it iu ja jv ka kg ki kj '
         'kk kl km kn ko kr ks ku kv kw ky la lb lg li ln lo lt lu lv mg mh mi mk ml mn mr ms mt my na nb nd ne ng nl nn no nr '
         'nv ny oc oj om or os pa pi pl ps pt qu rm rn ro ru rw sa sc sd se sg si sk sl sm sn so sq sr ss st su sv sw ta te tg '
         'th ti tk tl tn to tr ts tt tw ty ug uk ur uz ve vi vo wa wo xh yi yo za zh zu').split()

out = {
    'version': 'nekkora-structural-attributes/1',
    'note': ('CHROME-SECURITY-001 (revisão dos PRs #7, P1-01): regra FECHADA por atributo estrutural. Valor fora da regra => '
             'só a PRESENÇA (valor vazio). Gerado por scripts/gen-structural-attributes.py; não editar à mão.'),
    'languages': LANGS,
    'scripts': ['Latn', 'Cyrl', 'Arab', 'Hans', 'Hant', 'Grek', 'Hebr', 'Jpan', 'Kore'],
    'rules': dict(sorted(RULES.items())),
}
p = pathlib.Path(__file__).resolve().parent.parent / 'contracts' / 'privacy' / 'structural-attributes.json'
p.write_text(json.dumps(out, ensure_ascii=False, indent=1) + '\n', encoding='utf-8', newline='\n')
print(len(RULES), 'atributos', p)
