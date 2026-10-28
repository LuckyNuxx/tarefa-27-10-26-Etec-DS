#!/usr/bin/env bash
# Sobe Produtos, Pedidos e Entregas, executa todos os cenários da matriz de testes
# (inclusive parar/reiniciar serviços) e grava as evidências em docs/evidencias/.
# Uso (Linux/macOS/Git Bash), na raiz do repositório:  bash scripts/executar-testes.sh
# Requer JDK 17+ e curl. Portas 8081, 8082, 8083 e 9999 livres.
cd "$(dirname "$0")/.." || exit 1
RAIZ=$(pwd)
EV="$RAIZ/docs/evidencias"
mkdir -p "$EV"
SAIDA="$EV/resultado-testes.txt"
RESUMO="$EV/resumo.txt"
: > "$SAIDA"; : > "$RESUMO"
JAVA="java --add-modules jdk.httpserver"
PIDS=()

sobe() {  # sobe NOME ARQUIVO PORTA [VAR=VALOR]
  local nome=$1 arq=$2 porta=$3; shift 3
  ( cd "$RAIZ/$nome/src" && env "$@" $JAVA "$arq.java" >> "$EV/log-$arq.txt" 2>&1 ) &
  echo $! > "$EV/.pid-$arq"
  for _ in $(seq 1 60); do
    curl -s -o /dev/null --max-time 1 "http://localhost:$porta/x" && return 0
    sleep 0.5
  done
  echo "FALHA ao subir $arq" >&2; exit 1
}
para() {  # para ARQUIVO  (mata o processo java do serviço)
  local pid; pid=$(cat "$EV/.pid-$1")
  pkill -P "$pid" 2>/dev/null; kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
  sleep 1
}
titulo() { printf '\n==================== %s ====================\n' "$*" | tee -a "$SAIDA"; }
# teste ID "descrição" STATUS_ESPERADO [args do curl...]
teste() {
  local id=$1 desc=$2 esperado=$3; shift 3
  printf '\n### %s - %s (esperado %s)\n$ curl -i %s\n' "$id" "$desc" "$esperado" "$*" >> "$SAIDA"
  local out; out=$(curl -s -i --max-time 8 "$@" | tr -d '\r')
  echo "$out" >> "$SAIDA"
  local obtido; obtido=$(echo "$out" | head -1 | awk '{print $2}')
  [ -z "$obtido" ] && obtido="sem resposta"
  local ok=FALHOU; [ "$obtido" = "$esperado" ] && ok=OK
  printf '%-5s | %-62s | esperado %-3s | obtido %-12s | %s\n' "$id" "$desc" "$esperado" "$obtido" "$ok" | tee -a "$RESUMO"
}
P=http://localhost:8082; E=http://localhost:8083; PR=http://localhost:8081
post() { echo "$@"; }
rm -f "$EV"/log-*.txt

sobe produtos-service Produtos 8081
sobe pedidos-service  Pedidos  8082
sobe entregas-service Entregas 8083

titulo "BLOCO A - fluxo principal e validações"
teste T01 "Criar pedido em Pedidos (pré-requisito)"            201 -X POST $P/pedidos -H "Content-Type: text/plain" --data "1"
teste T02 "Criar entrega para o pedido 1"                      201 -X POST $E/entregas -H "Content-Type: text/plain" --data "1"
teste T03 "Consultar a entrega 1"                              200 $E/entregas/1
teste T04 "Segunda entrega para o mesmo pedido (permitido)"    201 -X POST $E/entregas -H "Content-Type: text/plain" --data "1"
teste T05 "Consultar a entrega 2"                              200 $E/entregas/2
teste T06 "POST com texto 'abc'"                               400 -X POST $E/entregas -H "Content-Type: text/plain" --data "abc"
teste T07 "POST com ID 0"                                      400 -X POST $E/entregas -H "Content-Type: text/plain" --data "0"
teste T08 "POST com ID negativo (-5)"                          400 -X POST $E/entregas -H "Content-Type: text/plain" --data "-5"
teste T09 "POST com corpo vazio"                               400 -X POST $E/entregas -H "Content-Type: text/plain" --data ""
teste T10 "POST com pedido inexistente (999)"                  422 -X POST $E/entregas -H "Content-Type: text/plain" --data "999"
teste T11 "GET de entrega inexistente (999)"                   404 $E/entregas/999
teste T12 "GET com ID inválido (abc)"                          400 $E/entregas/abc
teste T13 "GET em /entregas (método incorreto)"                405 $E/entregas
teste T14 "POST em /entregas/1 (método incorreto)"             405 -X POST $E/entregas/1 -H "Content-Type: text/plain" --data "1"
teste T15 "Rota desconhecida"                                  404 $E/desconhecida
teste T16 "Corpo com mais de 64 bytes"                         413 -X POST $E/entregas -H "Content-Type: text/plain" --data "$(printf '1%.0s' $(seq 1 80))"

titulo "BLOCO B - Produtos parado (falha parcial que NÃO afeta Entregas)"
para Produtos
teste T17 "Com Produtos parado: criar entrega p/ pedido existente" 201 -X POST $E/entregas -H "Content-Type: text/plain" --data "1"
teste T18 "Com Produtos parado: consultar a entrega criada"    200 $E/entregas/3

titulo "BLOCO C - Pedidos parado (503 na criação, GET local continua)"
para Pedidos
teste T19 "Com Pedidos parado: criar entrega"                  503 -X POST $E/entregas -H "Content-Type: text/plain" --data "1"
teste T20 "Com Pedidos parado: consultar entrega já criada (1)" 200 $E/entregas/1
teste T21 "Com Pedidos parado: consultar entrega já criada (3)" 200 $E/entregas/3

titulo "BLOCO D - Pedidos volta: o 503 não gravou nada nem consumiu ID"
sobe produtos-service Produtos 8081
sobe pedidos-service  Pedidos  8082
teste T22 "Recriar pedido (memória de Pedidos foi zerada)"     201 -X POST $P/pedidos -H "Content-Type: text/plain" --data "1"
teste T23 "Criar entrega: ID deve ser 4 (sem buraco pelo 503)" 201 -X POST $E/entregas -H "Content-Type: text/plain" --data "1"
grep -q '"id":4' <(sed -n '/^### T23/,/^### T24/p' "$SAIDA") && echo "      (confirmado: id 4, nenhuma entrega gravada durante o 503)" | tee -a "$RESUMO"

titulo "BLOCO E - Pedidos responde status inesperado (servidor falso na 9999 devolvendo 500)"
para Entregas
python3 -c '
import http.server
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(500); self.end_headers(); self.wfile.write(b"erro")
    def log_message(self,*a): pass
http.server.HTTPServer(("127.0.0.1",9999),H).serve_forever()' &
FAKE=$!
sleep 1
sobe entregas-service Entregas 8083 PEDIDOS_URL=http://localhost:9999
teste T24 "Pedidos devolve 500 -> Entregas responde 503"       503 -X POST $E/entregas -H "Content-Type: text/plain" --data "1"
teste T25 "Nada foi gravado: entrega 1 não existe"             404 $E/entregas/1
kill $FAKE 2>/dev/null; wait $FAKE 2>/dev/null
para Entregas

titulo "BLOCO F - Reiniciar Entregas: perda dos dados em memória"
sobe entregas-service Entregas 8083
teste T26 "Entrega 1 criada antes do reinício"                 404 $E/entregas/1
teste T27 "Entrega 4 criada antes do reinício"                 404 $E/entregas/4

para Entregas; para Pedidos; para Produtos
rm -f "$EV"/.pid-*
sed -i '/^Picked up JAVA_TOOL_OPTIONS/d' "$EV"/log-*.txt
echo; echo "Resumo:"; cat "$RESUMO"
echo; echo "Total OK: $(grep -c '| OK$' "$RESUMO") / $(grep -c '^T' "$RESUMO")"
