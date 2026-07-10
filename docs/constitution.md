# Constitution (Princípios e Requisitos Não-Funcionais)

## Princípios Arquiteturais

1. **Zero Infraestrutura Externa Obrigatória**: O MVP deve rodar nativamente com JVM e sistema de arquivos. Não deve depender de banco de dados, motores de busca vetorial ou serviços de nuvem para a sua operação fundamental.
2. **Armazenamento Transparente**: A fonte da verdade são arquivos Markdown (`.md`). Eles devem ser sempre legíveis, versionáveis em Git (sem lock binário) e fáceis de editar manualmente se necessário.
3. **Consolidação Não-Determinística**: A consolidação de memórias brutas para páginas curadas não é um script determinístico, mas uma ação assistida por LLM (agente) baseada nas restrições da CLI/MCP. A IA decide o que promover, orientada pela Skill/schema.
4. **Portabilidade**: A solução (Servidor MCP e CLI) deve ser totalmente portável e funcional em Linux, macOS e Windows.
5. **Fail-Open nos Hooks**: Os hooks injetados nos agentes não podem quebrar ou travar as sessões de desenvolvimento em caso de indisponibilidade do servidor Cortex. 
6. **Desacoplamento de Componentes**: A arquitetura deve ser dividida em camadas lógicas (Storage, Domínio, MCP Tools, CLI, Adaptadores de Hooks) para permitir a evolução fluida para implementações de storage mais robustas (ex.: SQLite FTS5) no futuro.

## Restrições Tecnológicas

- **Linguagem Base:** Java 21 (LTS) com Maven ou Gradle.
- **Protocolo MCP:** Uso do MCP Java SDK oficial (via Stdio). Logs não podem utilizar `System.out.println`, devendo obrigatoriamente ir para `stderr` ou arquivo para não corromper o JSON-RPC.
- **Interface de Linha de Comando:** Construída sobre o Picocli.
- **Parsing de Arquivos:** Uso de bibliotecas robustas para processar Front-matter YAML (ex. Jackson/Snakeyaml) e validação de Markdown (CommonMark-java).
