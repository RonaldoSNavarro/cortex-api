# AGENTS.md - Cortex Memory System Guidelines

Este repositório é governado pelas regras do **Cortex** e pelas definições contidas em `.agents/AGENTS.md`.

---

## 🛠️ Comandos Globais de Projeto

- **Build**: `mvn clean package -DskipTests`
- **Testes**: `mvn test`
- **Docker**: `docker-compose up -d --build cortex-api`
- **Servidor MCP SSE**: `http://localhost:8080/mcp/sse`

---

## 📋 Regras de Desenvolvimento & MCP Java

1. **Jackson Deserialization (`FAIL_ON_UNKNOWN_PROPERTIES = false`)**:
   Sempre configure instâncias de `ObjectMapper` do Jackson utilizadas no parsing de mensagens MCP com `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false`. Clientes MCP (como Go SDK ou Claude Code) enviam campos de capacidade customizados que não devem quebrar o handshake.

2. **Ponte de Resposta SSE POST**:
   No transporte SSE do MCP, interceptar POSTs do cliente e retornar a resposta JSON-RPC real no corpo da requisição HTTP POST (status 200 OK com payload JSON-RPC), pois bibliotecas clientes estritas requerem o payload no corpo do POST.

3. **Princípios de Memória LLM Wiki**:
   - Usar arquivos `.md` simples como unidades de conhecimento.
   - Proibido o uso de RAG vetorial baseado em chunking opaco.
   - Manter as memórias salvas no diretório `.agents/` legíveis por humanos e versionadas no Git.

---

Para mais regras específicas e skills ativas do projeto, consulte a pasta [.agents/](file:///g:/Dev/Projetos/cortex/.agents) e o arquivo [.agents/AGENTS.md](file:///g:/Dev/Projetos/cortex/.agents/AGENTS.md).
