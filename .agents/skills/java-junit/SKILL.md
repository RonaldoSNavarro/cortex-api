---
name: java-junit
description: 'Get best practices for JUnit 5 unit testing, including data-driven tests'
---

# JUnit 5+ Best Practices

Your goal is to help me write effective unit tests with JUnit 5, covering both standard and data-driven testing approaches.

## Project Setup

- Use standard Maven project structure.
- Place test source code in `src/test/java`.
- Include dependencies for `junit-jupiter-api`, `junit-jupiter-engine`, and `junit-jupiter-params` for parameterized tests.
- Use build tool command to run tests: `mvn test`.

## Test Structure

- Test classes should have a `Test` suffix, e.g., `MarkdownRepositoryTest`.
- Use `@Test` for test methods.
- Follow the Arrange-Act-Assert (AAA) pattern.
- Name tests using a descriptive convention, like `methodName_should_expectedBehavior_when_scenario`.
- Use `@BeforeEach` and `@AfterEach` for per-test setup and teardown.
- Use `@BeforeAll` and `@AfterAll` for per-class setup and teardown (must be static methods).
- Use `@DisplayName` to provide a human-readable name for test classes and methods.

## Standard Tests

- Keep tests focused on a single behavior.
- Make tests independent and idempotent (can run in any order).
- Avoid test interdependencies.

## Data-Driven (Parameterized) Tests

- Use `@ParameterizedTest` to mark a method as a parameterized test.
- Use `@ValueSource`, `@CsvSource`, or `@MethodSource` for test arguments.

## Assertions

- Use static methods from `org.junit.jupiter.api.Assertions`.
- Use `assertThrows` or `assertDoesNotThrow` to test for exceptions.

## Mocking and Isolation

- Use Mockito to create mock objects for dependencies (`@Mock`, `@InjectMocks`).

## Padrões Específicos do Cortex
- **Testes de Manipulação de Markdown:** Garanta o isolamento do sistema de arquivos utilizando diretórios temporários (`@TempDir`) nos testes de `cortex-core`.
- **Testes de Endpoints MCP / REST:** Teste os controladores do `cortex-api` e a serialização/deserialização de mensagens JSON-RPC do MCP.
