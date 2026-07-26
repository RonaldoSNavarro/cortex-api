package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;
import com.cortex.core.MemoryPage;
import com.cortex.core.ProjectRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/** Returns raw memories that still require curation through the REST command API. */
@Component
public class ConsolidateCommandStrategy implements CommandStrategy {

    private final ProjectRepository repository;

    public ConsolidateCommandStrategy(ProjectRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public String getCommandName() {
        return "consolidate";
    }

    /** {@inheritDoc} */
    @Override
    public String execute(CommandRequest request) throws Exception {
        List<MemoryPage> pending = repository.getPendingRaws(request.getProject());
        if (pending.isEmpty()) {
            return "Nenhum arquivo bruto pendente de consolidação.";
        }

        StringBuilder output = new StringBuilder("Arquivos brutos pendentes (")
            .append(pending.size()).append("):\n\n");
        for (MemoryPage page : pending) {
            output.append("ID: ").append(page.getId()).append("\n");
            output.append("Tipo: ").append(page.getType()).append("\n");
            output.append("Data: ").append(page.getCreatedAt()).append("\n");
            output.append("Conteúdo:\n").append(page.getContent()).append("\n---\n");
        }
        return output.toString();
    }
}
