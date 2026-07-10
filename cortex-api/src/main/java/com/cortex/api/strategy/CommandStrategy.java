package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;

public interface CommandStrategy {
    String getCommandName();
    String execute(CommandRequest request) throws Exception;
}
