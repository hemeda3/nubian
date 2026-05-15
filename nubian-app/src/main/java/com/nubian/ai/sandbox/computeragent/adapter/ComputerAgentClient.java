package com.nubian.ai.sandbox.computeragent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.nubian.ai.sandbox.computeragent.ComputerAgentException;

/**
 * HTTP client contract for the Ubuntu-desktop guest agent.
 * Concrete implementation: {@link com.nubian.ai.sandbox.computeragent.ComputerAgentClient}.
 */
public interface ComputerAgentClient {

    byte[] screenshot() throws ComputerAgentException;

    JsonNode accessibility() throws ComputerAgentException;

    JsonNode pyautogui(JsonNode body) throws ComputerAgentException;

    JsonNode xdotool(JsonNode body) throws ComputerAgentException;

    JsonNode exec(JsonNode body) throws ComputerAgentException;

    JsonNode cdpVersion() throws ComputerAgentException;

    JsonNode cdpCommand(String method, JsonNode params) throws ComputerAgentException;
}
