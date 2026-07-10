# Agentes Hooks Specification

## Visão Geral
Os hooks (ganchos) são scripts ou extensões configurados nas ferramentas (IDE ou agentes de linha de comando) para disparar ações automáticas no Servidor MCP, sem a intervenção explícita do usuário. Eles alimentam a camada `raw/` passivamente e preparam o contexto passivamente.

## Requisitos Técnicos e Princípios
- Os scripts de hooks devem invocar as ferramentas da base (por CLI ou chamando o serviço) assincronamente sempre que possível (`async: true`).
- A falha do hook não pode interferir na execução da ferramenta do usuário (**Fail-Open**).

## Hooks do Claude Code

1. **`SessionStart`**
   - **Gatilho:** Inicialização do Claude Code em um projeto.
   - **Ação:** Consulta o Cortex (`query index` ou `query rules`) e injeta os resumos e regras vitais como `additionalContext` para dentro da memória em tempo real da sessão do Claude.

2. **`Stop`**
   - **Gatilho:** Fim da sessão ou suspensão pelo usuário.
   - **Ação:** Coleta o histórico/sumário e realiza uma chamada `capture` para a pasta `raw/` do projeto associado, catalogando as decisões geradas naquela sessão.
   - **Prevenção de Loop:** O script deve checar um flag ou `stop_hook_active` para evitar que a ação de resumir triga novamente um ciclo infinito.

3. **`PreCompact`**
   - **Gatilho:** Antes do Claude descartar tokens de contexto longos para economizar memória na janela de atenção.
   - **Ação:** Extrai os pontos principais e registra no Cortex, funcionando como um dump de preservação de estado.

4. **`SessionEnd`** *(Opcional)*
   - Apenas marca log de encerramento sem bloqueio.

## Hooks do Antigravity
A integração com o Antigravity (Google SDK) deve buscar equivalência funcional com o ciclo do Claude.

- Como a plataforma evolui rápido, os nomes dos eventos podem variar (ex: `onSessionStart`, `onToolPreCall`, `onErrorRecovery`). 
- O contrato de dados (saída para `raw/`) deve obrigatoriamente se manter idêntico ao do Claude para garantir compatibilidade total na leitura do diretório pelas ferramentas MCP de abstração.
- O mapeamento exato dos hooks de lifecycle da Antigravity deverá ser documentado e atualizado conforme a adoção das APIs do SDK.
