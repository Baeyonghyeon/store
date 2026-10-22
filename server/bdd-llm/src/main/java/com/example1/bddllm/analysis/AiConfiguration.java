package com.example1.bddllm.analysis;

import com.example1.bddllm.common.Json;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

import static com.example1.bddllm.analysis.SpringAiLlmGateway.httpFailure;
import static com.example1.bddllm.analysis.SpringAiLlmGateway.validateChatWire;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
public class AiConfiguration {
    @Bean
    @ConfigurationProperties("spring.ai.openai")
    AiConnectionProperties openAiConnection() { return new AiConnectionProperties(); }

    @Bean
    @ConfigurationProperties("spring.ai.deepseek")
    AiConnectionProperties deepSeekConnection() { return new AiConnectionProperties(); }

    // This application-specific prefix keeps Ollama Cloud separate from OpenAI credentials.
    // The native spring.ai.ollama driver targets /api/chat; this client uses /v1/chat/completions.
    @Bean
    @ConfigurationProperties("bdd.ai.ollama-cloud")
    AiConnectionProperties ollamaCloudConnection() { return new AiConnectionProperties(); }

    @Bean
    AiProvider ollamaProvider(@Qualifier("ollamaCloudConnection") AiConnectionProperties properties) {
        return new AiProvider("ollama", properties,
                () -> compatibleModel("ollama", properties, options -> options.reasoningEffort("none")));
    }

    @Bean
    AiProvider deepSeekProvider(@Qualifier("deepSeekConnection") AiConnectionProperties properties) {
        return new AiProvider("deepseek", properties, () -> compatibleModel("deepseek", properties,
                options -> options.extraBody(Map.of("thinking", Map.of("type", "disabled")))
                        .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                                .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT).build())));
    }

    @Bean
    AiProvider openAiProvider(@Qualifier("openAiConnection") AiConnectionProperties properties) {
        // Existing saved OpenAI jobs use Responses, which the Spring AI Chat client does not expose.
        return new AiProvider("openai", properties, () -> new OpenAiResponsesChatModel(properties));
    }

    private ChatModel compatibleModel(String provider, AiConnectionProperties properties,
            Consumer<OpenAiChatOptions.Builder> customize) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(properties.getChat().getModel()).apiKey(properties.getApiKey().strip())
                .baseUrl(properties.getBaseUrl()).timeout(properties.getTimeout())
                .maxTokens(6000).maxRetries(0).temperature(null).n(1);
        customize.accept(options);
        OpenAiChatOptions defaults = options.build();
        ChatModel delegate = OpenAiChatModel.builder().options(defaults)
                .httpClientBuilderCustomizer(builder -> builder.interceptor(chain -> {
                    Request request = chain.request();
                    RequestBody original = request.body();
                    if (original != null) {
                        // Also prevent transport retries after sending the analysis body.
                        RequestBody once = new RequestBody() {
                            @Override
                            public MediaType contentType() {
                                return original.contentType();
                            }

                            @Override
                            public long contentLength() throws IOException {
                                return original.contentLength();
                            }

                            @Override
                            public boolean isOneShot() {
                                return true;
                            }

                            @Override
                            public void writeTo(BufferedSink sink) throws IOException {
                                original.writeTo(sink);
                            }
                        };
                        request = request.newBuilder().method(request.method(), once).build();
                    }
                    Response response = chain.proceed(request);
                    try {
                        String raw = response.peekBody(4 * 1024 * 1024).string();
                        if (!response.isSuccessful())
                            throw httpFailure(provider, response.code(), raw, response.header("Retry-After"));
                        validateChatWire(Json.parse(raw));
                        return response;
                    } catch (RuntimeException exception) {
                        response.close();
                        throw exception;
                    }

        })).build();
        return new OpenAiCompatibleChatModel(delegate, defaults);
    }
}
