---
spec: 007
titulo: Bloqueio permanente da sincronização Git por arquivos de lock órfãos (.git/index.lock) após encerramento anormal
status: implementada
atualizado: 2026-10-18
---
# 007 — Bloqueio permanente da sincronização Git por arquivos de lock órfãos (.git/index.lock) após encerramento anormal

## Objetivo
Evitar que a sincronização assíncrona de memória falhe permanentemente quando o container ou o processo do Cortex for interrompido de forma anormal deixando arquivos de lock residuais (`.git/index.lock`), garantindo detecção, recuperação e remoção automática de stale locks sem intervenção manual e sem corromper operações legítimas em andamento.

## Contexto
Durante a operação do container `cortex-api` em `2026-10-18`, a sincronização assíncrona de memória falhou continuamente com exit code 128:

```text
2026-10-18T16:05:23.622-03:00 ERROR 6 --- [   virtual-1797] com.cortex.core.ProjectRepository        : Failed to sync to Git: Git command failed with exit code 128: fatal: Unable to create '/root/.cortex/.git/index.lock': File exists.

Another git process seems to be running in this repository, e.g.
an editor opened by 'git commit'. Please make sure all processes
are terminated then try again. If it still fails, a git process
may have crashed in this repository earlier:
remove the file manually to continue.
```

### Análise de Causa Raiz
1. O repositório central de memória do Cortex (`~/.cortex`) é persistido no host e montado via volume Docker (`- ~/.cortex:/root/.cortex`).
2. Se o processo JVM ou container for interrompido abruptamente (SIGKILL, parada do Docker, encerramento de sessão ou queda de energia) enquanto um comando Git (`git add`, `git commit` ou `git push`) estiver em execução, o Git deixa o arquivo de bloqueio `.git/index.lock` residual no sistema de arquivos.
3. Em `ProjectRepository.java:1040-1072`:
   - `syncToGit(String commitMsg)` executa `runCommand(cortexDir, "git", "add", ".")`.
   - O Git aborta imediatamente com `exit code 128` ao encontrar `.git/index.lock`.
   - Como a sincronização de memória ocorre em segundo plano via thread virtual (`scheduleGitSync`), as falhas se repetem indefinidamente a cada nova operação (`capture`, `writePage`, `promoteRules`, `consolidate`), deixando o repositório remoto desatualizado até que um operador intervenha manualmente com `rm .git/index.lock`.
4. Em `ProjectRepository.java:1052`, o diretório `cortexDir` é fixado em `Paths.get(System.getProperty("user.home"), ".cortex")`, ignorando o diretório base configurado na instância (`baseProjectsDir`), dificultando o isolamento de testes de contrato unitários do Git sync.

### Alternativas Descartadas
- *Excluir incondicionalmente `.git/index.lock` antes de qualquer comando Git*: Descartado. Se outro processo Git estiver em execução legítima concorrente, apagar o arquivo em milissegundos corrompe o índice do Git.
- *Script no `entrypoint` do Dockerfile para deletar `index.lock`*: Descartado. Apenas trataria o reinício a frio do container, não cobrindo comandos que travam ou sofrem timeout durante a execução da aplicação nem ambientes de desenvolvimento fora do Docker.

## Comportamento esperado
1. **Inspeção preventiva de Stale Locks**:
   - Antes de iniciar operações Git em `cortexDir`, o Cortex verifica se existe o arquivo `.git/index.lock`.
   - Se o arquivo existir, o sistema afere sua idade (tempo decorrido desde a última modificação).
   - Se a idade exceder o limiar de obsolescência configurado (`cortex.git.stale-lock-timeout-seconds`, padrão de 60 segundos), o lock é tratado como *stale* (órfão de processo morto), sendo removido de forma segura com registro em log no nível `WARN` (`"Stale Git lock file detected (age: {}s > threshold: {}s). Removing: {}"`).
   - Se a idade for inferior ao limiar, o lock é respeitado e a operação falha de forma transitória sem remoção prematura.
2. **Recuperação reativa pós-falha (Retry transparente)**:
   - Se um comando Git disparar erro contendo `index.lock': File exists` e o arquivo tiver idade superior ao timeout de comando (`gitCommandTimeoutSeconds`), o Cortex remove o lock órfão e realiza uma única tentativa de repetição da operação Git antes de propagar o erro.
3. **Resiliência e I/O seguro**:
   - Falhas ao tentar verificar ou excluir o lock (ex.: arquivos apagados concorrentemente ou restrições de permissão) são tratadas com mensagens claras em log sem propagar `UncheckedIOException` não capturada para fora do executor virtual.
4. **Isolamento de diretório no Core**:
   - A resolução de `cortexDir` deve utilizar um método auxiliar `getCortexDir()`, permitindo que testes com instâncias parametrizadas de `ProjectRepository(Path cortexHome)` apontem para diretórios temporários isolados.

## Critérios de aceite
> Cada item é uma afirmação verificável — algo que alguém pode reprovar.

- **CA-1:** Quando `syncToGit` é acionado e existe um arquivo `.git/index.lock` com tempo de modificação superior ao limiar configurado (`staleLockTimeoutSeconds`, padrão 60s), o arquivo é removido com log `WARN` e a sincronização Git (`git add`, `git commit`, `git push`) conclui com sucesso.
- **CA-2:** Quando existe um arquivo `.git/index.lock` recente (idade inferior ao limiar configurado), `syncToGit` **não** remove o arquivo de lock e aborta a sincronização de forma segura sem interferir em processos concorrentes.
- **CA-3:** Se um comando Git falhar com `exit code 128` indicando `index.lock': File exists` e o lock for mais antigo que `gitCommandTimeoutSeconds`, o sistema remove o arquivo de lock e repete o comando com sucesso (recuperação reativa).
- **CA-4:** Falhas ao tentar inspecionar ou excluir o arquivo de lock (ex.: erro de I/O simulado ou arquivo já excluído) são capturadas e logadas sem quebrar a execução da thread virtual em background.
- **CA-5:** O limiar de obsolescência é configurável através da propriedade `cortex.git.stale-lock-timeout-seconds` e da System property correspondente, adotando 60 segundos como valor padrão.
- **CA-6:** Em um repositório Git sem nenhum arquivo de lock presente, `syncToGit` completa normalmente sem atrasos ou tentativas espúrias de exclusão de arquivos (não-regressão).

## Fora de escopo
- Resolução de conflitos de merge ou rebase com branch remoto divergente (push rejeitado por non-fast-forward) — escopo de sincronização avançada bidirecional.
- Remoção de locks em repositórios de projetos individuais externos fora de `~/.cortex` (ex.: repositórios monitorados em `/projetos`).

## Invariantes tocadas
- **Princípios de Memória LLM Wiki #1 e #3 (Unidade Atômica Markdown e Versionamento Git)**: Preserva o versionamento automático dos arquivos Markdown de memória, impedindo que falhas em locks órfãos interrompam indefinidamente o espelhamento com o repositório Git remoto.

## Testes que cobrem esta spec
- `cortex-core/src/test/java/contract/Spec007BloqueioGitSyncPorLocksOrfaosTest.java` → CA-1, CA-2, CA-3, CA-4, CA-5, CA-6.

## Notas
- Originada a partir do diagnóstico de incidente no container `cortex-api` em `2026-10-18`, após interrupção anormal de processo no host.
