---
name: java-docs
description: 'Ensure that Java types are documented with Javadoc comments and follow best practices for documentation.'
---

# Java Documentation (Javadoc) Best Practices

- Public and protected members should be documented with Javadoc comments.
- It is encouraged to document package-private and private members as well, especially if they are complex or not self-explanatory.
- The first sentence of the Javadoc comment is the summary description. It should be a concise overview of what the method does and end with a period.
- Use `@param` for method parameters. The description starts with a lowercase letter and does not end with a period.
- Use `@return` for method return values.
- Use `@throws` or `@exception` to document exceptions thrown by methods.
- Use `@see` for references to other types or members.
- Use `{@inheritDoc}` to inherit documentation from base classes or interfaces.
- Use `@param <T>` for type parameters in generic types or methods.
- Use `{@code}` for inline code snippets.
- Use `<pre>{@code ... }</pre>` for code blocks.
- Use `@since` to indicate when the feature was introduced.
- Use `@version` to specify the version of the member.
- Use `@deprecated` to mark a member as deprecated and provide an alternative.

## Padrões Específicos do Cortex
- Sempre documente a finalidade de serviços de domínio, repositórios de arquivos Markdown e manipuladores de MCP (`capture`, `query`, `write_page`, `promote_rules`, etc.).
- Ao implementar regras críticas de parsing YAML Frontmatter ou serialização Jackson/MCP, inclua referências no Javadoc explicando as razões técnicas (ex: evitar `FAIL_ON_UNKNOWN_PROPERTIES` para interoperabilidade com clientes MCP Go/Node/Claude Code).
