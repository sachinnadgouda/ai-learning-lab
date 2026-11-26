package dev.demo.traffic.application.model;

import java.util.List;

public record AiModelStatus(boolean reachable, String baseUrl, List<String> installedModels, boolean selectedModelInstalled,
                            String detail) {
    public AiModelStatus {
        installedModels = List.copyOf(installedModels);
    }
}
