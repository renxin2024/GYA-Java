package cn.renxinblog.c11;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 版离线测试：与 Python 版覆盖同一批关键语义（等待迁移、等待后无调用、
 * Gateway 拦截不等于暂停、非法状态拒绝、pending 字段、三源同一 Runtime 入口、
 * Trace 事件顺序一致、提示词不硬编码资料顺序、Workflow 分支是真条件分支）。
 *
 * <p>全部离线运行，不需要网络或 API Key。只验证控制流契约，不能证明真实模型行为。
 */
class MainTest {

    private static final Map<String, Object> BRIEF = Main.FixtureLlmClient.decision(
            "EXECUTE_TOOL",
            "create_brief",
            Map.of("content", "纲领正文", "evidence_ids", List.of("material_1")),
            null);
    private static final Map<String, Object> WAIT =
            Main.FixtureLlmClient.decision("REQUEST_USER_INPUT", null, Map.of(), "是否继续生成正文草稿？");
    private static final Map<String, Object> WRITE = Main.FixtureLlmClient.decision(
            "EXECUTE_TOOL", "write_draft", Map.of("content", "正文草稿"), null);

    private record Run(Main.ModeResult result, Main.State.VirtualStore store, Main.LlmClient client) {}

    private static Run runScripted(List<Map<String, Object>> decisions) throws Exception {
        return runScripted(decisions, Main.DEFAULT_MAX_STEPS);
    }

    private static Run runScripted(List<Map<String, Object>> decisions, int maxSteps) throws Exception {
        Main.LlmClient client = new Main.FixtureLlmClient();
        Main.State.VirtualStore store = new Main.State.VirtualStore();
        Main.ModeResult result = Main.runWithRuntime(
                new Main.ScriptedController(decisions), client, new Main.ActionGateway(store), maxSteps);
        return new Run(result, store, client);
    }

    private static Run runNaiveScripted(
            List<Map<String, Object>> decisions, int maxSteps, boolean markWaiting) throws Exception {
        Main.LlmClient client = new Main.FixtureLlmClient();
        Main.State.VirtualStore store = new Main.State.VirtualStore();
        Main.ModeResult result = Main.runNaive(
                new Main.ScriptedController(decisions),
                client,
                new Main.ActionGateway(store),
                maxSteps,
                markWaiting);
        return new Run(result, store, client);
    }

    /** 可复用的判据：状态是 WAITING、有 pending、且等待后没有新调用。 */
    private static void assertRunSuspended(Main.ModeResult result) {
        Main.State.RunState state = result.finalState();
        assertTrue(
                Main.isRunSuspended(result),
                () -> "Run 没有真正停下：status=" + state.status
                        + " pending=" + (state.pending == null ? null : state.pending.requestId())
                        + " after_wait=(" + result.modelCallsAfterWait() + ", " + result.toolCallsAfterWait() + ")");
    }

    private static List<Main.ModeResult> runAllSources() throws Exception {
        List<Main.ModeResult> results = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Main.LlmClient client = new Main.FixtureLlmClient();
            Main.Controller controller = switch (index) {
                case 0 -> new Main.ReactController(client);
                case 1 -> new Main.PlannerController(client);
                default -> new Main.WorkflowController(client);
            };
            results.add(Main.runWithRuntime(
                    controller, client, new Main.ActionGateway(new Main.State.VirtualStore())));
        }
        return results;
    }

    // ── 验收矩阵 1：自然语言不是等待信号 ──────────────────────────────

    @Test
    void naturalLanguageIsNotAWaitSignal() {
        Main.Runtime runtime = new Main.Runtime(new Main.ActionGateway(new Main.State.VirtualStore()));
        assertNull(runtime.parse("请用户确认"), "自然语言不该被解析成决定");
        assertNull(runtime.parse(Map.of("type", "WAIT_FOR_USER")), "未知类型应解析失败");
        assertNull(runtime.parse(Map.of("action", "read_material")), "缺少 type 应解析失败");
        Main.ControlDecision decision = runtime.parse(Map.of("type", "request_user_input", "question", "继续吗？"));
        assertNotNull(decision);
        assertEquals(Main.DecisionType.REQUEST_USER_INPUT, decision.type());
    }

    @Test
    void waitSignalAloneDoesNotStopNaiveLoop() throws Exception {
        Main.LlmClient client = new Main.FixtureLlmClient();
        Main.ModeResult result = Main.runNaive(
                new Main.ReactController(client),
                client,
                new Main.ActionGateway(new Main.State.VirtualStore()),
                8,
                false);
        Main.State.RunState state = result.finalState();
        // 模型提出过等待信号，但循环没有识别它：状态从未进入等待。
        assertTrue(
                result.trace().stream().anyMatch(e -> "wait_ignored".equals(e.event)),
                "缺陷版应记下被忽略的等待");
        assertEquals(Main.RunStatus.RUNNING, state.status);
        assertEquals("budget_exhausted", result.terminalReason());
        assertEquals(8, state.modelCalls, "等待信号之后仍在继续调用模型");
        assertFalse(Main.isRunSuspended(result));
    }

    // ── 验收矩阵 2、8：Runtime 迁移与 pending 记录 ────────────────────

    @Test
    void requestUserInputMovesRunToWaiting() throws Exception {
        Run run = runScripted(List.of(BRIEF, WAIT));
        assertEquals(Main.RunStatus.WAITING_FOR_USER, run.result().finalState().status);
        assertEquals("wait_suspended", run.result().terminalReason());
    }

    @Test
    void pendingRequestRecordsQuestionIdAndStateBefore() throws Exception {
        Run run = runScripted(List.of(BRIEF, WAIT));
        Main.PendingRequest pending = run.result().finalState().pending;
        assertNotNull(pending);
        assertEquals("approval_001", pending.requestId());
        assertEquals(WAIT.get("question"), pending.question());
        assertEquals("RUNNING", pending.stateBefore());
        // pending 同时要出现在 Trace 里，用户回来时才有关联标识。
        Main.TraceEvent exitEvent = run.result().trace().stream()
                .filter(e -> "run_exited".equals(e.event)).findFirst().orElseThrow();
        assertEquals("approval_001", exitEvent.pendingRequestId);
    }

    // ── 验收矩阵 3、4：等待之后没有新调用 ─────────────────────────────

    @Test
    void noModelCallsAfterWait() throws Exception {
        Run run = runScripted(List.of(BRIEF, WAIT, WRITE));
        assertEquals(0, run.result().modelCallsAfterWait());
        assertEquals(
                run.result().finalState().modelCalls,
                run.result().finalState().modelCallsAtWait);
    }

    @Test
    void noToolCallsAfterWait() throws Exception {
        Run run = runScripted(List.of(BRIEF, WAIT, WRITE));
        assertEquals(0, run.result().toolCallsAfterWait());
        assertEquals(
                run.result().finalState().toolAttempts,
                run.result().finalState().toolAttemptsAtWait);
    }

    @Test
    void runReturnsToCaller() throws Exception {
        Run run = runScripted(List.of(BRIEF, WAIT, WRITE));
        assertRunSuspended(run.result());
        Main.TraceEvent exitEvent = run.result().trace().get(run.result().trace().size() - 1);
        assertEquals("run_exited", exitEvent.event);
        assertEquals("control_returned_to_caller", exitEvent.result);
    }

    // ── 验收矩阵 5：缺陷版必须能被测试发现 ───────────────────────────

    @Test
    void waitingMarkerWithoutStopIsDetected() throws Exception {
        Run run = runNaiveScripted(List.of(BRIEF, WAIT, WAIT), 4, true);
        Main.State.RunState state = run.result().finalState();
        // 数据标记为真……
        assertTrue(state.waitingMarker);
        // ……但状态不是等待，循环也还在转：同一条等待信号被忽略了两次。
        assertNotEquals(Main.RunStatus.WAITING_FOR_USER, state.status);
        long ignored = run.result().trace().stream()
                .filter(e -> "wait_ignored".equals(e.event)).count();
        assertEquals(2, ignored, "循环没有停下：等待信号被反复忽略");
        // 判据能抓住它：这就是「测试可以发现缺陷」。
        assertThrows(AssertionError.class, () -> assertRunSuspended(run.result()));
    }

    // ── 验收矩阵 6：Gateway 拦截不等于 Run 暂停 ──────────────────────

    @Test
    void gatewayRejectionIsNotSuspension() throws Exception {
        Run run = runNaiveScripted(List.of(BRIEF, WRITE), 2, false);
        Main.State.RunState state = run.result().finalState();
        assertEquals(0, run.store().draftCount(), "未获批的写入不得落库");
        assertFalse(state.draftWritten);
        assertTrue(state.rejectedActions >= 1);
        assertTrue(run.result().trace().stream()
                .anyMatch(e -> "blocked_by_action_gateway".equals(e.result)));
        // 被拦住 ≠ 停下：Run 既没有进入等待态，也没有 pending request。
        assertNotEquals(Main.RunStatus.WAITING_FOR_USER, state.status);
        assertNull(state.pending);
        assertFalse(Main.isRunSuspended(run.result()));
    }

    // ── 验收矩阵 7：非法状态下的等待信号被拒绝 ────────────────────────

    @Test
    void waitSignalWithoutObjectIsRejected() throws Exception {
        Run run = runScripted(List.of(WAIT));
        Main.State.RunState state = run.result().finalState();
        assertEquals(1, state.rejectedSignals);
        assertEquals(Main.RunStatus.FAILED, state.status);
        assertEquals("wait_signal_without_pending_object", run.result().terminalReason());
        assertNull(state.pending, "被拒绝的等待不得留下 pending request");
    }

    @Test
    void waitSignalWithoutQuestionIsRejected() throws Exception {
        Run run = runScripted(List.of(
                BRIEF, Main.FixtureLlmClient.decision("REQUEST_USER_INPUT", null, Map.of(), null)));
        assertEquals(1, run.result().finalState().rejectedSignals);
        assertEquals(Main.RunStatus.FAILED, run.result().finalState().status);
        assertEquals("wait_signal_without_question", run.result().terminalReason());
    }

    @Test
    void prematureFinishIsRejected() throws Exception {
        // maxSteps=1：只让模型提出一次「完成」，观察它被拒绝而不是被接受。
        Run run = runScripted(
                List.of(Main.FixtureLlmClient.decision("FINISH", null, Map.of(), null)), 1);
        Main.State.RunState state = run.result().finalState();
        assertFalse(state.briefCreated);
        assertEquals(1, state.rejectedActions);
        assertEquals(Main.RunStatus.RUNNING, state.status);
        assertEquals("budget_exhausted", run.result().terminalReason());
    }

    @Test
    void unknownActionNeverBecomesSuccess() throws Exception {
        Run run = runScripted(
                List.of(Main.FixtureLlmClient.decision("EXECUTE_TOOL", "delete_everything", Map.of(), null)), 3);
        Main.State.RunState state = run.result().finalState();
        assertEquals(0, state.toolAttempts, "未知动作不该走到工具层");
        assertTrue(state.rejectedActions >= 1);
        assertFalse(state.briefCreated);
        assertFalse(state.draftWritten);
        assertEquals(0, run.store().draftCount());
    }

    @Test
    void unparseableOutputFailsExplicitly() throws Exception {
        Run run = runScripted(List.of(Map.of("type", "MAYBE_LATER")));
        assertEquals("parse_failed", run.result().terminalReason());
        assertEquals(Main.RunStatus.FAILED, run.result().finalState().status);
        assertEquals(1, run.result().finalState().rejectedSignals);
    }

    // ── 验收矩阵 9、10：三源共用一个 Runtime、Trace 顺序一致 ──────────

    @Test
    void threeSourcesShareOneRuntimeEntry() throws Exception {
        for (Main.ModeResult result : runAllSources()) {
            assertRunSuspended(result);
            assertEquals(
                    Main.RUNTIME_EVENT_ORDER,
                    Main.waitEventSequence(result.trace()),
                    result.mode() + " 的等待事件序列与其他来源不一致");
        }
    }

    @Test
    void onlyRuntimePerformsStateMigration() throws Exception {
        for (Main.ModeResult result : runAllSources()) {
            List<Main.TraceEvent> transitions = result.trace().stream()
                    .filter(e -> "state_transition".equals(e.event)).toList();
            assertFalse(transitions.isEmpty(), result.mode() + " 没有状态迁移事件");
            for (Main.TraceEvent event : transitions) {
                assertEquals("runtime", event.decisionMaker);
            }
            long suspendEvents = result.trace().stream()
                    .filter(e -> "state_transition".equals(e.event)
                            && "SUSPEND_RUN".equals(e.runtimeDecision))
                    .count();
            assertEquals(1, suspendEvents, "迁移点应当唯一");
        }
    }

    @Test
    void traceRecordCarriesContractFields() throws Exception {
        Run run = runScripted(List.of(BRIEF, WAIT));
        Main.TraceEvent record = run.result().trace().stream()
                .filter(e -> "run_exited".equals(e.event)).findFirst().orElseThrow();
        assertEquals("script", record.signalSource);
        assertEquals(Main.DecisionType.REQUEST_USER_INPUT.name(), record.signalType);
        assertEquals("SUSPEND_RUN", record.runtimeDecision);
        assertEquals(Main.RunStatus.WAITING_FOR_USER.name(), record.stateBefore);
        assertEquals(Main.RunStatus.WAITING_FOR_USER.name(), record.stateAfter);
        assertEquals("approval_001", record.pendingRequestId);
        assertEquals(0, record.modelCallsAfterWait);
        assertEquals(0, record.toolCallsAfterWait);
    }

    // ── 本次重构修正的两处问题 ────────────────────────────────────────

    @Test
    void promptsShareOneMaterialListWithoutHardcodedOrder() throws Exception {
        List<String[]> prompts = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            Main.FixtureLlmClient client = new Main.FixtureLlmClient();
            Main.Controller controller = index == 0
                    ? new Main.ReactController(client)
                    : new Main.PlannerController(client);
            Main.runWithRuntime(controller, client, new Main.ActionGateway(new Main.State.VirtualStore()));
            prompts.addAll(client.prompts);
        }

        String materialHint = "可用资料：" + String.join("、", Main.State.VISIBLE_MATERIALS);
        for (String[] prompt : prompts) {
            assertFalse(prompt[1].contains("先读 material_1"), "提示词不得硬编码读取顺序");
            assertFalse(prompt[1].contains("再读 material_2"), "提示词不得硬编码读取顺序");
            assertTrue(prompt[1].contains(materialHint), "两个控制器应拿到同一份资料清单");
        }
    }

    @Test
    void workflowBranchIsConditional() throws Exception {
        // 有 follow_up 线索：真的走进分支读第二份资料。
        Main.FixtureLlmClient client = new Main.FixtureLlmClient();
        Main.WorkflowController controller = new Main.WorkflowController(client);
        Main.ModeResult result = Main.runWithRuntime(
                controller, client, new Main.ActionGateway(new Main.State.VirtualStore()));
        assertTrue(controller.branchTaken);
        assertTrue(controller.readIds.contains("material_2"));
        assertRunSuspended(result);

        // 没有线索：不读第二份资料，分支为假——这才是真条件分支。
        Main.FixtureLlmClient bare = new Main.FixtureLlmClient();
        Main.WorkflowController bareController = new Main.WorkflowController(bare);
        Main.ModeResult bareResult = Main.runWithRuntime(
                bareController, bare, new Main.ActionGateway(new Main.State.VirtualStore(), false));
        assertFalse(bareController.branchTaken);
        assertFalse(bareController.readIds.contains("material_2"));
        assertEquals(List.of("material_1"), bareController.readIds);
        assertRunSuspended(bareResult);
    }

    @Test
    void planValidationIsStructuralOnly() throws Exception {
        // 结构合法：等待点在受保护写入之前。
        assertEquals("ACCEPT", Main.validatePlan(List.of(BRIEF, WAIT, WRITE)).status());
        // 结构缺口：受保护写入之前没有等待点。
        assertEquals("REVISE", Main.validatePlan(List.of(BRIEF, WRITE)).status());
        // 结构非法：未知决定类型。
        assertEquals("REJECT", Main.validatePlan(List.of(Map.of("type", "MAYBE"))).status());

        // 校验通过 ≠ 已经获批：跑完 Planner 夹具后，approval_granted 仍为假。
        Main.FixtureLlmClient client = new Main.FixtureLlmClient();
        Main.PlannerController controller = new Main.PlannerController(client);
        Main.ModeResult result = Main.runWithRuntime(
                controller, client, new Main.ActionGateway(new Main.State.VirtualStore()));
        assertEquals("ACCEPT", result.planStatus());
        assertFalse(result.finalState().approvalGranted);
        assertFalse(result.finalState().draftWritten);
        assertRunSuspended(result);
    }
}
