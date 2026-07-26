package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;
import com.cortex.core.ProjectRepository;
import org.springframework.stereotype.Component;

/** Creates a curated memory page and optionally consumes raw memories through REST. */
@Component
public class WritePageCommandStrategy implements CommandStrategy {

    private final ProjectRepository repository;

    public WritePageCommandStrategy(ProjectRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public String getCommandName() {
        return "write_page";
    }

    /** {@inheritDoc} */
    @Override
    public String execute(CommandRequest request) throws Exception {
        String type = requireText(request.getType(), "type");
        String content = requireText(request.getContent(), "content");
        String id = repository.writePage(request.getProject(), type, request.getTags(), request.getSupersedes(),
            request.getConsumedRawIds(), content);
        return "Página salva com sucesso. ID: " + id;
    }

    private String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Parâmetro '" + field + "' é obrigatório para write_page.");
        }
        return value.trim();
    }
}
