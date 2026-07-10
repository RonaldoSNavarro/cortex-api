---
name: cortex-conventions
description: Regras globais de memória para o projeto Cortex (Automação de Fase 5). Envolve leitura de contexto no começo e escrita de descobertas/regras no final do trabalho usando Cortex MCP.
---

# Cortex Conventions (Skill Automática)

Você (Antigravity/Claude) está trabalhando em um projeto governado pelo **Cortex**, um sistema de memória e wiki com base em Markdown. 

## Regras de Execução de Tarefa

Sempre que você for iniciar uma nova tarefa ou investigar um bug complexo neste projeto, siga estas etapas:

### 1. Inicialização (Read Context)
Antes de começar a editar arquivos ou fazer planos, use a ferramenta `query` (exposta pelo CortexMcpServer) para buscar conhecimento ativo relevante ao seu objetivo. 
- Busque por páginas do tipo `rule` ou `gotcha` se achar que pode haver convenções específicas da base.
- Se o Cortex não estiver rodando via MCP, e se a CLI estiver instalada, use `cortex query --project=consorcio-api <busca>`.

### 2. Ao Concluir (Capture Context)
No fim da sua tarefa, se você tropeçou em um bug não documentado (Gotcha), tomou uma decisão técnica arquitetural (Decision) ou descobriu um fato novo importante para agentes futuros (Fact), use a ferramenta `capture` (exposta pelo CortexMcpServer).
- Exemplo: "Acabei de descobrir que o Jackson serializa de forma diferente no Windows. Vou salvar como um `gotcha` usando `capture`".
- Se você criar uma Regra absoluta que todo o projeto deve seguir, crie um `rule`.

### 3. Promoção de Regras
Se você alterar arquiteturas ou criar uma regra ouro (`rule`), e consolidá-la usando `write_page`, lembre-se de promover a regra em seguida usando a tool `promote_rules` ou executando o comando `cortex promote --project=cortex --file=.agents/AGENTS.md`. Isso injeta fisicamente as novas regras na IDE para acesso rápido no próximo boot.

**Atenção:** Siga a filosofia de que o Cortex é o cérebro persistente. Documente decisões no momento em que ocorrerem, não dependa de transcripts de chat.
