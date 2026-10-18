package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;

/**
 * Estratégia de comando para processamento polimórfico de requisições na API REST.
 */
public interface CommandStrategy {
    String getCommandName();
    String execute(CommandRequest request) throws Exception;
}
