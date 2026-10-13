package com.nekkora.extension.core.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlContextParserTest {

    private final UrlContextParser parser = new UrlContextParser();

    @Test
    void observedAndInferredAreSeparated() { // §14
        NavigationContext c = parser.parse("http://127.0.0.1:8080/clientes?nome=ana&status=ativo#lista", "Clientes");
        assertEquals("/clientes", c.observed().pathname());
        assertEquals(Map.of("nome", List.of("ana"), "status", List.of("ativo")), c.observed().query());
        assertEquals("http://127.0.0.1:8080", c.observed().origin());
        assertEquals("127.0.0.1", c.observed().hostname());
        assertEquals("8080", c.observed().port());
        assertEquals("lista", c.observed().fragment());
        assertEquals("clientes", c.inferred().screen());
        assertEquals(Map.of("nome", "ana", "status", "ativo"), c.inferred().filters());
        assertTrue(c.inferred().caveat().contains("não comprovam"));
    }

    @Test
    void paginationAndSortAreNotFilters() {
        NavigationContext c = parser.parse("http://localhost:8765/lab/pedidos/42?page=2&sort=data&cliente=Jo%C3%A3o+Silva", null);
        assertEquals("pedidos", c.inferred().screen());
        assertEquals(Map.of("page", "2"), c.inferred().pagination());
        assertEquals(Map.of("sort", "data"), c.inferred().sort());
        assertEquals(Map.of("cliente", "João Silva"), c.inferred().filters());
    }

    @Test
    void hashRoutingAndChanges() { // T022 / T023
        NavigationContext a = parser.parse("http://localhost:8765/spa.html#/clientes", "SPA");
        NavigationContext b = parser.parse("http://localhost:8765/spa.html#/pedidos", "SPA");
        assertEquals("clientes", a.inferred().screen());
        assertEquals("FRAGMENT_CHANGE", NavigationChange.between(a, b).kind());

        NavigationContext q1 = parser.parse("http://localhost/clientes?nome=ana", null);
        NavigationContext q2 = parser.parse("http://localhost/clientes?nome=maria&status=ativo", null);
        NavigationChange ch = NavigationChange.between(q1, q2);
        assertEquals("QUERY_CHANGE", ch.kind());
        assertEquals(java.util.Arrays.asList("ana", "maria"), ch.queryChanges().get("nome"));
        assertEquals(java.util.Arrays.asList(null, "ativo"), ch.queryChanges().get("status"));
        assertEquals("ROUTE_CHANGE", NavigationChange.between(q1, parser.parse("http://localhost/pedidos", null)).kind());
    }
}
