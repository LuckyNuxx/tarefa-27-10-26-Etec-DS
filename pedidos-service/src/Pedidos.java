import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;

public class Pedidos {
    // Nenhum acesso ao mapa ou às classes de Produtos.
    static final ConcurrentHashMap<Integer, String> pedidos = new ConcurrentHashMap<>();
    static final AtomicInteger sequencia = new AtomicInteger();
    static final HttpClient cliente = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();
    static final String PRODUTOS_URL = System.getenv().getOrDefault(
        "PRODUTOS_URL", "http://localhost:8081");

    public static void main(String[] args) throws Exception {
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 8082), 0);
        servidor.createContext("/", e -> {
            String caminho = e.getRequestURI().getPath();
            if (caminho.equals("/pedidos")) {
                if (!e.getRequestMethod().equals("POST")) {
                    metodo(e, "POST"); return;
                }
                criar(e); return;
            }
            if (caminho.startsWith("/pedidos/")) {
                if (!e.getRequestMethod().equals("GET")) {
                    metodo(e, "GET"); return;
                }
                try {
                    int id = Integer.parseInt(caminho.substring("/pedidos/".length()));
                    String pedido = pedidos.get(id);
                    responder(e, pedido == null ? 404 : 200,
                        pedido == null ? "{\"erro\":\"Pedido inexistente\"}" : pedido);
                } catch (NumberFormatException ex) {
                    responder(e, 400, "{\"erro\":\"ID deve ser inteiro\"}");
                }
                return;
            }
            responder(e, 404, "{\"erro\":\"Rota inexistente\"}");
        });
        servidor.start();
        System.out.println("Pedidos em http://localhost:8082");
    }
    static void criar(HttpExchange e) throws IOException {
        // Corpo text/plain com apenas o ID: simplificação didática explícita.
        byte[] bytes = e.getRequestBody().readNBytes(65);
        if (bytes.length > 64) {
            responder(e, 413, "{\"erro\":\"Corpo muito grande\"}"); return;
        }
        int produtoId;
        try {
            produtoId = Integer.parseInt(new String(bytes, StandardCharsets.UTF_8).trim());
            if (produtoId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            responder(e, 400, "{\"erro\":\"Envie um ID inteiro positivo no corpo\"}"); return;
        }
        var requisicao = HttpRequest.newBuilder()
            .uri(URI.create(PRODUTOS_URL + "/produtos/" + produtoId))
            .timeout(Duration.ofSeconds(3)).GET().build();
        try {
            var resposta = cliente.send(requisicao, HttpResponse.BodyHandlers.ofString());
            if (resposta.statusCode() == 404) {
                responder(e, 422, "{\"erro\":\"Produto informado nao existe\"}"); return;
            }
            if (resposta.statusCode() != 200) {
                responder(e, 503, "{\"erro\":\"Catalogo indisponivel\"}"); return;
            }
            int id = sequencia.incrementAndGet();
            // Snapshot do produto no momento da compra; não é uma referência ao mapa remoto.
            String pedido = "{\"id\":" + id + ",\"produto\":" + resposta.body()
                + ",\"status\":\"CRIADO\"}";
            pedidos.put(id, pedido);
            e.getResponseHeaders().set("Location", "/pedidos/" + id);
            responder(e, 201, pedido);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            responder(e, 503, "{\"erro\":\"Consulta interrompida\"}");
        } catch (IOException ex) {
            responder(e, 503, "{\"erro\":\"Catalogo indisponivel\"}");
        }
    }
    static void metodo(HttpExchange e, String permitido) throws IOException {
        e.getResponseHeaders().set("Allow", permitido);
        responder(e, 405, "{\"erro\":\"Metodo nao permitido\"}");
    }
    static void responder(HttpExchange e, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.sendResponseHeaders(status, bytes.length);
        try (var saida = e.getResponseBody()) { saida.write(bytes); }
        System.out.println(e.getRequestMethod() + " " + e.getRequestURI() + " -> " + status);
    }
}
