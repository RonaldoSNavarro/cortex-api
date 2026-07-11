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
  - Unless there is major behavior change, in which case you should document the differences.
- Use `@param <T>` for type parameters in generic types or methods.
- Use `{@code}` for inline code snippets.
- Use `<pre>{@code ... }</pre>` for code blocks.
- Use `@since` to indicate when the feature was introduced (e.g., version number).
- Use `@version` to specify the version of the member.
- Use `@deprecated` to mark a member as deprecated and provide an alternative.

## Padrões Específicos do Consórcio API
- Sempre documente a correlação de entidades, métodos e regras de negócio com regulamentações específicas do Banco Central (ex: Circular 3432/2009 e Resolução BCB 285/2023).
- Ao implementar regras complexas ou críticas (como algoritmos de sorteio, concorrência otimista com `@Version` ou cálculo de restituição), inclua referências no Javadoc para os ADRs em `docs/PROJECT_CONTEXT.md` ou para a documentação técnica pertinente.
