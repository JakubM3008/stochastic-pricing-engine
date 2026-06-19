package com.quant.pricing.agent;

import com.quant.pricing.domain.AlmgrenChrissOptimizer;
import com.quant.pricing.domain.ExecutionSimulator;
import com.quant.pricing.domain.VwapTrajectoryGenerator;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AgentConfiguration {

    @Bean
    public AlmgrenChrissOptimizer optimizer() {
        return new AlmgrenChrissOptimizer();
    }

    @Bean
    public ExecutionSimulator simulator() {
        return new ExecutionSimulator();
    }

    @Bean
    public VwapTrajectoryGenerator vwapGenerator() {
        return new VwapTrajectoryGenerator();
    }

    @Bean
    public ExecutionTools executionTools(AlmgrenChrissOptimizer optimizer, ExecutionSimulator simulator, VwapTrajectoryGenerator vwapGenerator) {
        return new ExecutionTools(optimizer, simulator, vwapGenerator);
    }

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return new ChatLanguageModel() {
                @Override
                public Response<AiMessage> generate(List<ChatMessage> messages) {
                    throw new IllegalStateException("GEMINI_API_KEY environment variable is not set.");
                }

                @Override
                public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
                    throw new IllegalStateException("GEMINI_API_KEY environment variable is not set.");
                }

                @Override
                public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
                    throw new IllegalStateException("GEMINI_API_KEY environment variable is not set.");
                }
            };
        }

        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gemini-2.5-flash-lite")
                .build();
    }

    @Bean
    public ExecutionAgent executionAgent(ChatLanguageModel chatLanguageModel, ExecutionTools executionTools) {
        return AiServices.builder(ExecutionAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .tools(executionTools)
                .build();
    }
}
