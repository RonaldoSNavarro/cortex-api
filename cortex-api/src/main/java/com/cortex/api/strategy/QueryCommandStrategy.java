package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;
import com.cortex.core.MemoryPage;
import com.cortex.core.ProjectRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/** Executes full-text memory searches through the REST command API. */
@Component
public class QueryCommandStrategy implements CommandStrategy {

    private final ProjectRepository repository;

    public QueryCommandStrategy(ProjectRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public String getCommandName() {
        return "query";
    }

    /** {@inheritDoc} */
    @Override
    public String execute(CommandRequest request) throws Exception {
        String terms = requireText(request.getTerms(), "terms");
        List<MemoryPage> results = repository.search(request.getProject(), terms);
        if (results.isEmpty()) {
            return "Nenhum resultado encontrado para: " + terms;
        }

        StringBuilder output = new StringBuilder("Resultados para '").append(terms).append("':\n\n");
        for (MemoryPage page : results) {
            output.append("ID: ").append(page.getId()).append("\n");
            output.append("Tipo: ").append(page.getType()).append("\n");
            output.append("Conteúdo:\n").append(page.getContent()).append("\n---\n");
        }
        return output.toString();
    }

    private String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Parâmetro '" + field + "' é obrigatório para query.");
        }
        return value.trim();
    }
}
