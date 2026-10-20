---
spec: 003
titulo: Mapeamento de volume Docker e resolução de caminhos restritos a G:\Dev\Projetos
status: implementada
atualizado: 2026-10-17
---
# 003 — Mapeamento de volume Docker e resolução de caminhos restritos a G:\Dev\Projetos

## Objetivo
Alinhar o container `cortex-api` e a resolução de caminhos de `bootstrap` e `promote_rules` para operar exclusivamente sobre o diretório de projetos `G:\Dev\Projetos` (no Windows e `/mnt/g/Dev/Projetos` no WSL), eliminando o mapeamento legado para `F:\Dev\Projetos`.

## Contexto
O ambiente de desenvolvimento migrou os repositórios para `G:\Dev\Projetos` (acessado como `G:\Dev\Projetos` pelo Codex no Windows e `/mnt/g/Dev/Projetos` pelo Antigravity no WSL), mas a infraestrutura e os caminhos persistidos no Cortex permaneceram apontando para o drive `F:\`:

1. **Volume incorreto no `docker-compose.yml` (`docker-compose.yml:15`)**:
   O serviço `cortex-api` monta `- F:\Dev\Projetos:/projetos`. Como os projetos ativos estão em `G:\Dev\Projetos`, o container não enxerga nenhum repositório do workspace atual em `/projetos`.

2. **Ausência de tradução de caminho host $\rightarrow$ container em `ProjectRepository` (`ProjectRepository.java:481, 529`)**:
   Quando o Codex (Windows) ou o Antigravity (WSL) invoca `bootstrap` ou `promote_rules` enviando `root_path` ou `file` com o caminho do host (`G:\Dev\Projetos\<repo>`, `g:/Dev/Projetos/<repo>` ou `/mnt/g/Dev/Projetos/<repo>`), `ProjectRepository` executa `Paths.get(rootPath)` diretamente dentro do container Linux:
   - Se o caminho vem em formato Windows (`G:\Dev\Projetos\foo`), o Linux interpreta a string inteira como um nome de pasta relativo contendo barras invertidas (`\`) e cria diretórios inválidos dentro do diretório de trabalho do container.
   - Se o caminho vem do WSL (`/mnt/g/Dev/Projetos/foo`), o diretório `/mnt/g` não existe dentro do container, pois o volume é montado em `/projetos`.

3. **Registros legados em `config.yaml` (`config.yaml:3-13`)**:
   O arquivo `config.yaml` na raiz do repositório ainda lista caminhos `F:\Dev\Projetos\...` e `f:/Dev/Projetos/...`.

## Comportamento esperado
- O arquivo `docker-compose.yml` mapeia exclusivamente `G:\Dev\Projetos:/projetos` para o container `cortex-api` (removendo qualquer referência a `F:\Dev\Projetos`).
- Ao receber um caminho de projeto ou arquivo em `bootstrap(projectId, rootPath)` e `promoteRules(projectId, targetPath)`, o Cortex normaliza o caminho antes de acessar o sistema de arquivos:
   - Quando executado no ambiente containerizado (onde `/projetos` está montado e o caminho original do host não existe localmente), prefixos `G:\Dev\Projetos\`, `G:/Dev/Projetos/` (case-insensitive) e `/mnt/g/Dev/Projetos/` são traduzidos automaticamente para `/projetos/`.
   - Quando o caminho original já existe no sistema de arquivos local (execução nativa fora do Docker), ele é utilizado diretamente sem tradução.
- Caminhos pertencentes a drives não mapeados (como `F:\Dev\Projetos\...` ou `/mnt/f/Dev/Projetos/...`) não são traduzidos para `/projetos`, e qualquer caminho Windows com barras invertidas (`\`) ou letra de drive (`X:\`) recebido em ambiente Linux sem correspondência válida é recusado com `IllegalArgumentException`, impedindo a criação acidental de pastas com `\` no nome.
- O arquivo `config.yaml` do repositório tem os mapeamentos de projetos atualizados para `G:\Dev\Projetos` e `/projetos`.

## Critérios de aceite
> Cada item é uma afirmação verificável — algo que alguém pode reprovar.

- **CA-1:** O arquivo `docker-compose.yml` declara `- G:\Dev\Projetos:/projetos` na seção `volumes` do serviço `cortex-api` e não contém nenhuma referência a `F:\Dev\Projetos`.
- **CA-2:** Quando o diretório base `/projetos` está configurado como raiz de montagem do container, os caminhos `G:\Dev\Projetos\cortex`, `g:/Dev/Projetos/cortex` e `/mnt/g/Dev/Projetos/cortex` são resolvidos para `/projetos/cortex` tanto em `bootstrap` quanto em `promoteRules`.
- **CA-3:** Um caminho apontando para outro drive não mapeado (ex.: `F:\Dev\Projetos\cortex` ou `/mnt/f/Dev/Projetos/cortex`) não é traduzido para `/projetos/cortex` e, quando inexiste no sistema de arquivos local em ambiente Linux, é recusado com `IllegalArgumentException` sem criar diretório contendo `\` ou `F:` no disco.
- **CA-4:** Quando executado fora do container (onde o caminho informado existe diretamente no sistema de arquivos local, como `/mnt/g/Dev/Projetos/cortex` no WSL ou um diretório temporário de teste), `bootstrap` e `promoteRules` operam diretamente no caminho informado sem exigir a existência de `/projetos`.
- **CA-5:** Em `promoteRules`, se `file` for informado como caminho relativo (ex.: `.agents/AGENTS.md`) e o projeto possuir `root_path` registrado no `CortexConfig`, o arquivo é resolvido a partir do diretório raiz traduzido do projeto (`/projetos/<projeto>/.agents/AGENTS.md`).

## Fora de escopo
- Mapeamento simultâneo de `F:\Dev\Projetos` ou de múltiplos drives — descartado por decisão explícita de manter apenas `G:\Dev\Projetos`.
- Correções de transporte HTTP/SSE do MCP — tratadas na **Spec 001**.
- Execução assíncrona do `git push` e unificação entre `CortexMcpServer` e `McpConfig` — tratadas na **Spec 002**.

## Invariantes tocadas
- **Princípios de Memória LLM Wiki (`.agents/AGENTS.md` — Regra 4: Sem Infraestrutura Desnecessária / KISS)**: O mapeamento usa um único bind mount direto no `docker-compose.yml` e resolução determinística de `Path` na biblioteca padrão do Java, sem dependências adicionais.

## Testes que cobrem esta spec
- `cortex-core/src/test/java/contract/Spec003AlinhamentoDockerCaminhosTest.java` → CA-1, CA-2, CA-3, CA-4, CA-5

## Notas
Implementada em `ProjectRepository.resolveProjectPath()` e `docker-compose.yml`.

