# Specs — Spec Driven Development (SDD)

Cada mudança de comportamento deste projeto nasce de uma **spec leve** aqui, **antes** do código.
A spec é a fonte dos **critérios de aceite** (`CA-n`), que orientam a implementação e a verificação.

Este arquivo é, além das regras, o **índice** — e o índice é o changelog do projeto.

## Fluxo

1. **Spec** — crie `specs/NNN-nome-da-feature.md` a partir do `_template.md`, com objetivo,
   contexto (com evidência), comportamento esperado e **critérios de aceite numerados**.
2. **Índice** — registre a spec na tabela abaixo. Spec fora do índice é spec que ninguém acha.
3. **Código** — implemente o comportamento descrito. Comportamento não previsto que aparecer
   durante a implementação volta para a spec, antes do merge.
4. **Verificação** — cada `CA-n` é verificado antes do merge: teste automatizado quando o critério
   toca invariante ou contrato público; verificação manual **documentada no PR** quando não valer
   o custo do teste.
5. **Fechamento** — status → `implementada`, seção *Testes que cobrem esta spec* preenchida,
   índice atualizado.
6. **Bump de versão** — ao marcar a spec como implementada, suba a versão do artefato para
   `0.<NNN>.0`, onde `NNN` é o número da spec. É o que faz a versão publicada responder
   "qual spec está no ar". Duas regras: (a) **a versão nunca anda para trás** — spec implementada
   fora de ordem incrementa o último dígito da versão corrente (`0.110.1`) em vez de usar o
   próprio número; (b) correção posterior sobre a mesma spec incrementa o último dígito.
   Se a linguagem tiver instalação editável ou cache de metadata, **reinstale depois do bump**.

## Regras

- **Sem spec, sem código.** Mudança de comportamento começa por editar ou criar a spec.
- Critérios de aceite são **verificáveis** — afirmações que alguém pode reprovar, não desejos.
- CAs que tocam **invariantes** do projeto exigem teste automatizado; os demais podem ser
  verificados manualmente, desde que o PR registre como.
- Specs são versionadas com o código e revisadas **no mesmo PR** da implementação.
- Numeração é sequencial e **nunca reutilizada** — nem quando a spec é descartada.
- Toda spec tem *Fora de escopo* preenchido.

> **Regime de teste: SDD + TDD estrito.** Cada `CA-n` vira um teste que **falha antes** da
> implementação (red → green). Quando o projeto relaxar esse ciclo, registre aqui em qual spec
> a transição aconteceu — senão o histórico fica incoerente para quem lê de trás para frente.

## Convenção de nomes

- Spec: `specs/001-captura-de-evento.md`
- Teste de contrato: `src/test/java/contract/Spec001CapturaDeEventoTest.java`
- Cada caso: `@Test void ca4_nomeDoComportamento()`, com Javadoc citando a spec.
- Roda só a spec: `mvn test -Dtest=Spec001CapturaDeEventoTest`.
- O cabeçalho do arquivo de teste lista, em uma linha cada, todos os CAs que ele cobre.
- Um CA pode ter mais de um teste (os dois sentidos de uma precedência, por exemplo); todos citam
  o mesmo número.

## Status

`rascunho` → `aprovada` → `implementada`. Use estados honestos quando for o caso:
`implementada (CA-N revertida)`, `descartada` (com motivo, arquivo mantido, número não reaproveitado).

## Índice de specs

| Spec | Título | Status | Testes |
|---|---|---|---|
| [001](001-estabilidade-transporte-mcp.md) | Falhas de protocolo, corrupção assíncrona e colisão de sessões no transporte HTTP/SSE do MCP | Implementada | `Spec001EstabilidadeTransporteMcpTest` |
| [002](002-git-sync-assincrono-e-unificacao-mcp.md) | Bloqueio síncrono de git push nas escritas da wiki e divergência entre servidores MCP HTTP e Stdio | Implementada | `Spec002GitSyncAssincronoTest`, `Spec002UnificacaoMcpTest` |
| [003](003-alinhamento-docker-e-config-clientes.md) | Mapeamento de volume Docker e resolução de caminhos restritos a G:\Dev\Projetos | Implementada | `Spec003AlinhamentoDockerCaminhosTest` |
| [004](004-seguranca-integridade-e-git-core.md) | Corrupção em promote_rules, vulnerabilidade de path traversal, bloqueio de subprocesso Git e inconsistências de formatação Markdown/YAML no core | Implementada | `Spec004SegurancaIntegridadeGitCoreTest` |
| [005](005-concorrencia-indices-watcher-e-ciclo-de-vida.md) | Condições de corrida em CortexConfigManager, ProjectWatcherService, índices em memória e ausência de shutdown gracioso de recursos | Implementada | `Spec005ConcorrenciaIndicesWatcherTest` |
| [006](006-alinhamento-cli-docker-hardening-e-padroes-spring.md) | Divergência de contratos na CLI, imagem Docker com JDK completo e ausência de padronização de injeção por construtor e Javadoc | Implementada | `Spec006AlinhamentoCliDockerPadroesTest` |
| [007](007-bloqueio-git-sync-por-locks-orfaos.md) | Bloqueio permanente da sincronização Git por arquivos de lock órfãos (.git/index.lock) após encerramento anormal | Implementada | `Spec007BloqueioGitSyncPorLocksOrfaosTest` |
