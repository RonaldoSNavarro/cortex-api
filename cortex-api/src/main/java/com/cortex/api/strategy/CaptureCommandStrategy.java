package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;
import com.cortex.core.ProjectRepository;
import org.springframework.stereotype.Component;

/** Stores an append-only raw memory through the REST command API. */
@Component
public class CaptureCommandStrategy implements CommandStrategy {

    private final ProjectRepository repository;

    public CaptureCommandStrategy(ProjectRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public String getCommandName() {
        return "capture";
    }

    /** {@inheritDoc} */
    @Override
    public String execute(CommandRequest request) throws Exception {
        String type = requireText(request.getType(), "type");
        String content = requireText(request.getContent(), "content");
        String id = repository.createRaw(request.getProject(), type, content);
        return "Ingestão salva com ID " + id;
    }

    private String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Parâmetro '" + field + "' é obrigatório para capture.");
        }
        return value.trim();
    }
}
