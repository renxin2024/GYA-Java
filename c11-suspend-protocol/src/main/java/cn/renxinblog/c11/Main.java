package cn.renxinblog.c11;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C11 暂停协议（Java 版）：模型只提出决定，Runtime 独占状态迁移权。
 *
 * <p>与 Python 版语义一致，不要求源码逐行一致，也不要求 Trace 字段顺序一致：
 * 只要求关键事件类型与顺序一致（见 {@link #RUNTIME_EVENT_ORDER}）。
 *
 * <p>核心判断：自然语言「请用户确认」不是机器可识别的等待信号；
 * {@code waiting=true} 不等于循环已经停止；Gateway 拦住写操作不等于 Run 已暂停。
 * 只有 Runtime 把结构化信号解释成状态迁移，调度器才会退出当前 Run。
 *
 * <p>本实现只覆盖单进程：等待 → 当前调用返回。跨进程恢复、审批与动作绑定、
 * 重复审批幂等属于 C12，不在这里声称已经实现。
 */
public final class Main {

    static final String RUN_ID = "run_001";
    static final int DEFAULT_MAX_STEPS = 8;

    static final String TASK =
            "根据给定主题和本地资料，整理有依据的技术文章创作纲领，然后等待用户确认。"
                    + "未经确认，不生成正文草稿。";

    static final List<String> ALL_TOOLS =
            List.of("search_materials", "read_material", "create_brief", "write_draft");

    /** 一次等待在 Trace 里必须留下的五类事件，按顺序出现。Python 与 Java 必须一致。 */
    static final List<String> RUNTIME_EVENT_ORDER =
            List.of("signal_received", "runtime_validated", "state_transition", "pending_recorded", "run_exited");

    static final String MATERIAL_HINT =
            "可用资料：" + String.join("、", State.VISIBLE_MATERIALS) + "。"
                    + "资料读取顺序由工具返回的结果决定，不要预设顺序。";

    static final String DECISION_HINT =
            "决定类型（只返回其中一种）：\n"
                    + "- {\"type\": \"EXECUTE_TOOL\", \"action\": \"工具名\", \"args\": {...}}\n"
                    + "- {\"type\": \"REQUEST_USER_INPUT\", \"question\": \"要问用户的问题\"}\n"
                    + "- {\"type\": \"FINISH\"}\n";

    static final String TOOL_HINT =
            "可用工具：\n"
                    + "- search_materials: {\"query\": \"关键词\"}\n"
                    + "- read_material: {\"material_id\": \"资料ID\"}\n"
                    + "- create_brief: {\"content\": \"纲领正文\", \"evidence_ids\": [\"引用的资料ID\"]}\n"
                    + "- write_draft: {\"content\": \"草稿正文\"}（受保护写入，确认前绝不能用）\n";

    /**
     * 工具名白名单判定。
     *
     * <p>必须显式挡掉 null：{@code List.of(...).contains(null)} 会抛 NullPointerException，
     * 而「决定里没带 action」是合法输入，应由前置条件校验拒绝，而不是让程序崩掉。
     */
    static boolean isKnownTool(String action) {
        return action != null && ALL_TOOLS.contains(action);
    }

    // ── 协议：决定类型、运行状态、待处理请求、迁移 ─────────────────────

    /** 控制器能提出的四种决定。提出决定的一方不负责它会不会被执行。 */
    enum DecisionType {
        EXECUTE_TOOL,
        REQUEST_USER_INPUT,
        FINISH,
        FAIL
    }

    /** 一次 Run 的状态。WAITING_FOR_USER 由 Runtime 写入，模型无法直接设置。 */
    enum RunStatus {
        RUNNING,
        WAITING_FOR_USER,
        COMPLETED,
        FAILED
    }

    record ControlDecision(
            DecisionType type, String action, Map<String, Object> args, String question, String reason) {}

    /** 等待中的请求：C11 只记到这一步，「用户回来后关联哪个请求」靠它。 */
    record PendingRequest(String requestId, String question, String stateBefore) {}

    /** Runtime 对一条决定的裁决：kind 回答「调度该怎么办」。 */
    record Transition(String kind, RunStatus status, String note) {}

    /** 一次决定、一次校验或一次迁移的最小记录。前十个字段是 Trace 契约。 */
    static final class TraceEvent {
        final int step;
        final String event;

        // Trace 契约
        String signalSource;
        String signalType;
        String runtimeDecision;
        String stateBefore;
        String stateAfter;
        String pendingRequestId;
        Integer modelCallsAfterWait;
        Integer toolCallsAfterWait;

        // 演示用
        String mode = "";
        String decisionMaker = "";
        String action;
        String result;

        TraceEvent(int step, String event) {
            this.step = step;
            this.event = event;
        }

        /** 输出时丢掉空字段，避免 Trace 被 null 淹没。 */
        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("run_id", RUN_ID);
            map.put("step", step);
            put(map, "signal_source", signalSource);
            put(map, "signal_type", signalType);
            put(map, "runtime_decision", runtimeDecision);
            put(map, "state_before", stateBefore);
            put(map, "state_after", stateAfter);
            put(map, "pending_request_id", pendingRequestId);
            put(map, "model_calls_after_wait", modelCallsAfterWait);
            put(map, "tool_calls_after_wait", toolCallsAfterWait);
            put(map, "mode", mode);
            put(map, "decision_maker", decisionMaker);
            put(map, "event", event);
            put(map, "action", action);
            put(map, "result", result);
            return map;
        }

        private static void put(Map<String, Object> map, String key, Object value) {
            if (value != null && !"".equals(value)) {
                map.put(key, value);
            }
        }
    }

    record ModeResult(
            String mode,
            State.RunState finalState,
            List<TraceEvent> trace,
            String terminalReason,
            int modelCallsAfterWait,
            int toolCallsAfterWait,
            String planStatus,
            List<String> planViolations) {}

    // ── 运行态与隔离存储 ───────────────────────────────────────────────

    static final class State {

        /** 三种控制器共享的同一份初始资料清单：知道有哪些资料，不知道读取顺序。 */
        static final List<String> VISIBLE_MATERIALS = List.of("material_1", "material_2");

        /** 教学合成资料。material_1 首次读取只返回部分内容 + 一条指向 material_2 的线索。 */
        static final Map<String, Map<String, String>> MATERIALS = buildMaterials();

        private static Map<String, Map<String, String>> buildMaterials() {
            Map<String, Map<String, String>> materials = new LinkedHashMap<>();
            Map<String, String> m1 = new LinkedHashMap<>();
            m1.put("title", "Agent 控制模式（局部摘录）");
            m1.put("summary", "讲 ReAct 与 Workflow 在决策粒度上的差异，但关键结论在另一份资料里。");
            m1.put("content",
                    "ReAct 在每次观察后重新选择动作；Workflow 预定义主要节点。"
                            + "两者的决策粒度不同，但如何组合、审批边界放在哪里，"
                            + "需要结合 material_2 里关于「运行时准入」的部分才能下结论。");
            m1.put("follow_up", "这份摘录不完整。继续读 material_2 才能得到关于审批边界与运行时准入的结论。");
            materials.put("material_1", m1);

            Map<String, String> m2 = new LinkedHashMap<>();
            m2.put("title", "审批边界与运行时准入（完整）");
            m2.put("summary", "写操作在通过运行时检查后才执行；等待是控制结果而非数据标记。");
            m2.put("content",
                    "生成纲领后必须等待用户确认，确认前不得写正文。"
                            + "「请求审批」与「已经获批」是两个独立状态：前者是控制流状态，"
                            + "后者是外部输入。运行时在写操作前检查获批状态，未获批则拒绝。");
            m2.put("follow_up", "");
            materials.put("material_2", m2);
            return materials;
        }

        /** 一次 Run 的可变状态。每种控制器运行前都新建实例，避免产物泄漏。 */
        static final class RunState {
            // 状态：由 Runtime 写入，控制器和模型都改不了
            RunStatus status = RunStatus.RUNNING;

            boolean briefCreated;
            String briefContent = "";
            List<String> briefEvidenceIds = new ArrayList<>();
            boolean draftWritten;

            boolean approvalGranted;      // 外部输入，与「等待」这个状态严格分开
            PendingRequest pending;       // Run 进入 WAITING_FOR_USER 时写入
            boolean waitingMarker;        // 缺陷版用的数据标记

            int modelCalls;
            int toolAttempts;
            int toolSuccesses;
            int rejectedActions;
            int rejectedSignals;
            Map<String, Integer> usage = new LinkedHashMap<>();

            Integer modelCallsAtWait;
            Integer toolAttemptsAtWait;

            void snapshotWait() {
                modelCallsAtWait = modelCalls;
                toolAttemptsAtWait = toolAttempts;
            }

            /** 返回 {模型调用增量, 工具尝试增量}。未进入等待态时恒为 {0, 0}。 */
            int[] callsAfterWait() {
                if (modelCallsAtWait == null || toolAttemptsAtWait == null) {
                    return new int[] {0, 0};
                }
                return new int[] {modelCalls - modelCallsAtWait, toolAttempts - toolAttemptsAtWait};
            }
        }

        /** 隔离产物存储：只存在内存里，不写公开博客、不提交 Git、不部署。 */
        static final class VirtualStore {
            final Map<String, String> briefs = new LinkedHashMap<>();
            final Map<String, String> drafts = new LinkedHashMap<>();

            int briefCount() {
                return briefs.size();
            }

            int draftCount() {
                return drafts.size();
            }

            boolean hasDraft(String id) {
                return drafts.containsKey(id);
            }
        }
    }

    // ── 工具 + 动作准入 ────────────────────────────────────────────────

    /**
     * 动作准入与分派。
     *
     * <p>职责边界：Gateway 回答「这个动作能不能执行」；Runtime 回答
     * 「当前 Run 要不要继续」。被 Gateway 拦住只说明动作没执行，不说明 Run 已暂停。
     */
    static final class ActionGateway {
        final State.VirtualStore store;
        private final Set<String> readLog = new HashSet<>();
        private final boolean followUpEnabled;

        ActionGateway(State.VirtualStore store) {
            this(store, true);
        }

        ActionGateway(State.VirtualStore store, boolean followUpEnabled) {
            this.store = store;
            this.followUpEnabled = followUpEnabled;
        }

        String execute(String action, Map<String, Object> args, State.RunState state) {
            state.toolAttempts++;
            if (!isKnownTool(action)) {
                state.rejectedActions++;
                return "blocked_unknown_action";
            }
            String result = dispatch(action, args, state);
            if (isSuccess(result)) {
                state.toolSuccesses++;
            } else if (result.startsWith("blocked") || result.startsWith("invalid")) {
                state.rejectedActions++;
            }
            return result;
        }

        private String dispatch(String action, Map<String, Object> args, State.RunState state) {
            return switch (action) {
                case "search_materials" -> search(string(args, "query"));
                case "read_material" -> read(string(args, "material_id"));
                case "create_brief" -> createBrief(args, state);
                case "write_draft" -> writeDraft(args, state);
                default -> "blocked_unknown_action";
            };
        }

        private String search(String query) {
            if (query == null || query.isBlank()) {
                return "invalid_query";
            }
            StringBuilder sb = new StringBuilder();
            State.MATERIALS.forEach((id, m) -> sb.append(id).append(": ").append(m.get("title"))
                    .append(" — ").append(m.get("summary")).append("\n"));
            return sb.toString().strip();
        }

        private String read(String materialId) {
            if (materialId == null || !State.MATERIALS.containsKey(materialId)) {
                return "material_not_found: " + materialId;
            }
            Map<String, String> m = State.MATERIALS.get(materialId);
            boolean firstRead = !readLog.contains(materialId);
            readLog.add(materialId);
            // 线索只能从工具结果拿到，绝不写进模型提示词。
            if ("material_1".equals(materialId) && firstRead && followUpEnabled) {
                return "[partial] " + m.get("content") + "\nfollow_up: " + m.get("follow_up");
            }
            return "[full] " + m.get("content");
        }

        private String createBrief(Map<String, Object> args, State.RunState state) {
            String content = string(args, "content");
            Object evidence = args.get("evidence_ids");
            if (content == null || content.isBlank() || !(evidence instanceof List<?> ids)) {
                return "invalid_brief_arguments";
            }
            for (Object id : ids) {
                if (!(id instanceof String s) || !State.MATERIALS.containsKey(s)) {
                    return "invalid_evidence_id: " + id;
                }
            }
            state.briefCreated = true;
            state.briefContent = content;
            state.briefEvidenceIds = ids.stream().map(Object::toString).toList();
            store.briefs.put("brief_1", content);
            return "brief_created: brief_1";
        }

        private String writeDraft(Map<String, Object> args, State.RunState state) {
            // 受保护写入：准入检查必须在实际写入之前。
            if (!state.approvalGranted) {
                return "blocked_by_action_gateway";
            }
            String content = string(args, "content");
            if (content == null || content.isBlank()) {
                return "invalid_draft_content";
            }
            state.draftWritten = true;
            store.drafts.put("draft_1", content);
            return "draft_written";
        }

        private static boolean isSuccess(String result) {
            return !(result.startsWith("blocked")
                    || result.startsWith("invalid")
                    || result.startsWith("material_not_found"));
        }

        private static String string(Map<String, Object> args, String key) {
            Object value = args == null ? null : args.get(key);
            return value == null ? null : String.valueOf(value);
        }
    }

    // ── Runtime：唯一拥有状态迁移权的组件 ──────────────────────────────

    static final class Runtime {
        private final ActionGateway gateway;
        private int pendingSeq;

        Runtime(ActionGateway gateway) {
            this.gateway = gateway;
        }

        /**
         * 步骤 1-2：解析 + 类型校验。
         *
         * <p>返回 null 表示 Runtime 无法解释这条输出——自然语言（例如「请用户确认」）
         * 在这里就会失败，它不是机器可识别的等待信号。解析失败不静默当成正常动作，
         * 而是让 Run 以明确失败结束。
         */
        ControlDecision parse(Object raw) {
            if (!(raw instanceof Map<?, ?> map)) {
                return null;
            }
            Object typeValue = map.get("type");
            if (!(typeValue instanceof String text)) {
                return null;
            }
            DecisionType type;
            try {
                type = DecisionType.valueOf(text.strip().toUpperCase());
            } catch (IllegalArgumentException error) {
                return null;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            if (map.get("args") instanceof Map<?, ?> rawArgs) {
                rawArgs.forEach((key, value) -> args.put(String.valueOf(key), value));
            }
            return new ControlDecision(
                    type,
                    map.get("action") == null ? null : String.valueOf(map.get("action")),
                    args,
                    map.get("question") == null ? null : String.valueOf(map.get("question")),
                    map.get("reason") == null ? null : String.valueOf(map.get("reason")));
        }

        /** 步骤 3-5：前置条件校验 → 迁移与记录 → 返回。 */
        Transition handle(
                ControlDecision decision,
                State.RunState state,
                List<TraceEvent> trace,
                String mode,
                int step,
                String signalSource) {
            String stateBefore = state.status.name();
            TraceEvent received = new TraceEvent(step, "signal_received");
            received.signalSource = signalSource;
            received.signalType = decision.type().name();
            received.stateBefore = stateBefore;
            received.mode = mode;
            received.decisionMaker = signalSource;
            trace.add(received);

            // 步骤 3：状态前置条件校验
            String refusal = checkPrecondition(decision, state);
            if (refusal != null) {
                return refuse(decision, state, trace, mode, step, refusal);
            }

            TraceEvent validated = new TraceEvent(step, "runtime_validated");
            validated.signalType = decision.type().name();
            validated.runtimeDecision = "ACCEPT_SIGNAL";
            validated.stateBefore = stateBefore;
            validated.stateAfter = stateBefore;
            validated.mode = mode;
            validated.decisionMaker = "runtime";
            validated.result = "accepted";
            trace.add(validated);

            // 步骤 4：迁移与记录
            if (decision.type() == DecisionType.EXECUTE_TOOL) {
                String result = gateway.execute(decision.action(), decision.args(), state);
                TraceEvent executed = new TraceEvent(step, "tool_executed");
                executed.stateBefore = stateBefore;
                executed.stateAfter = state.status.name();
                executed.mode = mode;
                executed.decisionMaker = "runtime";
                executed.action = decision.action();
                executed.result = result;
                trace.add(executed);
                return new Transition("CONTINUE", state.status, result);
            }

            if (decision.type() == DecisionType.REQUEST_USER_INPUT) {
                pendingSeq++;
                PendingRequest pending = new PendingRequest(
                        String.format("approval_%03d", pendingSeq),
                        decision.question() == null ? "" : decision.question().strip(),
                        stateBefore);
                state.pending = pending;
                state.status = RunStatus.WAITING_FOR_USER;
                // 快照必须在迁移的同一时刻记下，否则无法证明「等待之后没有新调用」。
                state.snapshotWait();

                TraceEvent transition = new TraceEvent(step, "state_transition");
                transition.signalType = decision.type().name();
                transition.runtimeDecision = "SUSPEND_RUN";
                transition.stateBefore = stateBefore;
                transition.stateAfter = state.status.name();
                transition.mode = mode;
                transition.decisionMaker = "runtime";
                transition.result = "run_suspended";
                trace.add(transition);

                TraceEvent recorded = new TraceEvent(step, "pending_recorded");
                recorded.stateBefore = state.status.name();
                recorded.stateAfter = state.status.name();
                recorded.pendingRequestId = pending.requestId();
                recorded.mode = mode;
                recorded.decisionMaker = "runtime";
                recorded.action = decision.type().name();
                recorded.result = "question=" + pending.question();
                trace.add(recorded);

                return new Transition("SUSPEND", state.status, "run_suspended");
            }

            if (decision.type() == DecisionType.FINISH) {
                state.status = RunStatus.COMPLETED;
                trace.add(transitionEvent(step, stateBefore, state, mode, "FINISH"));
                return new Transition("COMPLETE", state.status, "completed");
            }

            state.status = RunStatus.FAILED;
            trace.add(transitionEvent(step, stateBefore, state, mode, "FAIL"));
            return new Transition("FAIL", state.status,
                    decision.reason() == null ? "controller_failed" : decision.reason());
        }

        /**
         * 状态前置条件：当前状态允不允许这条决定。
         *
         * <p>这一层就是「非法状态下的等待信号」被拒绝的地方。没有它，
         * 模型可以在任何时刻宣称「我要等用户」——而那时根本没有等待的对象。
         */
        private String checkPrecondition(ControlDecision decision, State.RunState state) {
            if (decision.type() == DecisionType.EXECUTE_TOOL) {
                if (!isKnownTool(decision.action())) {
                    return "unknown_action: " + decision.action();
                }
                return null;
            }
            if (decision.type() == DecisionType.REQUEST_USER_INPUT) {
                if (!state.briefCreated) {
                    return "wait_signal_without_pending_object";
                }
                if (decision.question() == null || decision.question().strip().isEmpty()) {
                    return "wait_signal_without_question";
                }
                return null;
            }
            if (decision.type() == DecisionType.FINISH) {
                if (!state.briefCreated) {
                    return "premature_finish";
                }
                return null;
            }
            return null;
        }

        /**
         * 拒绝一条决定。两类后果必须区分：
         * 等待信号不合法 → Run 明确失败；工具动作不合法 / 过早完成 → 拒绝这一条，Run 继续跑。
         */
        private Transition refuse(
                ControlDecision decision,
                State.RunState state,
                List<TraceEvent> trace,
                String mode,
                int step,
                String reason) {
            TraceEvent rejected = new TraceEvent(step, "runtime_validated");
            rejected.signalType = decision.type().name();
            rejected.runtimeDecision = "REJECT_SIGNAL";
            rejected.stateBefore = state.status.name();
            rejected.stateAfter = state.status.name();
            rejected.mode = mode;
            rejected.decisionMaker = "runtime";
            rejected.action = decision.action();
            rejected.result = reason;
            trace.add(rejected);

            if (decision.type() == DecisionType.REQUEST_USER_INPUT) {
                state.rejectedSignals++;
                String stateBefore = state.status.name();
                state.status = RunStatus.FAILED;
                trace.add(transitionEvent(step, stateBefore, state, mode, "RUN_TO_FAILED"));
                return new Transition("FAIL", state.status, reason);
            }

            state.rejectedActions++;
            return new Transition("CONTINUE", state.status, reason);
        }

        private static TraceEvent transitionEvent(
                int step, String stateBefore, State.RunState state, String mode, String label) {
            TraceEvent event = new TraceEvent(step, "state_transition");
            event.runtimeDecision = label;
            event.stateBefore = stateBefore;
            event.stateAfter = state.status.name();
            event.mode = mode;
            event.decisionMaker = "runtime";
            event.result = state.status.name();
            return event;
        }
    }

    /**
     * 「Run 真的停了吗」的可复用判据。
     *
     * <p>四个条件同时成立才算停：状态是 WAITING_FOR_USER（不是某个布尔为真）；
     * 等待之后没有新的模型调用；等待之后没有新的工具尝试；状态里留下了 pending request。
     * 缺陷版会在这条判据上失败——这正是它被测试发现的方式。
     */
    static boolean isRunSuspended(ModeResult result) {
        State.RunState state = result.finalState();
        int[] delta = state.callsAfterWait();
        return state.status == RunStatus.WAITING_FOR_USER
                && state.pending != null
                && delta[0] == 0
                && delta[1] == 0;
    }

    /** 抽出「一次等待」留下的事件序列，用于顺序断言与跨语言对照。 */
    static List<String> waitEventSequence(List<TraceEvent> trace) {
        int exitIndex = -1;
        for (int index = 0; index < trace.size(); index++) {
            if ("run_exited".equals(trace.get(index).event)) {
                exitIndex = index;
            }
        }
        if (exitIndex < 0) {
            return List.of();
        }
        int start = exitIndex;
        while (start > 0 && !"signal_received".equals(trace.get(start).event)) {
            start--;
        }
        List<String> sequence = new ArrayList<>();
        for (int index = start; index <= exitIndex; index++) {
            sequence.add(trace.get(index).event);
        }
        return sequence;
    }

    // ── LLM 客户端 ─────────────────────────────────────────────────────

    interface LlmClient {
        Map<String, Object> completeJson(String purpose, String system, String user) throws Exception;

        int calls();

        Map<String, Integer> usage();
    }

    /**
     * 确定性离线夹具：只验证控制流（Runtime 迁移、调度停止、Trace 契约），
     * <b>不能</b>证明真实模型行为。真实模型证据必须另行运行并如实报告。
     *
     * <p>本 Java demo 不提供真实模型客户端；真实模型实验在 Python 侧执行，
     * 这属于两种语言之间的已知不对称项，验证记录里必须写明。
     */
    static class FixtureLlmClient implements LlmClient {
        static final String BRIEF_CONTENT = "决策粒度不同的控制模式；审批边界由运行时准入保证。";
        static final String READY_QUESTION = "创作纲领已生成，是否继续生成正文草稿？";

        /** 夹具的默认 ReAct 序列：最后一条重复，缺陷版会一直转而不是靠 FINISH 意外停下。 */
        static final List<Map<String, Object>> DEFAULT_REACT_DECISIONS = List.of(
                decision("EXECUTE_TOOL", "search_materials", Map.of("query", "控制模式"), null),
                decision("EXECUTE_TOOL", "read_material", Map.of("material_id", "material_1"), null),
                decision("EXECUTE_TOOL", "read_material", Map.of("material_id", "material_2"), null),
                decision("EXECUTE_TOOL", "create_brief",
                        Map.of("content", BRIEF_CONTENT, "evidence_ids", List.of("material_1", "material_2")), null),
                decision("REQUEST_USER_INPUT", null, Map.of(), READY_QUESTION));

        static final List<Map<String, Object>> DEFAULT_PLAN_STEPS = List.of(
                decision("EXECUTE_TOOL", "search_materials", Map.of("query", "控制模式"), null),
                decision("EXECUTE_TOOL", "read_material", Map.of("material_id", "material_1"), null),
                decision("EXECUTE_TOOL", "read_material", Map.of("material_id", "material_2"), null),
                decision("EXECUTE_TOOL", "create_brief",
                        Map.of("content", BRIEF_CONTENT, "evidence_ids", List.of("material_1", "material_2")), null),
                decision("REQUEST_USER_INPUT", null, Map.of(), READY_QUESTION),
                // 等待之后的步骤：正确实现在此之前就挂起了，根本不会走到这一步。
                decision("EXECUTE_TOOL", "write_draft", Map.of("content", "正文草稿"), null));

        static Map<String, Object> decision(
                String type, String action, Map<String, Object> args, String question) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            if (action != null) {
                map.put("action", action);
            }
            map.put("args", args);
            if (question != null) {
                map.put("question", question);
            }
            return map;
        }

        private int calls;
        private int reactIndex;
        private final List<Map<String, Object>> reactDecisions;
        private final List<Map<String, Object>> planSteps;
        final List<String[]> prompts = new ArrayList<>();

        FixtureLlmClient() {
            this(null, null);
        }

        FixtureLlmClient(List<Map<String, Object>> reactDecisions, List<Map<String, Object>> planSteps) {
            this.reactDecisions = reactDecisions == null
                    ? new ArrayList<>(DEFAULT_REACT_DECISIONS) : new ArrayList<>(reactDecisions);
            this.planSteps = planSteps == null
                    ? new ArrayList<>(DEFAULT_PLAN_STEPS) : new ArrayList<>(planSteps);
        }

        @Override
        public Map<String, Object> completeJson(String purpose, String system, String user) {
            prompts.add(new String[] {purpose, system, user});
            calls++;
            return switch (purpose) {
                case "react" -> {
                    if (reactDecisions.isEmpty()) {
                        throw new IllegalStateException("react fixture 序列为空");
                    }
                    int index = Math.min(reactIndex, reactDecisions.size() - 1);
                    reactIndex++;
                    yield new LinkedHashMap<>(reactDecisions.get(index));
                }
                case "plan" -> Map.of("steps", new ArrayList<>(planSteps));
                case "workflow_content" -> Map.of("brief_summary", BRIEF_CONTENT);
                default -> throw new IllegalArgumentException("unknown fixture purpose: " + purpose);
            };
        }

        @Override
        public int calls() {
            return calls;
        }

        @Override
        public Map<String, Integer> usage() {
            Map<String, Integer> usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", 0);
            usage.put("completion_tokens", 0);
            usage.put("total_tokens", 0);
            return usage;
        }
    }

    // ── 计划校验：只检查结构 ───────────────────────────────────────────

    record PlanValidation(String status, List<String> violations) {}

    /**
     * 只检查计划结构，不执行计划、不制造审批结果、不静默修改计划。
     *
     * <p>它证明「计划结构合法」，不证明「审批已经授予」，也不证明「Run 会停下来」。
     */
    static PlanValidation validatePlan(List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) {
            return new PlanValidation("REJECT", List.of("empty_plan"));
        }
        List<String> violations = new ArrayList<>();
        Set<String> knownTypes = new HashSet<>();
        for (DecisionType type : DecisionType.values()) {
            knownTypes.add(type.name());
        }
        Set<String> seen = new HashSet<>();
        boolean hasUnknown = false;

        for (int index = 1; index <= steps.size(); index++) {
            Map<String, Object> step = steps.get(index - 1);
            String raw = step.get("type") == null ? "" : String.valueOf(step.get("type"));
            String decisionType = raw.strip().toUpperCase();

            if (!knownTypes.contains(decisionType)) {
                hasUnknown = true;
                violations.add("step-" + index + ": unknown decision type "
                        + (decisionType.isEmpty() ? "<missing>" : decisionType));
                continue;
            }
            if (DecisionType.EXECUTE_TOOL.name().equals(decisionType)) {
                String action = step.get("action") == null ? null : String.valueOf(step.get("action"));
                if (!isKnownTool(action)) {
                    hasUnknown = true;
                    violations.add("step-" + index + ": unknown action " + action);
                    continue;
                }
                if ("write_draft".equals(action) && !seen.contains(DecisionType.REQUEST_USER_INPUT.name())) {
                    violations.add("step-" + index + ": write_draft requires REQUEST_USER_INPUT before it");
                }
            }
            seen.add(decisionType);
        }

        if (hasUnknown) {
            return new PlanValidation("REJECT", violations);
        }
        if (!violations.isEmpty()) {
            return new PlanValidation("REVISE", violations);
        }
        return new PlanValidation("ACCEPT", violations);
    }

    // ── 控制器：等待信号的三个来源 ─────────────────────────────────────

    /** 控制器统一接口。注意它没有 setState 之类的方法——它改不了状态。 */
    abstract static class Controller {
        String name() {
            return "controller";
        }

        String signalSource() {
            return "controller";
        }

        abstract Map<String, Object> propose(State.RunState state, int step) throws Exception;

        /** 工具执行完的回执。只有 ReAct / Workflow 会用它。 */
        void onToolResult(String result) {}

        /** 计划状态只在 Planner 上非空；调度器用它判断「校验过一次了」。 */
        String plannedStatus() {
            return null;
        }

        List<String> planViolations() {
            return List.of();
        }
    }

    /**
     * 模型在每次观察后重新提出决定。
     *
     * <p>它能看到完整的历史观察（不是只看最新一条）——旧稿把「ReAct 只能看到当前观察」
     * 写成绕圈的原因，那句话与代码不符，本版不沿用这个说法。
     */
    static final class ReactController extends Controller {
        private final LlmClient client;
        private final List<String> observations = new ArrayList<>();

        ReactController(LlmClient client) {
            this.client = client;
        }

        @Override
        String name() {
            return "react";
        }

        @Override
        String signalSource() {
            return "llm";
        }

        @Override
        Map<String, Object> propose(State.RunState state, int step) throws Exception {
            String system = "你是 ReAct 风格控制器。每次只提出一个决定，返回 JSON 对象。\n"
                    + MATERIAL_HINT + "\n"
                    + DECISION_HINT
                    + TOOL_HINT
                    + "规则：先读资料，再生成创作纲领（create_brief）。"
                    + "纲领生成之后，如果需要用户确认才能继续，就提出 REQUEST_USER_INPUT；"
                    + "确认之前绝不能用 write_draft。";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("task", TASK);
            payload.put("observations", observations);
            payload.put("brief_created", state.briefCreated);
            payload.put("approval_granted", state.approvalGranted);
            return client.completeJson("react", system, toJson(payload));
        }

        @Override
        void onToolResult(String result) {
            observations.add(result);
        }
    }

    /**
     * 等待是计划里的一步。
     *
     * <p>计划文本本身没有暂停能力：执行器把「请求用户输入」这一步照原样交给 Runtime，
     * 真正决定停止的是 Runtime，不是那份计划。
     */
    static final class PlannerController extends Controller {
        private final LlmClient client;
        private List<Map<String, Object>> steps = new ArrayList<>();
        private int index;
        private String planStatus;
        private List<String> planViolations = List.of();
        private boolean planned;

        PlannerController(LlmClient client) {
            this.client = client;
        }

        @Override
        String name() {
            return "plan_and_execute";
        }

        @Override
        String signalSource() {
            return "planner";
        }

        @Override
        String plannedStatus() {
            return planStatus;
        }

        @Override
        List<String> planViolations() {
            return planViolations;
        }

        @Override
        Map<String, Object> propose(State.RunState state, int step) throws Exception {
            if (!planned) {
                planned = true;
                Map<String, Object> response = client.completeJson(
                        "plan",
                        "你是 Planner。返回 JSON：{\"steps\": [ ... ]}。\n"
                                + MATERIAL_HINT + "\n每一步都是一个决定：\n"
                                + DECISION_HINT
                                + TOOL_HINT
                                + "规则：create_brief 之后、write_draft 之前必须放一个 "
                                + "REQUEST_USER_INPUT 步（等待用户确认，它不是工具）。",
                        toJson(Map.of("task", TASK)));
                steps = asStepList(response.get("steps"));
                PlanValidation validation = validatePlan(steps);
                planStatus = validation.status();
                planViolations = validation.violations();
            }
            if (index >= steps.size()) {
                // 计划走完但没有等到结果（例如计划里没有等待步）
                return FixtureLlmClient.decision("FINISH", null, Map.of(), null);
            }
            Map<String, Object> stepDecision = steps.get(index);
            index++;
            return stepDecision;
        }
    }

    /**
     * 等待是代码里的固定节点。
     *
     * <p>节点序列由代码决定，节点内部的内容仍由模型生成。第 3 个节点是
     * <b>真实的条件分支</b>：只有工具结果里真的带回了指向另一份资料的线索，
     * 才会去读那份资料。旧版在这里无条件读 material_2，却在 Trace 里把它标成
     * 「读到 follow_up 触发的分支」——那是假的。
     */
    static final class WorkflowController extends Controller {
        private final LlmClient client;
        private int node;
        private String lastResult = "";
        Boolean branchTaken;
        final List<String> readIds = new ArrayList<>();

        WorkflowController(LlmClient client) {
            this.client = client;
        }

        @Override
        String name() {
            return "workflow";
        }

        @Override
        String signalSource() {
            return "workflow";
        }

        @Override
        Map<String, Object> propose(State.RunState state, int step) throws Exception {
            node++;

            if (node == 1) {
                return FixtureLlmClient.decision(
                        "EXECUTE_TOOL", "search_materials", Map.of("query", "控制模式"), null);
            }
            if (node == 2) {
                String targetId = State.VISIBLE_MATERIALS.get(0);
                readIds.add(targetId);
                return FixtureLlmClient.decision(
                        "EXECUTE_TOOL", "read_material", Map.of("material_id", targetId), null);
            }
            if (node == 3) {
                // 真实分支：看上一个节点的实际结果，而不是假设它一定带线索。
                String target = followUpTarget(lastResult);
                if (target != null && !readIds.contains(target)) {
                    branchTaken = true;
                    readIds.add(target);
                    return FixtureLlmClient.decision(
                            "EXECUTE_TOOL", "read_material", Map.of("material_id", target), null);
                }
                branchTaken = false;
                // 没有线索就不读第二份：把节点指针推到读取节点的下一位，
                // 让下一个节点去生成纲领。否则纲领会在节点 3、4 各生成一次。
                node = 4;
                return proposeBrief(state);
            }
            if (node == 4) {
                return proposeBrief(state);
            }
            if (node == 5) {
                return FixtureLlmClient.decision(
                        "REQUEST_USER_INPUT", null, Map.of(), "创作纲领已生成，是否继续生成正文草稿？");
            }
            // 正常流程不会走到这里：节点 5 提出等待后，Runtime 会让 Run 挂起。
            return FixtureLlmClient.decision("FINISH", null, Map.of(), null);
        }

        @Override
        void onToolResult(String result) {
            // 只记下最近一次工具结果：第 3 个节点要根据它判断要不要走进分支。
            lastResult = result;
        }

        /** 节点内容由模型生成，路由仍由代码决定。 */
        private Map<String, Object> proposeBrief(State.RunState state) throws Exception {
            Map<String, Object> response = client.completeJson(
                    "workflow_content",
                    "你只负责生成创作纲领摘要，返回 JSON：{\"brief_summary\": \"...\"}。",
                    toJson(Map.of("task", TASK, "read", readIds)));
            Object summaryValue = response.get("brief_summary");
            String summary = summaryValue == null ? "" : String.valueOf(summaryValue);
            List<String> evidence = readIds.isEmpty() ? State.VISIBLE_MATERIALS : List.copyOf(readIds);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("content", summary);
            args.put("evidence_ids", evidence);
            return FixtureLlmClient.decision("EXECUTE_TOOL", "create_brief", args, null);
        }

        /** 从工具结果里解析「接下来该读哪一份资料」。没有线索就返回 null。 */
        private static String followUpTarget(String result) {
            if (result == null || !result.contains("follow_up:")) {
                return null;
            }
            Matcher matcher = Pattern.compile("(material_\\d+)").matcher(result.split("follow_up:", 2)[1]);
            return matcher.find() ? matcher.group(1) : null;
        }
    }

    /**
     * 按脚本提出决定，用于确定性测试与故障注入。
     *
     * <p>它不代表任何一种真实控制模式，只用来把某一条决定单独喂给 Runtime 或朴素执行器。
     */
    static final class ScriptedController extends Controller {
        private final List<Map<String, Object>> decisions;
        private int index;

        ScriptedController(List<Map<String, Object>> decisions) {
            this.decisions = new ArrayList<>(decisions);
        }

        @Override
        String name() {
            return "scripted";
        }

        @Override
        String signalSource() {
            return "script";
        }

        @Override
        Map<String, Object> propose(State.RunState state, int step) {
            if (index >= decisions.size()) {
                return FixtureLlmClient.decision("FINISH", null, Map.of(), null);
            }
            Map<String, Object> decision = decisions.get(index);
            index++;
            return decision;
        }
    }

    // ── 调度循环 ───────────────────────────────────────────────────────

    /**
     * 带 Runtime 的调度循环。
     *
     * <p>控制器只提出决定；要不要继续跑，由 Runtime 的状态迁移结果决定。
     */
    static ModeResult runWithRuntime(Controller controller, LlmClient client, ActionGateway gateway)
            throws Exception {
        return runWithRuntime(controller, client, gateway, DEFAULT_MAX_STEPS);
    }

    static ModeResult runWithRuntime(
            Controller controller, LlmClient client, ActionGateway gateway, int maxSteps) throws Exception {
        State.RunState state = new State.RunState();
        List<TraceEvent> trace = new ArrayList<>();
        Runtime runtime = new Runtime(gateway);
        String planStatus = null;
        List<String> planViolations = List.of();
        String terminalReason = "budget_exhausted";

        int step = 0;
        while (step < maxSteps) {
            if (state.status != RunStatus.RUNNING) {
                break;
            }
            step++;

            Map<String, Object> raw = controller.propose(state, step);
            // 立刻把调用计数同步进状态：等待快照要记的是「此刻真实发生过的调用数」。
            // 若只在循环结束时同步，快照会是 0，等待后的增量就会被算成全部调用。
            syncStats(client, state);

            // 计划校验只检查结构，不产生审批结果。REJECT 时明确失败，不硬跑下去。
            if (planStatus == null && controller.plannedStatus() != null) {
                planStatus = controller.plannedStatus();
                planViolations = controller.planViolations();
                TraceEvent planEvent = new TraceEvent(step, "validate_plan");
                planEvent.mode = controller.name();
                planEvent.decisionMaker = "plan_validator";
                planEvent.result = planStatus;
                trace.add(planEvent);
                if ("REJECT".equals(planStatus)) {
                    state.status = RunStatus.FAILED;
                    terminalReason = "plan_rejected";
                    break;
                }
            }

            // 步骤 1-2：解析 + 类型校验。无法解释的输出不让它悄悄过去。
            ControlDecision decision = runtime.parse(raw);
            if (decision == null) {
                state.rejectedSignals++;
                String stateBefore = state.status.name();
                state.status = RunStatus.FAILED;
                TraceEvent failed = new TraceEvent(step, "parse_failed");
                failed.signalSource = controller.signalSource();
                failed.runtimeDecision = "REJECT_SIGNAL";
                failed.stateBefore = stateBefore;
                failed.stateAfter = state.status.name();
                failed.mode = controller.name();
                failed.decisionMaker = "runtime";
                failed.result = "unparseable_decision: " + truncate(raw, 160);
                trace.add(failed);
                terminalReason = "parse_failed";
                break;
            }

            // 步骤 3-5：前置条件校验 → 迁移与记录 → 返回
            Transition transition =
                    runtime.handle(decision, state, trace, controller.name(), step, controller.signalSource());

            if ("SUSPEND".equals(transition.kind())) {
                syncStats(client, state);
                String stateAfter = state.status.name();
                int[] delta = state.callsAfterWait();
                TraceEvent exited = new TraceEvent(step, "run_exited");
                exited.signalSource = controller.signalSource();
                exited.signalType = DecisionType.REQUEST_USER_INPUT.name();
                exited.runtimeDecision = "SUSPEND_RUN";
                exited.stateBefore = stateAfter;
                exited.stateAfter = stateAfter;
                exited.pendingRequestId = state.pending == null ? null : state.pending.requestId();
                exited.modelCallsAfterWait = delta[0];
                exited.toolCallsAfterWait = delta[1];
                exited.mode = controller.name();
                exited.decisionMaker = "scheduler";
                exited.result = "control_returned_to_caller";
                trace.add(exited);
                terminalReason = "wait_suspended";
                break;
            }

            if ("CONTINUE".equals(transition.kind())) {
                if (decision.type() == DecisionType.EXECUTE_TOOL) {
                    controller.onToolResult(transition.note());
                }
                continue;
            }

            // COMPLETE / FAIL
            terminalReason = "COMPLETE".equals(transition.kind()) ? "completed" : transition.note();
            break;
        }

        syncStats(client, state);
        return finish(controller.name(), state, trace, terminalReason, planStatus, planViolations);
    }

    /**
     * 缺陷版：循环里没有 Runtime。
     *
     * <p>它把模型输出直接当成「要做的事」，等待信号被当成一条数据：
     * markWaiting=false 时连标记都不记；markWaiting=true 时把等待记成一个数据标记然后继续。
     * 两种写法都<b>没有</b>停止调度——这正是「waiting=true 不等于循环已经停止」的最小复现。
     */
    static ModeResult runNaive(
            Controller controller,
            LlmClient client,
            ActionGateway gateway,
            int maxSteps,
            boolean markWaiting)
            throws Exception {
        State.RunState state = new State.RunState();
        List<TraceEvent> trace = new ArrayList<>();
        String terminalReason = "budget_exhausted";

        int step = 0;
        while (step < maxSteps) {
            step++;
            Map<String, Object> raw = controller.propose(state, step);
            String decisionType = raw != null && raw.get("type") != null
                    ? String.valueOf(raw.get("type")) : null;

            if (DecisionType.EXECUTE_TOOL.name().equals(decisionType)) {
                String action = raw.get("action") == null ? "" : String.valueOf(raw.get("action"));
                String result = gateway.execute(action, asArgs(raw.get("args")), state);
                TraceEvent event = new TraceEvent(step, "tool_executed_without_runtime");
                event.stateBefore = state.status.name();
                event.stateAfter = state.status.name();
                event.mode = controller.name();
                event.decisionMaker = "naive_loop";
                event.action = action;
                event.result = result;
                trace.add(event);
                controller.onToolResult(result);
                continue;
            }

            if (DecisionType.REQUEST_USER_INPUT.name().equals(decisionType)) {
                if (markWaiting) {
                    state.waitingMarker = true;
                }
                TraceEvent event = new TraceEvent(step, "wait_ignored");
                event.signalSource = controller.signalSource();
                event.signalType = decisionType;
                event.stateBefore = state.status.name();
                event.stateAfter = state.status.name();
                event.mode = controller.name();
                event.decisionMaker = "naive_loop";
                event.action = decisionType;
                event.result = markWaiting ? "waiting_marker=true; loop_continues" : "loop_continues";
                trace.add(event);
                continue;
            }

            if (DecisionType.FINISH.name().equals(decisionType)) {
                String stateBefore = state.status.name();
                state.status = RunStatus.COMPLETED;
                TraceEvent event = new TraceEvent(step, "finish_without_runtime");
                event.stateBefore = stateBefore;
                event.stateAfter = state.status.name();
                event.mode = controller.name();
                event.decisionMaker = "naive_loop";
                event.result = "completed";
                trace.add(event);
                terminalReason = "completed";
                break;
            }

            // 无法识别的输出：同样被忽略，循环继续。
            TraceEvent event = new TraceEvent(step, "decision_ignored");
            event.stateBefore = state.status.name();
            event.stateAfter = state.status.name();
            event.mode = controller.name();
            event.decisionMaker = "naive_loop";
            event.result = String.valueOf(raw);
            trace.add(event);
        }

        syncStats(client, state);
        return finish(controller.name(), state, trace, terminalReason, null, List.of());
    }

    private static void syncStats(LlmClient client, State.RunState state) {
        state.modelCalls = client.calls();
        state.usage = client.usage();
    }

    private static ModeResult finish(
            String mode,
            State.RunState state,
            List<TraceEvent> trace,
            String terminalReason,
            String planStatus,
            List<String> planViolations) {
        int[] delta = state.callsAfterWait();
        return new ModeResult(
                mode, state, trace, terminalReason, delta[0], delta[1], planStatus, planViolations);
    }

    // ── 输出 ───────────────────────────────────────────────────────────

    static void printResult(ModeResult result) {
        for (TraceEvent event : result.trace()) {
            System.out.println("[trace] " + toJson(event.asMap()));
        }
        State.RunState state = result.finalState();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mode", result.mode());
        summary.put("terminal_reason", result.terminalReason());
        summary.put("status", state.status.name());
        summary.put("pending_request_id", state.pending == null ? null : state.pending.requestId());
        summary.put("brief_created", state.briefCreated);
        summary.put("draft_written", state.draftWritten);
        summary.put("approval_granted", state.approvalGranted);
        summary.put("waiting_marker", state.waitingMarker);
        summary.put("model_calls", state.modelCalls);
        summary.put("tool_attempts", state.toolAttempts);
        summary.put("tool_successes", state.toolSuccesses);
        summary.put("rejected_actions", state.rejectedActions);
        summary.put("rejected_signals", state.rejectedSignals);
        summary.put("model_calls_after_wait", result.modelCallsAfterWait());
        summary.put("tool_calls_after_wait", result.toolCallsAfterWait());
        summary.put("plan_status", result.planStatus());
        summary.put("usage", state.usage);
        System.out.println("[summary] " + toJson(summary));
    }

    public static void main(String[] args) throws Exception {
        List<String> argv = List.of(args);
        boolean naive = argv.contains("--naive");
        boolean noFollowUp = argv.contains("--no-follow-up");

        List<ModeResult> results = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            // 控制器与调度循环必须共用同一个 client，否则「等待后没有新调用」无从统计。
            LlmClient client = new FixtureLlmClient();
            Controller controller = switch (index) {
                case 0 -> new ReactController(client);
                case 1 -> new PlannerController(client);
                default -> new WorkflowController(client);
            };
            ActionGateway gateway = new ActionGateway(new State.VirtualStore(), !noFollowUp);
            results.add(naive
                    ? runNaive(controller, client, gateway, DEFAULT_MAX_STEPS, true)
                    : runWithRuntime(controller, client, gateway));
        }
        results.forEach(Main::printResult);
    }

    // ── 小工具 ─────────────────────────────────────────────────────────

    static List<Map<String, Object>> asStepList(Object value) {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> step = new LinkedHashMap<>();
                    map.forEach((key, entry) -> step.put(String.valueOf(key), entry));
                    steps.add(step);
                }
            }
        }
        return steps;
    }

    static Map<String, Object> asArgs(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> args = new LinkedHashMap<>();
            map.forEach((key, entry) -> args.put(String.valueOf(key), entry));
            return args;
        }
        return new LinkedHashMap<>();
    }

    /** 极简 JSON 序列化：只覆盖本 demo 用到的 Map / List / 标量。 */
    static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(value, sb);
        return sb.toString();
    }

    private static void writeJson(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String text) {
            sb.append('"').append(escape(text)).append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(entry.getKey()))).append("\":");
                writeJson(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> items) {
            sb.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJson(item, sb);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** 截断长文本，避免 Trace 被整段模型输出淹没。 */
    static String truncate(Object value, int limit) {
        String text = String.valueOf(value);
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
