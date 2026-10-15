package com.nekkora.extension.host.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Locale;
import java.util.Set;

/**
 * Arquivo de descoberta publicado pelo Studio ({@code studio-bridge.json}): versão do protocolo, transporte, endereço
 * local, identidade da instância e o segredo EFÊMERO do emparelhamento (válido só enquanto aquela instância vive).
 *
 * <p>Antes de confiar no arquivo, o host confere que ele pertence ao usuário atual e que nenhum outro principal tem
 * acesso (ACL no Windows, permissões POSIX nos demais). Arquivo inseguro é recusado — a conexão não é tentada.
 */
public final class BridgeDiscovery {

    public static final String FILE_NAME = "studio-bridge.json";

    /** Conteúdo validado do arquivo de descoberta. */
    public record Endpoint(String transport, String address, String instanceId, long pid, byte[] secret, long modifiedAt) {
        public boolean unix() {
            return "unix".equals(transport);
        }
    }

    private final Path directory;
    private final ObjectMapper mapper;

    public BridgeDiscovery(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    /**
     * Diretório de descoberta (o MESMO algoritmo do Studio): propriedade {@code nekkora.bridge.dir}, variável
     * {@code NEKKORA_BRIDGE_DIR}, {@code %LOCALAPPDATA%\Nekkora\Studio\bridge} no Windows, senão
     * {@code $XDG_RUNTIME_DIR/nekkora-studio} ou {@code ~/.local/share/nekkora/studio/bridge}.
     */
    public static Path defaultDirectory() {
        String explicit = System.getProperty("nekkora.bridge.dir");
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getenv("NEKKORA_BRIDGE_DIR");
        }
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            Path base = local != null && !local.isBlank() ? Path.of(local) : Path.of(System.getProperty("user.home"), "AppData", "Local");
            return base.resolve("Nekkora").resolve("Studio").resolve("bridge");
        }
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        if (runtime != null && !runtime.isBlank()) {
            return Path.of(runtime, "nekkora-studio");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "nekkora", "studio", "bridge");
    }

    public Path directory() {
        return directory;
    }

    public Path file() {
        return directory.resolve(FILE_NAME);
    }

    /** Lê e valida o arquivo; {@code null} quando não há Studio publicado. */
    public Endpoint read() throws IOException {
        Path f = file();
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        assertPrivate(f);
        JsonNode n = mapper.readTree(Files.readAllBytes(f));
        if (!BridgeAuth.PROTOCOL.equals(n.path("protocol").asText())) {
            throw new BridgeException("PROTOCOL_UNSUPPORTED", "arquivo de descoberta com protocolo " + n.path("protocol").asText());
        }
        String transport = n.path("transport").asText();
        String address = n.path("address").asText();
        String instanceId = n.path("instanceId").asText();
        if (!("unix".equals(transport) || "tcp".equals(transport)) || address.isBlank() || !instanceId.matches("[A-Za-z0-9._:-]{1,80}")) {
            throw new BridgeException("BAD_REQUEST", "arquivo de descoberta incompleto");
        }
        if ("tcp".equals(transport)) {
            assertLoopback(address);
        }
        return new Endpoint(transport, address, instanceId, n.path("pid").asLong(-1), BridgeAuth.decodeSecret(n.path("secret").asText()),
                Files.getLastModifiedTime(f).toMillis());
    }

    /** TCP só em loopback (127.0.0.1 / ::1): o Studio nunca é procurado na rede. */
    static void assertLoopback(String address) throws BridgeException {
        int colon = address.lastIndexOf(':');
        String host = colon > 0 ? address.substring(0, colon) : address;
        try {
            if (!InetAddress.getByName(host.replace("[", "").replace("]", "")).isLoopbackAddress() || !host.matches("[0-9.:\\[\\]a-fA-F]+")) {
                throw new BridgeException("BAD_REQUEST", "endereço TCP do Bridge fora do loopback");
            }
        } catch (java.net.UnknownHostException e) {
            throw new BridgeException("BAD_REQUEST", "endereço TCP do Bridge inválido");
        }
    }

    /** O arquivo pertence ao usuário atual e nenhum outro principal tem permissão de acesso. */
    static void assertPrivate(Path f) throws IOException {
        UserPrincipal owner = Files.getOwner(f, LinkOption.NOFOLLOW_LINKS);
        UserPrincipal me = f.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        if (!owner.equals(me)) {
            throw new BridgeException("AUTH_FAILED", "arquivo de descoberta não pertence ao usuário atual");
        }
        AclFileAttributeView acl = Files.getFileAttributeView(f, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            for (AclEntry e : acl.getAcl()) {
                if (e.type() == AclEntryType.ALLOW && !e.principal().equals(owner)) {
                    throw new BridgeException("AUTH_FAILED", "arquivo de descoberta acessível a outro principal");
                }
            }
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(f, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> p = posix.readAttributes().permissions();
            for (PosixFilePermission x : p) {
                if (x.name().startsWith("GROUP") || x.name().startsWith("OTHERS")) {
                    throw new BridgeException("AUTH_FAILED", "arquivo de descoberta acessível a grupo/outros");
                }
            }
        }
    }
}
