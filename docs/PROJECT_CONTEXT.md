# Contexto do Projeto (Cortex)

## Visão Geral
O Cortex é uma plataforma única que combina o padrão *LLM Wiki* (Karpathy) com o padrão *ai-memory* (Akita), projetado para ser servido como um servidor MCP para agentes (como Claude Code e Antigravity) e suportando captura automática via hooks.

Ele atua em dois papéis principais:
1. **Wiki de conhecimento**: uma base de conhecimento em markdown mantida pela própria IA (sintetiza, cura páginas, indexa).
2. **Memória de agente de código**: captura contínua de decisões, fatos, regras e gotchas durante sessões de desenvolvimento, permitindo handoff entre diferentes agentes sem perda de contexto.

## Escopo do MVP
- Armazenamento 100% em arquivos markdown (sem banco de dados ou infraestrutura externa).
- Busca textual simples (grep/keyword).
- Servidor MCP em Java (stdio) expondo ferramentas essenciais.
- CLI Java para operações manuais (init, ingest, query, lint, consolidate).
- Hooks para Claude Code (SessionStart, Stop, PreCompact) e Antigravity.
- Isolamento de escopo por projeto, via diretório de wiki isolado.
- Promoção de regras duráveis para os arquivos de agentes (CLAUDE.md / AGENTS.md).

## Fora do Escopo do MVP (Evolução Futura)
- Busca híbrida (embeddings/vetores).
- Banco de dados (SQLite FTS5, Lucene).
- Interface Web de navegação.
- Multiusuário/Autenticação.
- Retenção/Decay automático de memórias.
- Sincronização remota/nuvem.

## Arquitetura em Camadas
1. **Agentes de IA** (Claude Code, Antigravity)
2. **Camada de Captura** (Hooks automáticos e CLI manual)
3. **Servidor MCP Java** (Exposição de ferramentas: capture, query, consolidate, write_page, etc.)
4. **Armazenamento** (Wiki baseada em arquivos `.md`, fonte da verdade)

## Roadmap Incremental Sugerido
O projeto deve ser construído nas seguintes fases para garantir validação progressiva:
- **Fase 0 — Esqueleto:** Estrutura de diretórios, `schema.md`, CLI `init/ingest/query` operando só em arquivos (sem MCP).
- **Fase 1 — Servidor MCP:** Expor `capture/query/lint` via stdio e testar com Claude Code apontando para ele.
- **Fase 2 — Hooks (Básico):** `SessionStart` injetando contexto e `Stop` capturando o resumo bruto.
- **Fase 3 — Consolidação Assistida:** Ferramenta `consolidate` + `write_page` operacionais.
- **Fase 4 — Regras Duráveis:** `promote_rule` gravando em `CLAUDE.md`/`AGENTS.md`.
- **Fase 5 — Antigravity SDK:** Replicar hooks para suportar o agente Google, testando o handoff.
- **Fase 6 (Futuro):** Busca avançada (SQLite/Lucene) e UI web.

## Riscos e Decisões em Aberto
- **Antigravity SDK:** Sendo uma plataforma muito recente, os eventos de ciclo de vida (hooks) podem diferir da especificação e exigir adaptação.
- **Loop de Hooks `Stop`:** Um agente instruído a "resumir a sessão ao parar" pode gerar chamadas MCP infinitas. A prevenção com flags como `stop_hook_active` é crítica na implementação.
- **Conflito de Projetos:** Decidir na Fase 0 se o `<project-id>` é derivado do hash do caminho ou se deve ser nomeado manualmente pelo usuário no comando `cortex init`.
- **Consolidação Manual vs Automática:** No MVP a consolidação é sob demanda (via comando do usuário). A consolidação automática traz risco de degradação da base se não for acompanhada de validação humana.
