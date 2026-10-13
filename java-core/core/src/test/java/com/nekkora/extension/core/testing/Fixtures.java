package com.nekkora.extension.core.testing;

/** Fixtures HTML reutilizadas nos testes do motor (espelham páginas do laboratório). */
public final class Fixtures {

    private Fixtures() {
    }

    /** §8.1 — duas tabelas com botões de mesmo id e texto. */
    public static final String DUPLICATED_TABLES = """
            <main>
              <h2>Clientes ativos</h2>
              <table id="clientes-ativos">
                <tr data-client="101"><td>Ana</td><td><button id="excluir">Excluir</button></td></tr>
              </table>
              <h2>Clientes inativos</h2>
              <table id="clientes-inativos">
                <tr data-client="202"><td>Maria</td><td><button id="excluir">Excluir</button></td></tr>
              </table>
            </main>
            """;

    /** Mesmo id em linhas diferentes da MESMA tabela. */
    public static final String SAME_TABLE_ROWS = """
            <table id="clientes">
              <thead><tr><th>Nome</th><th>Status</th><th>Ações</th></tr></thead>
              <tbody>
                <tr data-client="101"><td>Ana</td><td>Ativo</td><td><button id="excluir">Excluir</button></td></tr>
                <tr data-client="202"><td>Maria</td><td>Ativo</td><td><button id="excluir">Excluir</button></td></tr>
                <tr data-client="303"><td>João</td><td>Inativo</td><td><button id="excluir">Excluir</button></td></tr>
              </tbody>
            </table>
            """;

    /** Mesmas linhas reordenadas (João, Ana, Maria). */
    public static final String SAME_TABLE_ROWS_REORDERED = """
            <table id="clientes">
              <thead><tr><th>Nome</th><th>Status</th><th>Ações</th></tr></thead>
              <tbody>
                <tr data-client="303"><td>João</td><td>Inativo</td><td><button id="excluir">Excluir</button></td></tr>
                <tr data-client="101"><td>Ana</td><td>Ativo</td><td><button id="excluir">Excluir</button></td></tr>
                <tr data-client="202"><td>Maria</td><td>Ativo</td><td><button id="excluir">Excluir</button></td></tr>
              </tbody>
            </table>
            """;

    /** §9 — card com contexto textual. */
    public static final String CARDS = """
            <section aria-label="Clientes">
              <div class="cliente"><h3>Cliente Ana</h3><p>Status: Ativo</p><button>Editar</button></div>
              <div class="cliente"><h3>Cliente Maria</h3><p>Status: Inativo</p><button>Editar</button></div>
            </section>
            """;

    /** Campos "Estado" de cobrança e entrega. */
    public static final String ADDRESS_FORMS = """
            <form id="checkout">
              <fieldset><legend>Endereço de cobrança</legend>
                <label for="estado-c">Estado</label><select id="estado-c" name="billing_state"><option>SP</option><option>RJ</option></select>
              </fieldset>
              <fieldset><legend>Endereço de entrega</legend>
                <label for="estado-e">Estado</label><select id="estado-e" name="shipping_state"><option>SP</option><option>RJ</option></select>
              </fieldset>
            </form>
            """;

    public static String dynamicId(String suffix) {
        return """
                <form id="cadastro">
                  <label for="customer-name-%1$s">Nome do cliente</label>
                  <input id="customer-name-%1$s" name="customerName">
                  <button type="submit">Salvar</button>
                </form>
                """.formatted(suffix);
    }
}
