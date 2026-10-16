# CHROME-SECURITY-001: armazenamento e retenção

## Chrome Storage (SEC001-059 a 061)

| Chave | Conteúdo agora |
|---|---|
| `chrome.storage.session['nk.srec.<gravação>']` (fila da gravação) | etapas e evidências **já** sob a política (o que vai ao Studio) |
| `chrome.storage.session['inspection:<aba>']` | resultado da inspeção, gerado pelo motor sobre o modelo filtrado |
| `chrome.storage.session` (sessão de captura, estado da inspeção, gravações) | ids e estado |
| `chrome.storage.local` | configuração (modo, ligação com o Studio), sem conteúdo de página |

Depois do reinício do service worker, o que é restaurado é o que foi guardado, e só foi guardado o que já está sob a política. Nenhum estado anterior à política é reinterpretado como seguro: a extensão nova parte das próprias gravações e sessões. O teste de recuperação (página 12 do laboratório: desconexão e reconexão do host durante a gravação) não reenviou conteúdo.

## Diretório de dados do Native Host

`%LOCALAPPDATA%\Nekkora\ExtensionChrome` (Windows) ou `~/.nekkora/extension-chrome` (ou `NEKKORA_DATA_DIR`):

| Arquivo | Política |
|---|---|
| `targets/registry-v2.json` | novo, sob a v2 (só origens de laboratório; sites externos ficam em memória) |
| `pagemaps/*.json` (novos) | PageMap 0.3.0 sob a v2 |
| `logs/native-host-*.log` | sem mensagens de exceção nem conteúdo |

## Dados de versões anteriores

**Não são apagados automaticamente.** Arquivos produzidos antes desta versão podem conter texto de páginas:

- `targets/registry.json` (registro da política anterior): a versão nova **não o lê nem o reescreve**;
- `pagemaps/*.json` com `"version": "0.1.0"` ou `"0.2.0"`;
- `logs/native-host-*.log` antigos (mensagens de exceção podiam citar trechos).

### Procedimento de limpeza (explícito, feito pelo usuário)

1. Feche o Chrome. Isso encerra o Native Host.
2. Abra o diretório de dados (Windows: `%LOCALAPPDATA%\Nekkora\ExtensionChrome`).
3. Apague `targets\registry.json`, os `pagemaps\*.json` que não forem 0.3.0 e os `logs\*.log` anteriores à atualização.
4. Para a sessão do navegador (`chrome.storage.session`), basta fechar o navegador: ela não sobrevive ao encerramento.

Apagar arquivos **não garante** eliminação física irrecuperável em SSD, em sistemas com cópias de sombra ou instantâneos, nem em backups. Se houver dados reais de clientes nesses arquivos, trate-os pela política de descarte da sua organização.
