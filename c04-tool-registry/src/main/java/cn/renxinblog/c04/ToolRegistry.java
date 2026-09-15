package cn.renxinblog.c04;

import java.util.*;

/** C04 Java 对照：与 Python 版本使用同一组错误码与恢复 Trace。 */
public final class ToolRegistry {
  record Response(String status, String errorCode, String recoveryAction) {}
  interface Handler { Response call(Map<String, String> args) throws Timeout, Failure; }
  static class Timeout extends Exception {}
  static class Failure extends Exception {}
  static class Tool { Set<String> required; Set<String> handlerRequired; Handler handler; boolean enabled = true; boolean healthy = true; int version;
    Tool(Set<String> required, Set<String> handlerRequired, Handler handler, int version) { this.required = required; this.handlerRequired = handlerRequired; this.handler = handler; this.version = version; } }
  static class Registry {
    Map<String, Tool> tools = new HashMap<>();
    void register(String name, Set<String> required, Set<String> handlerRequired, Handler handler) {
      if (!required.equals(handlerRequired)) throw new IllegalArgumentException("schema 与 handler 输入契约不一致");
      Tool previous = tools.get(name); int version = previous == null ? 1 : previous.version + 1;
      tools.put(name, new Tool(required, handlerRequired, handler, version));
    }
    void disable(String name) { tools.get(name).enabled = false; }
    Response execute(String name, Map<String, String> args) {
      Tool tool = tools.get(name);
      if (tool == null) return new Response("ERROR", "UNKNOWN_TOOL", "DO_NOT_RETRY");
      if (!tool.enabled || !tool.healthy) return new Response("ERROR", "TOOL_UNAVAILABLE", "DO_NOT_RETRY");
      if (!args.keySet().containsAll(tool.required)) return new Response("ERROR", "INVALID_ARGUMENT", "DO_NOT_RETRY");
      try { return tool.handler.call(args); }
      catch (Timeout e) { return new Response("ERROR", "EXECUTION_ERROR", "CHECK_IDEMPOTENCY_STATUS_FIRST"); }
      catch (Failure e) { return new Response("ERROR", "EXECUTION_ERROR", "DO_NOT_RETRY"); }
    }
  }
  static class RefundDomain {
    String mode; boolean first = true; int executionCount = 0; Map<String, String> states = new HashMap<>();
    RefundDomain(String mode) { this.mode = mode; }
    Response refund(Map<String, String> args) throws Timeout {
      String key = args.get("idempotency_key");
      if (mode.equals("before_execution") && first) { first = false; states.put(key, "NOT_EXECUTED"); throw new Timeout(); }
      executionCount++; states.put(key, "SUCCEEDED");
      if (mode.equals("after_success") && first) { first = false; throw new Timeout(); }
      return new Response("SUCCESS", null, null);
    }
  }
  static class Runtime {
    Registry registry; RefundDomain domain; List<String> trace = new ArrayList<>();
    Runtime(Registry registry, RefundDomain domain) { this.registry = registry; this.domain = domain; }
    Response refund(String key) {
      Map<String, String> args = Map.of("order_id", "O-100", "idempotency_key", key);
      Response first = registry.execute("refund_order", args);
      trace.add("refund_order response=" + (first.errorCode == null ? first.status : first.errorCode));
      if (!"CHECK_IDEMPOTENCY_STATUS_FIRST".equals(first.recoveryAction)) return first;
      String state = domain.states.getOrDefault(key, "UNKNOWN"); trace.add("idempotency_status=" + state);
      if (state.equals("SUCCEEDED")) { trace.add("runtime_action=REPLAY_SUCCESS"); return new Response("SUCCESS", null, null); }
      if (state.equals("NOT_EXECUTED")) { trace.add("runtime_action=RETRY_ONCE"); return registry.execute("refund_order", args); }
      trace.add("runtime_action=WAIT_FOR_RECONCILIATION"); return new Response("ERROR", "EXECUTION_ERROR", "WAIT_FOR_RECONCILIATION");
    }
  }
  static Registry build(RefundDomain domain) {
    Registry registry = new Registry();
    registry.register("get_weather", Set.of("city"), Set.of("city"), args -> new Response("SUCCESS", null, null));
    registry.register("refund_order", Set.of("order_id", "idempotency_key"), Set.of("order_id", "idempotency_key"), domain::refund);
    registry.register("search_notes", Set.of("query"), Set.of("query"), args -> { throw new Failure(); });
    return registry;
  }
  static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
  public static void main(String[] args) {
    check(build(new RefundDomain("none")).execute("missing", Map.of()).errorCode.equals("UNKNOWN_TOOL"), "unknown");
    RefundDomain disabled = new RefundDomain("none"); Registry disabledRegistry = build(disabled); disabledRegistry.disable("refund_order");
    check(disabledRegistry.execute("refund_order", Map.of("order_id", "O", "idempotency_key", "K")).errorCode.equals("TOOL_UNAVAILABLE"), "disabled");
    check(disabled.executionCount == 0, "disabled must not run handler");
    check(build(new RefundDomain("none")).execute("get_weather", Map.of()).errorCode.equals("INVALID_ARGUMENT"), "arguments");
    check(build(new RefundDomain("none")).execute("search_notes", Map.of("query", "mcp")).recoveryAction.equals("DO_NOT_RETRY"), "failure");
    RefundDomain updateDomain = new RefundDomain("none"); Registry updateRegistry = build(updateDomain); int oldVersion = updateRegistry.tools.get("refund_order").version;
    try { updateRegistry.register("refund_order", Set.of("order_id"), Set.of("order_id", "idempotency_key"), updateDomain::refund); throw new AssertionError("bad update accepted"); }
    catch (IllegalArgumentException expected) { }
    check(updateRegistry.execute("refund_order", Map.of("order_id", "O", "idempotency_key", "K")).status.equals("SUCCESS"), "old version remains callable");
    check(updateRegistry.tools.get("refund_order").version == oldVersion, "old version retained");
    RefundDomain afterSuccess = new RefundDomain("after_success"); Runtime runtime = new Runtime(build(afterSuccess), afterSuccess);
    check(runtime.refund("K-1").status.equals("SUCCESS"), "replay"); check(afterSuccess.executionCount == 1, "no duplicate refund");
    check(runtime.trace.contains("runtime_action=REPLAY_SUCCESS"), "replay trace");
    RefundDomain beforeExecution = new RefundDomain("before_execution"); Runtime retryRuntime = new Runtime(build(beforeExecution), beforeExecution);
    check(retryRuntime.refund("K-2").status.equals("SUCCESS"), "retry"); check(beforeExecution.executionCount == 1, "one retry execution");
    System.out.println("ALL_TESTS_PASSED");
  }
}
