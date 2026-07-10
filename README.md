# Cortex - Sistema de Memória e Wiki

O **Cortex** é um sistema de memória ativa e wiki pessoal, projetado para servir como o "cérebro" de longo prazo para agentes de IA (como Claude Code e Antigravity). Ele combina os padrões de *LLM Wiki* e *ai-memory*, permitindo que os agentes leiam convenções, registrem decisões, documentem *gotchas* e consolidem conhecimento ao longo do tempo.

## 🚀 Arquitetura

O sistema foi refatorado para centralizar suas operações em uma API unificada.

- **`cortex-core`**: Lógica de domínio, gerenciamento de arquivos markdown, e operações de busca.
- **`cortex-api`**: Aplicação Spring Boot que atua como interface principal.
  - Expõe endpoints REST tradicionais.
  - **Integra o Servidor MCP (Model Context Protocol)** nativamente, comunicando-se via HTTP Server-Sent Events (SSE) no endpoint `/mcp/sse`.

## 📦 Como rodar

A infraestrutura inteira roda empacotada em Docker. O `cortex-api` expõe a porta `8080`.

```bash
# Sobe a API e o servidor MCP integrados
docker-compose up -d --build cortex-api
```

### Configuração do MCP (Antigravity / Claude)

Para conectar um agente MCP ao Cortex, basta apontar para o endpoint SSE da API:

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

## 🛠️ Ferramentas Disponíveis no MCP

O Cortex expõe as seguintes ferramentas para o agente gerenciar a memória do projeto:

- `capture`: Captura informações brutas (raw).
- `query`: Busca informações na memória do projeto.
- `write_page`: Escreve uma página curada na wiki, consolidando contexto bruto.
- `promote_rules`: Extrai as regras ativas da base de memória do Cortex e as injeta no contexto da IDE.
- `lint`: Verifica a saúde da base do Cortex, detectando referências inválidas.
- `bootstrap`: Inicializa um projeto completo no Cortex com estrutura de diretórios e Skills.
- `handoff`: Gera um relatório consolidado para transferência de contexto entre agentes.

## 📂 Estrutura da Memória

Os dados do Cortex ficam salvos no host (mapeados pelo Docker) na pasta raiz do projeto alvo sob a estrutura `.agents/`. Todas as memórias são arquivos `.md` simples.
