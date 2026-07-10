# Orquestração de Agentes (SDD)

Este documento descreve os papéis dos agentes que interagem no ecossistema Cortex. Diferente de projetos normais, o Cortex atua **para** agentes e também é **desenvolvido por** agentes.

## Papéis no Desenvolvimento do Cortex (Self-Build)
Para a construção do próprio sistema Cortex, os agentes devem agir como **Engenheiros Java**, seguindo a arquitetura definida (Java 21, Picocli, MCP Java SDK).
- Eles devem seguir o ciclo SDD: consultar specs na pasta `docs/specs`, atualizar specs antes do código.

## Interação de Agentes com o Sistema Cortex (Runtime)
Quando o Cortex estiver em execução servindo outros projetos, os agentes atuarão como **Clientes do Cortex**:

### Claude Code e Antigravity
- São acionados por **Hooks** injetados no ciclo de vida (SessionStart, Stop, PreCompact).
- Carregam o contexto inicial do `index.md` e regras através da injeção no `additionalContext` (ou equivalente).
- Possuem acesso às ferramentas MCP do Cortex (`cortex-mcp`) para consultar a memória, ler fatos do projeto e registrar (capturar) novas informações que considerem úteis durante a sessão.

### Ações de Handoff
- Um agente pode utilizar a ferramenta `handoff` para gerar um estado sumarizado de sua operação atual e delegar ou passar o contexto para outro agente.

### Consolidação (Curadoria de Wiki)
- Os agentes usam a skill (convenções) de consolidação e a ferramenta `consolidate`. Eles decidem como as observações brutas em `raw/` devem se agrupar e resultar em páginas curadas ou regras ativas (`write_page`, `promote_rule`).
