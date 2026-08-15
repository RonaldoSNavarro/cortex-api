package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;
import com.cortex.core.CortexStats;
import com.cortex.core.MemoryPage;
import com.cortex.core.MemoryType;
import com.cortex.core.ProjectRepository;
import com.cortex.core.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestCommandStrategiesTest {

    @Mock
    private ProjectRepository repository;

    @Test
    void query_shouldFormatRepositoryResults() throws Exception {
        MemoryPage page = new MemoryPage();
        page.setId("page-1");
        page.setType(MemoryType.FACT);
        page.setContent("MCP is available over REST.");
        SearchResult sr = new SearchResult(page, 2.5, "MCP is available over REST.", List.of("mcp"));
        when(repository.searchLexical("cortex", "MCP", 20)).thenReturn(List.of(sr));

        CommandRequest request = new CommandRequest();
        request.setProject("cortex");
        request.setTerms("MCP");

        String output = new QueryCommandStrategy(repository).execute(request);

        assertTrue(output.contains("page-1"));
        assertTrue(output.contains("MCP is available over REST."));
    }

    @Test
    void query_shouldRejectMissingTerms() {
        CommandRequest request = new CommandRequest();
        request.setProject("cortex");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new QueryCommandStrategy(repository).execute(request)
        );

        assertTrue(exception.getMessage().contains("terms"));
    }

    @Test
    void capture_shouldCreateRawMemory() throws Exception {
        when(repository.createRaw("cortex", "fact", "REST works")).thenReturn("raw-1");
        CommandRequest request = new CommandRequest();
        request.setProject("cortex");
        request.setType("fact");
        request.setContent("REST works");

        String output = new CaptureCommandStrategy(repository).execute(request);

        assertEquals("Ingestão salva com ID raw-1", output);
    }

    @Test
    void writePage_shouldForwardCurationFields() throws Exception {
        when(repository.writePage(eq("cortex"), eq("gotcha"), any(), eq("old-page"), any(), eq("New page")))
            .thenReturn("Página salva com sucesso. ID: page-2");
        CommandRequest request = new CommandRequest();
        request.setProject("cortex");
        request.setType("gotcha");
        request.setContent("New page");
        request.setTags(List.of("api"));
        request.setSupersedes("old-page");
        request.setConsumedRawIds(List.of("raw-1"));

        String output = new WritePageCommandStrategy(repository).execute(request);

        assertEquals("Página salva com sucesso. ID: page-2", output);
        ArgumentCaptor<List<String>> rawIds = ArgumentCaptor.forClass(List.class);
        verify(repository).writePage(eq("cortex"), eq("gotcha"), eq(List.of("api")), eq("old-page"), rawIds.capture(), eq("New page"));
        assertEquals(List.of("raw-1"), rawIds.getValue());
    }

    @Test
    void consolidate_shouldCallConsolidateReport() throws Exception {
        when(repository.consolidateReport("cortex", null)).thenReturn("Nenhuma memória raw pendente para consolidar.");
        CommandRequest request = new CommandRequest();
        request.setProject("cortex");

        String output = new ConsolidateCommandStrategy(repository).execute(request);

        assertEquals("Nenhuma memória raw pendente para consolidar.", output);
    }

    @Test
    void stats_shouldReturnSummary() throws Exception {
        CortexStats stats = new CortexStats("cortex", 5, 4, 1, 2, Map.of("rule", 2), Map.of("mcp", 3), 4, 3, Map.of("base", 2));
        when(repository.getStats("cortex")).thenReturn(stats);

        CommandRequest request = new CommandRequest();
        request.setProject("cortex");

        String output = new StatsCommandStrategy(repository).execute(request);

        assertTrue(output.contains("Métricas e Saúde do Cérebro"));
        assertTrue(output.contains("cortex"));
    }

    @Test
    void promoteRules_shouldRequireTargetFile() {
        CommandRequest request = new CommandRequest();
        request.setProject("cortex");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new PromoteRulesCommandStrategy(repository).execute(request)
        );

        assertTrue(exception.getMessage().contains("file"));
    }

    @Test
    void shouldDeserializeQueryAlias() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"project\":\"cortex\",\"command\":\"query\",\"query\":\"MCP test\"}";
        CommandRequest parsed = mapper.readValue(json, CommandRequest.class);

        assertEquals("MCP test", parsed.getTerms());
    }

    @Test
    void shouldDeserializeTermsField() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"project\":\"cortex\",\"command\":\"query\",\"terms\":\"MCP test\"}";
        CommandRequest parsed = mapper.readValue(json, CommandRequest.class);

        assertEquals("MCP test", parsed.getTerms());
    }
}
