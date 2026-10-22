package com.example1.bddllm.analysis;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface LlmGateway {
    ObjectNode generate(String provider, String model, String instructions, String evidence);

    default void validateConfiguration(String provider) {}
}
