# CLI Java Specification

## Visão Geral
A CLI do Cortex permite ao usuário interagir manualmente com o sistema de memória, caso queira acionar ingestão manual de conhecimento, iniciar um projeto, ou fazer manutenções ad-hoc. 

## Requisitos Técnicos
- Escrito em **Java 21**.
- Utiliza **Picocli** para definição de comandos e parse de argumentos, por permitir saídas coloridas, subcomandos e uma estrutura robusta de terminal.
- Reutiliza a camada de Domínio usada pelo Servidor MCP.

## Comandos

### `cortex init`
- **Descrição:** Inicializa um diretório do Cortex local (`~/.cortex`), se não existir.
- **Argumentos:** (pode receber opcionalmente `--project <nome> --path <caminho>`) para já realizar o bootstrap de um projeto.
- **Ação:** Cria a estrutura de base e o `config.yaml`.

### `cortex ingest`
- **Descrição:** Captura informações brutas a partir de arquivos externos ou urls.
- **Argumentos:** `<fonte>` (pode ser URL ou arquivo local), `--type <fact|rule|...>`, `--project <id>`.
- **Ação:** Formata o conteúdo e salva na pasta `raw/` simulando um hook manual.

### `cortex query`
- **Descrição:** Busca texto na wiki do projeto.
- **Argumentos:** `"<termos de busca>"` `--project <id>`.
- **Ação:** Retorna snippets formatados no terminal encontrados na pasta `pages/`. Executa varredura simples via `Files.walk` + `grep`.

### `cortex lint`
- **Descrição:** Aciona a verificação de sanidade da base da wiki.
- **Argumentos:** `--project <id>`.
- **Ação:** Retorna o status de páginas órfãs, front-matters incorretos, links mortos, alertando o usuário no console.

### `cortex consolidate`
- **Descrição:** Aciona uma rotina manual ou interface interativa para chamar o agente local e empurrar a consolidação das informações brutas (se implementado fluxo local, ou apenas informa quais arquivos brutos estão pendentes).
