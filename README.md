# Cortex - Sistema de Memória e Wiki de IA

O **Cortex** é um sistema de memória ativa, grafo de conhecimento e wiki pessoal de alta performance, projetado para servir como o "cérebro" de longo prazo para agentes de IA (**Google Antigravity**, **Claude Code** e **OpenAI Codex**).

Ele combina os fundamentos de *LLM Wiki* (Andrej Karpathy) e *ai-memory* (Fabio Akita), permitindo que agentes leiam convenções, registrem decisões, documentem *gotchas*, naveguem por referências cruzadas e consolidem conhecimento continuamente sem depender de bancos vetoriais opacos ou fatiamento arbitrário de texto.

---

## 🚀 Arquitetura & Stack Tecnológica

O sistema foi construído sobre a plataforma moderna **Java 25** (`jdk-25.0.4`) e **Spring Boot 4.1.0**, utilizando **Virtual Threads** e estruturas de dados de alta concorrência:

- **`cortex-core`**: Núcleo de domínio e motor de inteligência:
  - **In-Memory BM25 Lexical Search**: Motor de busca lexical ponderado com cálculo BM25 ($k_1=1.2, b=0.75$), tokenização CamelCase e suporte a filtros facetados combináveis (`type:rule tags:mcp status:active`).
  - **Knowledge Graph & Wikilinks**: Grafo bidirecional em memória com suporte à sintaxe `[[wikilink]]`, indexação de backlinks e análise preditiva de impacto na substituição de páginas (`supersedes`).
  - **Reactive NIO Cache**: Monitoramento em tempo real do sistema de arquivos via Java NIO `WatchService` rodando em **Java 25 Virtual Threads** (`Thread.ofVirtual()`), mantendo latência $O(1)$ direta em memória.
  - **Smart Synthesizer**: Algoritmo de clustering semântico para agrupamento automático de notas brutas (`raw/`) por tópicos e co-ocorrência de tags.
  - **Semantic Linting & Observability**: Verificação de links quebrados (*dangling wikilinks*), conflitos de contexto e métricas do cérebro via [`CortexStats.java`](file:///g:/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/CortexStats.java).
- **`cortex-api`**: Aplicação Spring Boot 4.1.0 que atua como backend unificado:
  - Expõe endpoint REST unificado `POST /api/execute`.
  - **Servidor MCP SSE Integrado**: Transporte SSE nativo no endpoint `/mcp/sse` com suporte a *SSE POST Response Bridging* e tolerância total a capacidades customizadas de clientes (`FAIL_ON_UNKNOWN_PROPERTIES = false`).
- **`cortex-cli`**: Interface de linha de comando CLI desenvolvida com Picocli 4.7.x para operações diretas no terminal (`cortex init`, `cortex query`, `cortex stats`, `cortex lint`, `cortex consolidate`).
- **`cortex-mcp`**: Servidor MCP independente para transporte `stdio`.

---

## 📦 Como Executar

### Docker Compose (Recomendado)
A aplicação roda empacotada em container Docker com base **Eclipse Temurin 25 JDK**, expondo a porta `8080`:

```bash
# Constrói a imagem Java 25 e inicia o container
docker-compose up -d --build cortex-api
```

### Maven Local (Java 25)
```powershell
# Definir JAVA_HOME para o JDK 25
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.4"

# Compilar e empacotar
mvn clean package -DskipTests

# Executar a API Spring Boot 4.1.0
mvn spring-boot:run -pl cortex-api
```

---

## 🤖 Integração com Agentes de IA

### 1. Google Antigravity (AGY)
Configurado no arquivo [`.agents/mcp.json`](file:///g:/Dev/Projetos/cortex/.agents/mcp.json) e orientado pelas diretrizes de [`.agents/AGENTS.md`](file:///g:/Dev/Projetos/cortex/.agents/AGENTS.md):
```json
{
  "mcpServers": {
    "cortex": {
      "type": "sse",
      "url": "http://localhost:8080/mcp/sse"
    }
  }
}
```

### 2. Claude Code (Anthropic)
Configurado no arquivo [`.mcp.json`](file:///g:/Dev/Projetos/cortex/.mcp.json) com instruções em [`CLAUDE.md`](file:///g:/Dev/Projetos/cortex/CLAUDE.md):
```json
{
  "mcpServers": {
    "cortex": {
      "type": "sse",
      "url": "http://localhost:8080/mcp/sse"
    }
  }
}
```

### 3. OpenAI Codex
Configurado no arquivo [`.codex/config.toml`](file:///g:/Dev/Projetos/cortex/.codex/config.toml) e [`.codex/instructions.md`](file:///g:/Dev/Projetos/cortex/.codex/instructions.md):
```toml
[mcp_servers.cortex]
url = "http://127.0.0.1:8080/mcp/sse"
enabled = true
required = true
```

---

## 🛠️ Ferramentas MCP Expostas (9 Tools)

| Ferramenta | Entrada | Descrição |
|---|---|---|
| `query` | `project`, `terms`, `limit` (opcional) | Busca lexical ponderada (BM25) com suporte a filtros facetados (`type:rule tags:mcp status:active`) e snippets contextuais. |
| `capture` | `project`, `type`, `content` | Captura observações e notas brutas em `raw/` de forma rápida e atômica. |
| `write_page` | `project`, `type`, `content`, `tags`, `supersedes`, `consumed_raw_ids` | Cria ou atualiza uma página curada em `pages/` com suporte a wikilinks (`[[target]]`), gerenciando a cadeia de supersessão e alertando impactos em dependentes. |
| `consolidate` | `project`, `topic` (opcional) | Agrupa notas brutas por clusters temáticos e vocabulário, instruindo o agente a sintetizar 1 tópico por vez. |
| `stats` | `project` | Retorna dashboard markdown com métricas de saúde, nós e arestas do grafo, top tags e páginas mais referenciadas. |
| `lint` | `project` | Validador semântico: detecta *dangling wikilinks* (links quebrados), duplicações e débito de consolidação. |
| `promote_rules`| `project`, `file` | Extrai as regras ativas da base do Cortex e as injeta no contexto da IDE/Agente (`AGENTS.md` ou `CLAUDE.md`). |
| `bootstrap` | `project`, `rootPath` | Inicializa a estrutura da memória e o arquivo `schema.md` no projeto. |
| `handoff` | `project`, `target_agent` | Gera um resumo de estado condensado e cirúrgico para transferência de contexto entre sessões. |

---

## 📂 Estrutura de Diretórios da Memória

Os dados do Cortex são persistidos em `~/.cortex/projects/<project-id>/` e versionados em Git:
```
~/.cortex/
  config.yaml               # Mapeamento do diretório do projeto no disco para o ID
  projects/
    <project-id>/
      schema.md             # Definição de convenções de tipos e front-matter
      index.md              # Índice catalogado de tópicos
      log.md                # Log append-only das capturas
      raw/                  # Observações brutas pendentes de síntese
      pages/                # Páginas curadas e consolidadas em Markdown
      rules/                # Regras duráveis
```
