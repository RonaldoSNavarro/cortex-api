# Contexto do Projeto (Cortex)

## Visão Geral
O **Cortex** é uma plataforma de memória contínua e wiki viva que combina o padrão *LLM Wiki* (Karpathy) com o padrão *ai-memory* (Akita). O sistema foi construído em **Java 25** e **Spring Boot 4.1.0** e atua como servidor MCP integrado (HTTP SSE no endpoint `/mcp/sse` e stdio) e API REST unificada (`POST /api/execute`).

Ele atua em dois papéis principais:
1. **Wiki de Conhecimento**: uma base em Markdown mantida e enriquecida por agentes de IA (sintetiza, cura páginas, indexa referências cruzadas via wikilinks e monitora dependências).
2. **Memória de Longo Prazo para Agentes de Código**: captura contínua de decisões, fatos, regras e gotchas durante sessões de desenvolvimento (Google Antigravity, Claude Code, OpenAI Codex), permitindo handoff cirúrgico sem perda de contexto.

---

## Capacidades Implementadas
- **Armazenamento 100% em Markdown**: Arquivos simples legíveis por humanos e versionados no Git.
- **Motor de Busca Lexical In-Memory (BM25)**: Ranking ponderado ($k_1=1.2, b=0.75$), tokenização CamelCase, filtros facetados combináveis (`type:`, `tags:`, `status:`) e snippets contextuais.
- **Grafo de Conhecimento & Wikilinks**: Referências bidirecionais `[[link]]`, índice de backlinks e análise preditiva de impacto em páginas dependentes quando uma página antiga é substituída (`supersedes`).
- **Cache Reativo via Java NIO WatchService & Virtual Threads**: Sincronização em tempo real do índice e grafo em threads virtuais do Java 25 (`Thread.ofVirtual()`).
- **Smart Synthesizer**: Agrupamento automático de notas brutas em clusters temáticos na ferramenta `consolidate`.
- **Linting Semântico**: Detecção de *dangling wikilinks* e débitos de consolidação.
- **Observabilidade & Stats**: Dashboard e métricas de saúde da memória (`stats`).
- **Servidor MCP SSE com Bridging**: Suporte total a clientes estritos com resposta JSON-RPC no corpo do HTTP POST e `FAIL_ON_UNKNOWN_PROPERTIES = false`.
- **API REST Unificada**: Endpoint `POST /api/execute` suportando todos os comandos.
- **CLI Java**: Interface de terminal com Picocli 4.7.x.

---

## Arquitetura em Camadas
1. **Agentes de IA** (Google Antigravity, Claude Code, OpenAI Codex)
2. **Camada de Transporte & Protocolos** (MCP SSE em `/mcp/sse`, MCP stdio, REST em `/api/execute`, CLI)
3. **Núcleo de Inteligência e Domínio** (`cortex-core`: BM25, KnowledgeGraph, WatchService, SmartSynthesizer)
4. **Armazenamento e Persistência** (`~/.cortex/projects/<project-id>/` em arquivos Markdown versionados no Git)
