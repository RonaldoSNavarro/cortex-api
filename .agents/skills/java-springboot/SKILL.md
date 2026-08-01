---
name: java-springboot
description: 'Get best practices for developing applications with Spring Boot.'
---

# Spring Boot Best Practices

Your goal is to help write high-quality Spring Boot applications by following established best practices.

## Project Setup & Structure

- **Build Tool:** Use Maven (`pom.xml`) for dependency management.
- **Starters:** Use Spring Boot starters (`spring-boot-starter-web`) to simplify dependency management.
- **Package Structure:** Organize code by feature/domain (`com.cortex.api`, `com.cortex.core`, `com.cortex.mcp`).

## Dependency Injection & Components

- **Constructor Injection:** Always use constructor-based injection for required dependencies.
- **Immutability:** Declare dependency fields as `private final`.
- **Component Stereotypes:** Use `@Component`, `@Service`, `@Repository`, and `@RestController` appropriately.

## Configuration & Logging

- **Externalized Configuration:** Use `application.yml` or `application.properties`.
- **SLF4J:** Use SLF4J with parameterized logging (`logger.info("Processing page {}...", pageName)`).

## Web Layer & MCP Transports

- **RESTful APIs & SSE:** Implement clean controllers.
- **MCP SSE Bridge:** Keep Jackson configured with `FAIL_ON_UNKNOWN_PROPERTIES = false` to accept capabilities sent by MCP client SDKs (Go, Node, Python, Claude Code, Antigravity).

## Testing

- **Unit & Integration Tests:** Use `@SpringBootTest` and `@WebMvcTest` with `@TempDir` for clean filesystem assertions.

## Padrões Específicos do Cortex
- **Integração MCP SSE:** O endpoint `/mcp/sse` deve manter o comportamento de ponte para publicar respostas JSON-RPC no corpo das requisições POST.
- **Gerenciamento de Arquivos:** Operações de escrita de arquivos em `cortex-core` devem ser thread-safe e garantir que metadados no YAML Frontmatter permaneçam consistentes.
