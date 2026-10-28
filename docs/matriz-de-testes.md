# Matriz de testes - serviço de Entregas

Todos os testes foram executados com os **três serviços reais** rodando (Produtos 8081, Pedidos 8082, Entregas 8083), usando o script `scripts/executar-testes.sh`. Cada linha tem a evidência no arquivo [`evidencias/resultado-testes.txt`](evidencias/resultado-testes.txt) (procure pelo ID, ex.: `### T19`). O resumo bruto está em [`evidencias/resumo.txt`](evidencias/resumo.txt) e os consoles dos serviços em `evidencias/log-*.txt`.

**Resultado final: 27 de 27 testes passaram.**

## Fluxo principal e validações

| ID | Cenário | Requisição | Esperado | Obtido | Evidência |
|---|---|---|---|---|---|
| T01 | Criar pedido (pré-requisito) | `POST :8082/pedidos` corpo `1` | 201 | 201 | `### T01` |
| T02 | Criar entrega para pedido existente | `POST :8083/entregas` corpo `1` | 201, `Location: /entregas/1`, `{"id":1,"pedidoId":1,"status":"AGUARDANDO_ENVIO"}` | 201, Location e corpo iguais ao esperado | `### T02` |
| T03 | Consultar entrega criada | `GET :8083/entregas/1` | 200 | 200 | `### T03` |
| T04 | Segunda entrega para o mesmo pedido | `POST :8083/entregas` corpo `1` | 201 (permitido na versão básica) | 201, id 2 | `### T04` |
| T05 | Consultar segunda entrega | `GET :8083/entregas/2` | 200 | 200 | `### T05` |
| T06 | Entrada inválida (texto) | `POST` corpo `abc` | 400 | 400 | `### T06` |
| T07 | ID zero | `POST` corpo `0` | 400 | 400 | `### T07` |
| T08 | ID negativo | `POST` corpo `-5` | 400 | 400 | `### T08` |
| T09 | Corpo vazio | `POST` corpo vazio | 400 | 400 | `### T09` |
| T10 | Pedido inexistente | `POST` corpo `999` | 422, sem gravar | 422 | `### T10` |
| T11 | Entrega inexistente | `GET /entregas/999` | 404 | 404 | `### T11` |
| T12 | ID inválido na consulta | `GET /entregas/abc` | 400 | 400 | `### T12` |
| T13 | Método incorreto em `/entregas` | `GET /entregas` | 405 + `Allow: POST` | 405, `Allow: POST` | `### T13` |
| T14 | Método incorreto em `/entregas/{id}` | `POST /entregas/1` | 405 + `Allow: GET` | 405, `Allow: GET` | `### T14` |
| T15 | Rota desconhecida | `GET /desconhecida` | 404 | 404 | `### T15` |
| T16 | Corpo maior que 64 bytes | `POST` com 80 caracteres | 413 | 413 | `### T16` |

## Falhas parciais (demonstração obrigatória)

| ID | Cenário | Passo manual no IntelliJ | Esperado | Obtido | Evidência |
|---|---|---|---|---|---|
| T17 | Só **Produtos** parado, criar entrega para pedido existente | Stop em Produtos | 201 (Entregas só fala com Pedidos, e Pedidos não precisa de Produtos para consultar um pedido já salvo) | 201 | `### T17` |
| T18 | Consultar entrega criada com Produtos parado | - | 200 | 200 | `### T18` |
| T19 | **Pedidos** parado, criar entrega | Stop em Pedidos | 503 | 503 `{"erro":"Pedidos indisponivel"}` | `### T19` |
| T20 | Pedidos parado, consultar entrega 1 já criada | - | 200 (GET usa só a memória local) | 200 | `### T20` |
| T21 | Pedidos parado, consultar entrega 3 já criada | - | 200 | 200 | `### T21` |
| T22 | Pedidos volta (memória dele zerada): recriar pedido | Run em Produtos e Pedidos | 201 | 201 | `### T22` |
| T23 | Criar entrega: o 503 anterior não gravou nada nem gastou ID | - | 201 com `id` = 4 (sem "buraco") | 201, `id`: 4 | `### T23` |
| T24 | Pedidos responde status diferente de 200/404 (servidor falso devolvendo 500) | Entregas iniciado com `PEDIDOS_URL=http://localhost:9999` | 503 | 503 | `### T24` |
| T25 | Nada foi gravado no caso anterior | - | 404 em `GET /entregas/1` | 404 | `### T25` |
| T26 | Reiniciar Entregas: entrega 1 | Stop e Run em Entregas | 404 (memória perdida) | 404 | `### T26` |
| T27 | Reiniciar Entregas: entrega 4 | - | 404 | 404 | `### T27` |

Observação sobre T24/T25: o Pedidos real só devolve 200, 400, 404, 405 ou 413, então para provar o caso "outro status remoto" usamos um servidor falso em Python que sempre responde 500 (o script faz isso sozinho).

## Como reproduzir

```bash
bash scripts/executar-testes.sh
```

O script sobe os serviços, para e reinicia quando preciso, e regrava a pasta `docs/evidencias/`. Se preferir fazer à mão no IntelliJ, use `entregas-service/testes.http` (instruções no README).
