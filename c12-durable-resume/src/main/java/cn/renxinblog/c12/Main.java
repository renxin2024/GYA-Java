package cn.renxinblog.c12;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;

/** C12 的 Java 21 对照实现：checkpoint、审批绑定和可恢复的本地幂等写入。 */
public final class Main {
  static final String ACTION = "write_draft";
  static final String CONTENT = "approved technical brief";

  static String jsonString(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  static String canonical(String action, String content, String output) {
    // 提案的规范化表示：action + args + output 三者一起决定「这个动作要做什么、写到哪」。
    // output 必须纳入：否则批准「写 A 文件」后，恢复时换成「写 B 文件」也能通过审批校验。
    return "{\"action\":" + jsonString(action) + ",\"args\":{\"content\":" + jsonString(content)
        + "},\"output\":" + jsonString(output) + "}";
  }

  static String digest(String action, String content, String output) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical(action, content, output).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  record RunRow(String runId, String status, String requestId, String action, String content,
                String output, String proposalHash, String approval, String approvalRequestId, String approvalHash,
                boolean executed) {}

  static final class Store implements AutoCloseable {
    final Connection c;
    final Path output;

    Store(Path db, Path output) throws Exception {
      this.output = output;
      Class.forName("org.sqlite.JDBC");
      c = DriverManager.getConnection("jdbc:sqlite:" + db);
      try (Statement s = c.createStatement()) {
        s.executeUpdate("CREATE TABLE IF NOT EXISTS runs("
            + "run_id TEXT PRIMARY KEY,status TEXT NOT NULL,request_id TEXT NOT NULL,"
            + "action TEXT NOT NULL,args TEXT NOT NULL,output TEXT NOT NULL,proposal_hash TEXT NOT NULL,"
            + "approval TEXT,approval_request_id TEXT,approval_hash TEXT,executed INTEGER NOT NULL DEFAULT 0)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS trace("
            + "seq INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT NOT NULL,event TEXT NOT NULL,detail TEXT NOT NULL)");
      }
    }

    void trace(String run, String event, String detail) throws SQLException {
      try (PreparedStatement p = c.prepareStatement("INSERT INTO trace(run_id,event,detail) VALUES(?,?,?)")) {
        p.setString(1, run);
        p.setString(2, event);
        p.setString(3, detail);
        p.executeUpdate();
      }
    }

    void start(String run) throws Exception {
      String proposalHash = digest(ACTION, CONTENT, output.toString());
      try (PreparedStatement p = c.prepareStatement(
          "INSERT INTO runs(run_id,status,request_id,action,args,output,proposal_hash) VALUES(?,?,?,?,?,?,?)")) {
        p.setString(1, run);
        p.setString(2, "WAITING_FOR_USER");
        p.setString(3, "approval:" + run);
        p.setString(4, ACTION);
        p.setString(5, CONTENT);
        p.setString(6, output.toString());
        p.setString(7, proposalHash);
        p.executeUpdate();
      }
      trace(run, "checkpoint_saved", "{\"status\":\"WAITING_FOR_USER\"}");
      trace(run, "proposal_recorded", "{\"proposal_hash\":" + jsonString(proposalHash) + "}");
    }

    RunRow row(String run) throws Exception {
      try (PreparedStatement p = c.prepareStatement(
          "SELECT run_id,status,request_id,action,args,output,proposal_hash,approval,approval_request_id,approval_hash,executed FROM runs WHERE run_id=?")) {
        p.setString(1, run);
        try (ResultSet r = p.executeQuery()) {
          if (!r.next()) throw new IllegalArgumentException("unknown_run:" + run);
          return new RunRow(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
              r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getInt(11) != 0);
        }
      }
    }

    String hash(String run) throws Exception {
      return row(run).proposalHash();
    }

    String status(String run) throws Exception {
      return row(run).status();
    }

    void decide(String run, String decision, String suppliedHash) throws Exception {
      RunRow current = row(run);
      if (!current.status().equals("WAITING_FOR_USER") || current.approval() != null) {
        trace(run, "transition_rejected", "{\"reason\":\"approval_not_pending\",\"current_status\":" + jsonString(current.status()) + ",\"existing_approval\":" + (current.approval() == null ? "null" : jsonString(current.approval())) + ",\"attempted_decision\":" + jsonString(decision) + ",\"supplied_hash\":" + jsonString(suppliedHash) + "}");
        throw new IllegalArgumentException("approval_not_pending");
      }
      String currentHash = digest(current.action(), current.content(), current.output());
      if (!currentHash.equals(current.proposalHash()) || !currentHash.equals(suppliedHash)) {
        trace(run, "proposal_rejected", "{\"phase\":\"approval\",\"reason\":\"proposal_hash_mismatch\",\"supplied_hash\":" + jsonString(suppliedHash) + ",\"current_hash\":" + jsonString(currentHash) + ",\"stored_hash\":" + jsonString(current.proposalHash()) + ",\"action\":" + jsonString(current.action()) + ",\"args\":" + jsonString(current.content().length() > 200 ? current.content().substring(0, 200) : current.content()) + ",\"output\":" + jsonString(current.output()) + "}");
        throw new IllegalArgumentException("proposal_hash_mismatch");
      }
      String next = decision.equals("approve") ? "APPROVED" : decision.equals("reject") ? "REJECTED" : null;
      if (next == null) throw new IllegalArgumentException("invalid_decision");
      try (PreparedStatement p = c.prepareStatement(
          "UPDATE runs SET status=?,approval=?,approval_request_id=?,approval_hash=? WHERE run_id=?")) {
        p.setString(1, next);
        p.setString(2, decision);
        p.setString(3, current.requestId());
        p.setString(4, currentHash);
        p.setString(5, run);
        p.executeUpdate();
      }
      trace(run, "proposal_verified", "{\"phase\":\"approval\",\"proposal_hash\":" + jsonString(currentHash) + "}");
      trace(run, "approval_recorded", "{\"request_id\":" + jsonString(current.requestId())
          + ",\"decision\":" + jsonString(decision) + "}");
    }

    String resume(String run) throws Exception {
      return resume(run, false);
    }

    String resume(String run, boolean failAfterAction) throws Exception {
      RunRow current = row(run);
      if (current.status().equals("REJECTED")) {
        trace(run, "resume_rejected", "{\"reason\":\"approval_rejected\",\"status\":\"REJECTED\"}");
        throw new IllegalArgumentException("approval_rejected");
      }
      if (current.status().equals("WAITING_FOR_USER")) {
        trace(run, "resume_rejected", "{\"reason\":\"approval_required\",\"status\":\"WAITING_FOR_USER\"}");
        throw new IllegalArgumentException("approval_required");
      }
      if (current.status().equals("COMPLETED") || current.executed()) {
        trace(run, "action_replayed", "{\"reason\":\"already_completed\"}");
        return "already_completed";
      }
      if (!current.status().equals("APPROVED")) throw new IllegalArgumentException("cannot_resume:" + current.status());

      // 副作用目标（output）是 proposal 的不可变组成部分：恢复时传入的路径必须与批准时冻结的一致，否则视为篡改。
      if (!output.toString().equals(current.output())) {
        trace(run, "proposal_rejected", "{\"phase\":\"resume\",\"reason\":\"output_mismatch\",\"current_output\":" + jsonString(output.toString()) + ",\"approved_output\":" + jsonString(current.output()) + ",\"action\":" + jsonString(current.action()) + "}");
        throw new IllegalArgumentException("output_mismatch");
      }
      String currentHash = digest(current.action(), current.content(), current.output());
      if (!current.requestId().equals(current.approvalRequestId())) {
        trace(run, "proposal_rejected", "{\"phase\":\"resume\",\"reason\":\"approval_request_mismatch\",\"current_request_id\":" + jsonString(current.requestId()) + ",\"approved_request_id\":" + (current.approvalRequestId() == null ? "null" : jsonString(current.approvalRequestId())) + ",\"action\":" + jsonString(current.action()) + ",\"args\":" + jsonString(current.content().length() > 200 ? current.content().substring(0, 200) : current.content()) + "}");
        throw new IllegalArgumentException("approval_request_mismatch");
      }
      if (!currentHash.equals(current.proposalHash()) || !currentHash.equals(current.approvalHash())) {
        trace(run, "proposal_rejected", "{\"phase\":\"resume\",\"reason\":\"proposal_hash_mismatch\",\"current_hash\":" + jsonString(currentHash) + ",\"stored_hash\":" + jsonString(current.proposalHash()) + ",\"approval_hash\":" + (current.approvalHash() == null ? "null" : jsonString(current.approvalHash())) + ",\"action\":" + jsonString(current.action()) + ",\"args\":" + jsonString(current.content().length() > 200 ? current.content().substring(0, 200) : current.content()) + ",\"output\":" + jsonString(current.output()) + "}");
        throw new IllegalArgumentException("proposal_hash_mismatch");
      }
      trace(run, "proposal_verified", "{\"phase\":\"resume\",\"proposal_hash\":" + jsonString(currentHash) + "}");
      trace(run, "resume_started", "{}");

      if (output.getParent() != null) Files.createDirectories(output.getParent());
      boolean recovered = false;
      try {
        Files.writeString(output, current.content(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      } catch (FileAlreadyExistsException e) {
        // 文件已存在：不是普通失败，而是「动作副作用可能已落盘、但 checkpoint 还没写回」。
        // 内容相同 → 上次已写成功（recovered，只补 checkpoint）；不同 → 幂等键被别的来源写了别的数据（冲突，不覆盖）。
        if (!Files.readString(output).equals(current.content())) {
          String existingHash;
          String expectedContentHash;
          String existingContent;
          try {
            existingHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output)));
            expectedContentHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(current.content().getBytes(StandardCharsets.UTF_8)));
            existingContent = Files.readString(output);
          } catch (Exception ex) { existingHash = "unable_to_compute"; expectedContentHash = "unable_to_compute"; existingContent = ""; }
          trace(run, "action_conflict", "{\"reason\":\"idempotency_key_reused_with_different_content\",\"expected_content_hash\":" + jsonString(expectedContentHash) + ",\"existing_content_hash\":" + jsonString(existingHash) + ",\"expected_content\":" + jsonString(current.content().length() > 200 ? current.content().substring(0, 200) : current.content()) + ",\"existing_content\":" + jsonString(existingContent.length() > 200 ? existingContent.substring(0, 200) : existingContent) + "}");
          throw new IllegalArgumentException("output_conflict");
        }
        recovered = true;
      }
      if (failAfterAction) {
        trace(run, "crash_injected", "{\"phase\":\"after_action_before_checkpoint\"}");
        throw new IllegalStateException("simulated_crash_after_action");
      }
      try (PreparedStatement p = c.prepareStatement("UPDATE runs SET status='COMPLETED',executed=1 WHERE run_id=?")) {
        p.setString(1, run);
        p.executeUpdate();
      }
      String event = recovered ? "action_recovered" : "action_executed";
      trace(run, event, "{\"action\":" + jsonString(current.action()) + "}");
      return recovered ? "recovered" : "executed";
    }

    long count(String run, String event) throws Exception {
      try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM trace WHERE run_id=? AND event=?")) {
        p.setString(1, run);
        p.setString(2, event);
        try (ResultSet r = p.executeQuery()) {
          r.next();
          return r.getLong(1);
        }
      }
    }

    String showJson(String run) throws Exception {
      RunRow current = row(run);
      StringBuilder traceJson = new StringBuilder("[");
      try (PreparedStatement p = c.prepareStatement("SELECT event,detail FROM trace WHERE run_id=? ORDER BY seq")) {
        p.setString(1, run);
        try (ResultSet r = p.executeQuery()) {
          boolean first = true;
          while (r.next()) {
            if (!first) traceJson.append(',');
            first = false;
            traceJson.append("{\"event\":").append(jsonString(r.getString(1)))
                .append(",\"detail\":").append(r.getString(2)).append('}');
          }
        }
      }
      traceJson.append(']');
      return "{\"run_id\":" + jsonString(current.runId())
          + ",\"status\":" + jsonString(current.status())
          + ",\"request_id\":" + jsonString(current.requestId())
          + ",\"action\":" + jsonString(current.action())
          + ",\"args\":{\"content\":" + jsonString(current.content()) + "}"
          + ",\"output\":" + jsonString(current.output())
          + ",\"proposal_hash\":" + jsonString(current.proposalHash())
          + ",\"approval_request_id\":" + (current.approvalRequestId() == null ? "null" : jsonString(current.approvalRequestId()))
          + ",\"approval_hash\":" + (current.approvalHash() == null ? "null" : jsonString(current.approvalHash()))
          + ",\"executed\":" + current.executed()
          + ",\"trace\":" + traceJson + "}";
    }

    @Override
    public void close() throws SQLException {
      c.close();
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 4) {
      throw new IllegalArgumentException("usage: <db> <output> <start|show-trace|approve|reject|resume> <run> [hash]");
    }
    try (Store store = new Store(Path.of(args[0]), Path.of(args[1]))) {
      String command = args[2];
      String run = args[3];
      switch (command) {
        case "start" -> store.start(run);
        case "show-trace" -> { }
        case "approve", "reject" -> {
          if (args.length < 5) throw new IllegalArgumentException("proposal_hash_required");
          store.decide(run, command, args[4]);
        }
        case "resume" -> store.resume(run);
        default -> throw new IllegalArgumentException("unknown_command");
      }
      System.out.println(store.showJson(run));
    }
  }
}
