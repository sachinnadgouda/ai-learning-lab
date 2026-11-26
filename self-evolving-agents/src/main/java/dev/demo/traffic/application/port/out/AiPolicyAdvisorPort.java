package dev.demo.traffic.application.port.out;

import dev.demo.traffic.application.model.AiCallResult;
import dev.demo.traffic.application.model.AiModelStatus;
import dev.demo.traffic.application.model.AiPrompt;

public interface AiPolicyAdvisorPort {
    /** Sends the prompt to the model. Never throws for model/transport failures; they are reported in the result. */
    AiCallResult advise(AiPrompt prompt, String model);

    AiModelStatus status(String selectedModel);
}
