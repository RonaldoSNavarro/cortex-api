# Cortex - Sistema de Memória e Wiki

O **Cortex** é um sistema de memória ativa e wiki pessoal, projetado para servir como o "cérebro" de longo prazo para agentes de IA (**Claude Code**, **Google Antigravity** e **OpenAI Codex**). Ele combina os padrões de *LLM Wiki* (Karpathy) e *ai-memory* (Akita), permitindo que os agentes leiam convenções, registrem decisões, documentem *gotchas* e consolidem conhecimento ao longo do tempo.

---

## 🚀 Arquitetura

O sistema centraliza suas operações em uma API unificada e em componentes reutilizáveis:

- **`cortex-core`**: Lógica de domínio, gerenciamento de arquivos markdown com frontmatter, e operações de busca.
- **`cortex-api`**: Aplicação Spring Boot que atua como interface principal.
  - Expõe endpoints REST tradicionais.
  - **Integra o Servidor MCP (Model Context Protocol)** nativamente via HTTP Server-Sent Events (SSE) no endpoint `/mcp/sse`.
- **`cortex-cli`**: Interface de linha de comando (PicoCLI) para operações diretas de memória e promoção de regras.
- **`cortex-mcp`**: Servidor MCP independente para transporte stdio.

---

## 📦 Como Rodar

### Docker Compose
A infraestrutura roda empacotada via Docker. O `cortex-api` expõe a porta `8080`:

```bash
# Sobe a API e o servidor MCP integrados
docker-compose up -d --build cortex-api
```

### Maven (Local)
```bash
# Compilar todos os módulos
mvn clean package -DskipTests

# Subir a API localmente
mvn spring-boot:run -pl cortex-api
```

---

## 🤖 Configuração de Conectividade com IAs

### 1. Claude Code (Anthropic)
O Claude Code lê automaticamente o arquivo [`CLAUDE.md`](file:///g:/Dev/Projetos/cortex/CLAUDE.md) e a autodescoberta MCP no arquivo [`.mcp.json`](file:///g:/Dev/Projetos/cortex/.mcp.json):
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

### 2. Google Antigravity (AGY)
O Antigravity lê o arquivo [`.agents/AGENTS.md`](file:///g:/Dev/Projetos/cortex/.agents/AGENTS.md), as regras do repositório em [`AGENTS.md`](file:///g:/Dev/Projetos/cortex/AGENTS.md) e o arquivo [`.agents/mcp.json`](file:///g:/Dev/Projetos/cortex/.agents/mcp.json) para carregar as 7 ferramentas MCP do Cortex.

### 3. OpenAI Codex / ChatGPT
O Codex utiliza o arquivo [`.codex/config.toml`](file:///g:/Dev/Projetos/cortex/.codex/config.toml) e [`.codex/instructions.md`](file:///g:/Dev/Projetos/cortex/.codex/instructions.md):
```toml
[mcp_servers.cortex]
url = "http://127.0.0.1:8080/mcp/sse"
enabled = true
required = true
```

---

## 🛠️ Ferramentas Disponíveis no MCP

O Cortex expõe 7 ferramentas via MCP para o agente gerenciar a memória do projeto:

- `capture`: Captura informações brutas (gotchas, decisões, fatos).
- `query`: Busca informações na memória do projeto.
- `write_page`: Escreve uma página curada na wiki, consolidando contexto.
- `promote_rules`: Extrai as regras ativas da base do Cortex e as injeta no contexto da IDE/Agente.
- `lint`: Verifica a saúde da base do Cortex, detectando links ou referências inválidos.
- `bootstrap`: Inicializa a estrutura da memória em um novo repositório.
- `handoff`: Gera um relatório de transferência de contexto entre sessões de agentes.

---

## 📂 Estrutura da Memória

Os dados do Cortex ficam salvos na pasta raiz do projeto alvo sob a estrutura `.agents/`. Todas as memórias são arquivos `.md` simples com frontmatter YAML legíveis por humanos e versionáveis no Git.
