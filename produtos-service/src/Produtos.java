import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class Produtos {
    // Este serviço é o único dono do catálogo.
    static final Map<Integer, String> catalogo = Map.of(
        1, "{\"id\":1,\"nome\":\"Caderno\",\"precoCentavos\":1500}",
        2, "{\"id\":2,\"nome\":\"Caneta\",\"precoCentavos\":300}");

    public static void main(String[] args) throws Exception {
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 8081), 0);
        servidor.createContext("/", e -> {
            String caminho = e.getRequestURI().getPath();
            if (!caminho.startsWith("/produtos/")) {
                responder(e, 404, "{\"erro\":\"Rota inexistente\"}"); return;
            }
            if (!e.getRequestMethod().equals("GET")) {
                e.getResponseHeaders().set("Allow", "GET");
                responder(e, 405, "{\"erro\":\"Use GET\"}"); return;
            }
            try {
                int id = Integer.parseInt(caminho.substring("/produtos/".length()));
                String produto = catalogo.get(id);
                responder(e, produto == null ? 404 : 200,
                    produto == null ? "{\"erro\":\"Produto inexistente\"}" : produto);
            } catch (NumberFormatException ex) {
                responder(e, 400, "{\"erro\":\"ID deve ser inteiro\"}");
            }
        });
        servidor.start();
        System.out.println("Produtos em http://localhost:8081");
    }
    static void responder(HttpExchange e, int status, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.sendResponseHeaders(status, bytes.length);
        try (var saida = e.getResponseBody()) { saida.write(bytes); }
        System.out.println(e.getRequestMethod() + " " + e.getRequestURI() + " -> " + status);
    }
}
