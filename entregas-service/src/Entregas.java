import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;

public class Entregas {
    // Armazenamento próprio em memória: só este serviço mexe neste mapa.
    static final ConcurrentHashMap<Integer, String> entregas = new ConcurrentHashMap<>();
    static final AtomicInteger sequencia = new AtomicInteger();
    // Cliente HTTP para falar com Pedidos (timeout de conexão de 2 s).
    static final HttpClient cliente = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();
    static final String PEDIDOS_URL = System.getenv().getOrDefault(
        "PEDIDOS_URL", "http://localhost:8082");

    public static void main(String[] args) throws Exception {
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 8083), 0);
        servidor.createContext("/", e -> {
            String caminho = e.getRequestURI().getPath();
            if (caminho.equals("/entregas")) {
                if (!e.getRequestMethod().equals("POST")) {
                    metodo(e, "POST"); return;
                }
                criar(e); return;
            }
            if (caminho.startsWith("/entregas/")) {
                if (!e.getRequestMethod().equals("GET")) {
                    metodo(e, "GET"); return;
                }
                consultar(e, caminho); return;
            }
            responder(e, 404, "{\"erro\":\"Rota inexistente\"}");
        });
        servidor.start();
        System.out.println("Entregas em http://localhost:8083");
    }

    // POST /entregas: corpo text/plain com apenas o ID do pedido.
    static void criar(HttpExchange e) throws IOException {
        // Lê no máximo 65 bytes: se vier mais de 64, o corpo é grande demais (413).
        byte[] bytes = e.getRequestBody().readNBytes(65);
        if (bytes.length > 64) {
            responder(e, 413, "{\"erro\":\"Corpo muito grande\"}"); return;
        }
        int pedidoId;
        try {
            pedidoId = Integer.parseInt(new String(bytes, StandardCharsets.UTF_8).trim());
            if (pedidoId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            responder(e, 400, "{\"erro\":\"Envie um ID inteiro positivo no corpo\"}"); return;
        }
        // Integração HTTP: pergunta ao serviço de Pedidos se o pedido existe.
        var requisicao = HttpRequest.newBuilder()
            .uri(URI.create(PEDIDOS_URL + "/pedidos/" + pedidoId))
            .timeout(Duration.ofSeconds(3)).GET().build();
        try {
            var resposta = cliente.send(requisicao, HttpResponse.BodyHandlers.ofString());
            if (resposta.statusCode() == 404) {
                // Pedido não existe: erro do cliente, nada é gravado.
                responder(e, 422, "{\"erro\":\"Pedido informado nao existe\"}"); return;
            }
            if (resposta.statusCode() != 200) {
                // Qualquer outro status remoto (400, 500...): Pedidos com problema, nada é gravado.
                responder(e, 503, "{\"erro\":\"Pedidos indisponivel\"}"); return;
            }
            // Só chega aqui com 200: gera o ID da entrega e grava no mapa local.
            int id = sequencia.incrementAndGet();
            String entrega = "{\"id\":" + id + ",\"pedidoId\":" + pedidoId
                + ",\"status\":\"AGUARDANDO_ENVIO\"}";
            entregas.put(id, entrega);
            e.getResponseHeaders().set("Location", "/entregas/" + id);
            responder(e, 201, entrega);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            responder(e, 503, "{\"erro\":\"Consulta interrompida\"}");
        } catch (IOException ex) {
            // Falha de rede (Pedidos parado, timeout): 503, nada é gravado.
            responder(e, 503, "{\"erro\":\"Pedidos indisponivel\"}");
        }
    }

    // GET /entregas/{id}: usa somente a memória local (não chama o serviço de Pedidos).
    static void consultar(HttpExchange e, String caminho) throws IOException {
        try {
            int id = Integer.parseInt(caminho.substring("/entregas/".length()));
            String entrega = entregas.get(id);
            responder(e, entrega == null ? 404 : 200,
                entrega == null ? "{\"erro\":\"Entrega inexistente\"}" : entrega);
        } catch (NumberFormatException ex) {
            responder(e, 400, "{\"erro\":\"ID deve ser inteiro\"}");
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
