package com.renxin.gya.c01;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Scanner;

/**
 * C01 演示（Java 21 + Gradle）：一个只会说话的模型（命令行聊天）
 *
 * 启动后你就能和模型对话——它说的每句话，底层都是一次
 * "给定前文 token，预测下一个 token" 的重复。
 *
 * 运行：
 *   export DEEPSEEK_API_KEY=sk-xxx
 *   gradle run
 *
 * 依赖：JDK 21 + Jackson（build.gradle.kts 声明，Gradle 自动拉取）。
 */
public class Chat {

    static final String API_URL = System.getenv().getOrDefault("LLM_API_URL", "https://api.deepseek.com/chat/completions");
    static final String API_KEY = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "");
    static final String MODEL = System.getenv().getOrDefault("LLM_MODEL", "deepseek-v4-flash");

    static final ObjectMapper JSON = new ObjectMapper();

    /** 对话历史（每项 {role, content}）。 */
    static final ArrayNode HISTORY = JSON.createArrayNode();

    /** 把整个对话历史发给模型，返回它补全出来的下一个回复。 */
    static String ask(String userText) throws Exception {
        ObjectNode userMsg = HISTORY.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userText);

        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", MODEL);
        payload.set("messages", HISTORY);
        payload.put("stream", false);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(req,
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode root = JSON.readTree(resp.body());
        String reply = root.path("choices").path(0).path("message").path("content").asText();
        ObjectNode assistantMsg = HISTORY.addObject();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", reply);
        return reply;
    }

    public static void main(String[] args) throws Exception {
        if (API_KEY.isEmpty()) {
            System.out.println("请先设置 DEEPSEEK_API_KEY 环境变量（https://platform.deepseek.com 获取）");
            System.exit(1);
        }

        System.out.println("模型: " + MODEL);
        System.out.println("你正在和一个只会说话的模型聊天。输入 exit 退出。\n");

        try (Scanner in = new Scanner(System.in)) {
            while (true) {
                System.out.print("你 > ");
                String line = in.hasNextLine() ? in.nextLine().trim() : "";
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")
                        || line.equals("退出")) break;
                System.out.println("模型 > " + ask(line) + "\n");
            }
        }
    }
}