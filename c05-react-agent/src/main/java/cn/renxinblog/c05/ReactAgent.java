package cn.renxinblog.c05;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** C05 Java 对照：模型提议 Action，Runtime 组织协议、状态与执行边界。 */
public final class ReactAgent {
  static final ObjectMapper JSON = new ObjectMapper();
  enum Status { RUNNING, WAITING_FOR_CLARIFICATION, COMPLETED, MAX_STEPS }
  record Call(String name, ObjectNode args, String id) {}
  record Turn(String content, Call call) {}
  interface Model { Turn decide(List<ObjectNode> messages) throws Exception; }
  static final class State { final List<ObjectNode> messages = new ArrayList<>(); final List<String> trace = new ArrayList<>(); Status status = Status.RUNNING; String city, pending; }

  static final class Registry {
    ObjectNode execute(Call call) {
      String[] required = switch (call.name) { case "resolve_company_address" -> new String[]{"company_name"}; case "get_weather" -> new String[]{"city"}; case "request_clarification" -> new String[]{"question", "missing_field"}; default -> null; };
      if (required == null) return response("ERROR").put("code", "UNKNOWN_TOOL");
      ArrayNode bad = JSON.createArrayNode(); for (String key : required) if (!call.args.hasNonNull(key) || !call.args.path(key).isTextual() || call.args.path(key).asText().isBlank()) bad.add(key);
      // 参数错误由注册表变为 Observation；handler 不会抛异常中断整个 Run。
      if (!bad.isEmpty()) return response("ERROR").put("code", "INVALID_ARGUMENT").set("missing_or_invalid", bad);
      return switch (call.name) {
        case "resolve_company_address" -> resolve(call.args.path("company_name").asText());
        case "get_weather" -> response("SUCCESS").put("city", call.args.path("city").asText()).put("weather", Map.of("上海", "小雨，22℃", "北京", "晴，18℃").getOrDefault(call.args.path("city").asText(), "未知"));
        case "request_clarification" -> response("WAITING_FOR_CLARIFICATION").put("question", call.args.path("question").asText()).put("missing_field", call.args.path("missing_field").asText());
        default -> throw new IllegalStateException();
      };
    }
    ObjectNode resolve(String company) { if (company.equals("上海总部")) return response("RESOLVED").put("city", "上海"); if (company.equals("北京总部")) return response("RESOLVED").put("city", "北京"); return response("AMBIGUOUS").put("message", "请补充公司名称或所在城市"); }
    ObjectNode response(String status) { return JSON.createObjectNode().put("status", status); }
  }

  static final class Runtime {
    final Model model; final Registry registry = new Registry(); Runtime(Model model) { this.model = model; }
    State start(String question) throws Exception { State state = new State(); state.messages.add(message("user", question)); return drive(state, 4); }
    State resume(State state, String value) throws Exception { if (state.status != Status.WAITING_FOR_CLARIFICATION) throw new IllegalArgumentException("run is not waiting"); state.messages.add(message("user", "补充信息：" + value)); state.trace.add("external_input=clarification"); state.status = Status.RUNNING; state.pending = null; return drive(state, 4); }
    State drive(State s, int max) throws Exception {
      for (int step = 1; step <= max; step++) {
        Turn turn = model.decide(s.messages); if (turn.call == null) { s.trace.add("step=" + step + " model_candidate=FINAL"); s.status = s.pending == null ? Status.COMPLETED : Status.WAITING_FOR_CLARIFICATION; return s; }
        Call call = turn.call; s.trace.add("step=" + step + " model_candidate=" + call.name + call.args); String reject = guard(call, s); if (reject != null) { s.trace.add("runtime_reject=" + reject); s.status = Status.WAITING_FOR_CLARIFICATION; return s; }
        // 这条 assistant 历史要原样带到下一次 API 请求；type=function 缺失会在第二轮被服务端拒绝。
        ObjectNode assistant = message("assistant", null); ObjectNode saved = assistant.putArray("tool_calls").addObject(); saved.put("id", call.id).put("type", "function"); saved.putObject("function").put("name", call.name).put("arguments", call.args.toString()); s.messages.add(assistant);
        ObjectNode obs = registry.execute(call); s.trace.add("runtime_execute=" + call.name + call.args); s.trace.add("observation=" + call.name + ":" + obs.path("status").asText()); ObjectNode tool = message("tool", obs.toString()); tool.put("tool_call_id", call.id); s.messages.add(tool);
        if (call.name.equals("resolve_company_address") && obs.path("status").asText().equals("RESOLVED")) s.city = obs.path("city").asText();
        if (call.name.equals("resolve_company_address") && obs.path("status").asText().equals("AMBIGUOUS")) s.pending = obs.path("message").asText();
        if (call.name.equals("request_clarification")) { s.pending = obs.path("question").asText(); s.status = Status.WAITING_FOR_CLARIFICATION; s.trace.add("runtime_wait=missing:" + obs.path("missing_field").asText()); return s; }
      } s.status = Status.MAX_STEPS; s.trace.add("runtime_stop=MAX_STEPS"); return s;
    }
    String guard(Call call, State s) { String city = call.args.path("city").asText(); if (s.pending != null && !call.name.equals("request_clarification")) return "WAITING_FOR_CLARIFICATION"; if (call.name.equals("get_weather") && !city.isBlank() && !Objects.equals(city, s.city)) return "WEATHER_MUST_USE_RESOLVED_CITY"; return null; }
  }

  static final class LiveModel implements Model {
    final String url = require("LLM_API_URL"), key = require("LLM_API_KEY"), model = require("LLM_MODEL"); final HttpClient client = HttpClient.newHttpClient();
    public Turn decide(List<ObjectNode> history) throws Exception { ObjectNode body = JSON.createObjectNode().put("model", model).put("stream", false); ArrayNode messages = body.putArray("messages"); messages.add(message("system", "你是工具调用 Agent。查询总部天气先 resolve_company_address；RESOLVED 后只用返回 city 调 get_weather；歧义时 request_clarification，missing_field=company_name。本实验中“上海总部”和“北京总部”都是合法 company_name。收到“补充信息：”后立即解析其中公司名，不得再次澄清或猜城市。")); history.forEach(messages::add); body.set("tools", schemas()); HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json").header("Authorization", "Bearer " + key).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(); HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString()); if (response.statusCode() >= 400) throw new IllegalStateException("LLM HTTP " + response.statusCode()); JsonNode msg = JSON.readTree(response.body()).path("choices").get(0).path("message"); JsonNode calls = msg.path("tool_calls"); if (!calls.isArray() || calls.isEmpty()) return new Turn(msg.path("content").asText(), null); JsonNode c = calls.get(0); return new Turn(msg.path("content").asText(), new Call(c.path("function").path("name").asText(), (ObjectNode) JSON.readTree(c.path("function").path("arguments").asText("{}")), c.path("id").asText())); }
  }
  static ObjectNode message(String role, String content) { ObjectNode n = JSON.createObjectNode().put("role", role); if (content != null) n.put("content", content); return n; }
  static String require(String key) { String value = System.getenv(key); if (value == null || value.isBlank()) throw new IllegalStateException("missing environment variable: " + key); return value; }
  static ArrayNode schemas() { return JSON.createArrayNode().add(schema("resolve_company_address", "解析公司总部", "company_name")).add(schema("get_weather", "查询天气", "city")).add(schema("request_clarification", "请求补充", "question", "missing_field")); }
  static ObjectNode schema(String name, String desc, String... required) { ObjectNode t = JSON.createObjectNode().put("type", "function"), f = t.putObject("function").put("name", name).put("description", desc), p = f.putObject("parameters").put("type", "object"); ObjectNode props = p.putObject("properties"); ArrayNode need = p.putArray("required"); for (String key : required) { props.putObject(key).put("type", "string"); need.add(key); } return t; }
  static Call call(String name, String id, String key, String value) { return new Call(name, JSON.createObjectNode().put(key, value), id); }
  static final class Scripted implements Model { final Deque<Turn> turns; Scripted(Turn... turns) { this.turns = new ArrayDeque<>(List.of(turns)); } public Turn decide(List<ObjectNode> m) { return turns.removeFirst(); } }
  static void offline() throws Exception { Runtime r = new Runtime(new Scripted(new Turn(null, call("get_weather", "a", "city", "")), new Turn(null, call("resolve_company_address", "b", "company_name", "北京总部")), new Turn(null, call("get_weather", "c", "city", "北京")), new Turn("完成", null))); State s = r.start("查询总部天气"); if (s.status != Status.COMPLETED || s.trace.stream().noneMatch(x -> x.equals("observation=get_weather:ERROR"))) throw new AssertionError("offline recovery failed"); System.out.println("ALL_OFFLINE_CHECKS_PASSED"); }
  public static void main(String[] args) throws Exception { if (args.length == 1 && args[0].equals("--offline")) { offline(); return; } Runtime r = new Runtime(new LiveModel()); State s = r.start("查询公司总部今天的天气"); s.trace.forEach(System.out::println); if (s.status == Status.WAITING_FOR_CLARIFICATION) { System.out.println("--- resumed ---"); s = r.resume(s, "北京总部"); s.trace.forEach(System.out::println); } System.out.println("status=" + s.status); }
}
