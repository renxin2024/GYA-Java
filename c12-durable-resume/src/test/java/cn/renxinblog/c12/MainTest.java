package cn.renxinblog.c12;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;

class MainTest {
  @Test
  void recoveryAndReplayAreSafe() throws Exception {
    Path root = Files.createTempDirectory("c12-java");
    Path db = root.resolve("runs.db");
    Path out = root.resolve("draft.txt");
    try (Main.Store first = new Main.Store(db, out)) {
      first.start("r1");
      // 跨语言契约：两端对同一个 action+args+output 组合算出相同 hash（固定 output 作锚点）
      assertEquals("2bba1bb36ae552812b4b9e0d730249a3447adfc3ed100e0f4d92e3a8c28e7573",
          Main.digest(Main.ACTION, Main.CONTENT, "/tmp/c12-draft.txt"));
      first.decide("r1", "approve", first.hash("r1"));
    }
    try (Main.Store second = new Main.Store(db, out)) {
      assertEquals("executed", second.resume("r1"));
      assertEquals("already_completed", second.resume("r1"));
      assertEquals(Main.CONTENT, Files.readString(out));
      assertEquals(1, second.count("r1", "action_executed"));
      assertEquals(1, second.count("r1", "action_replayed"));
    }
  }

  @Test
  void crashAfterFileCreationIsRecoveredWithoutRewrite() throws Exception {
    Path root = Files.createTempDirectory("c12-java-crash");
    Path db = root.resolve("runs.db");
    Path out = root.resolve("draft.txt");
    try (Main.Store first = new Main.Store(db, out)) {
      first.start("r1");
      first.decide("r1", "approve", first.hash("r1"));
      assertThrows(IllegalStateException.class, () -> first.resume("r1", true));
    }
    FileTime before = Files.getLastModifiedTime(out);
    try (Main.Store second = new Main.Store(db, out)) {
      assertEquals("recovered", second.resume("r1"));
      assertEquals(before, Files.getLastModifiedTime(out));
      assertEquals(1, second.count("r1", "action_recovered"));
    }
  }

  @Test
  void unapprovedResumeCannotWrite() throws Exception {
    Path root = Files.createTempDirectory("c12-java-unapproved");
    Path out = root.resolve("draft.txt");
    try (Main.Store store = new Main.Store(root.resolve("runs.db"), out)) {
      store.start("r1");
      assertThrows(IllegalArgumentException.class, () -> store.resume("r1"));
      assertFalse(Files.exists(out));
    }
  }

  @Test
  void mismatchedApprovalHashIsRejected() throws Exception {
    Path root = Files.createTempDirectory("c12-java-hash");
    Path out = root.resolve("draft.txt");
    try (Main.Store store = new Main.Store(root.resolve("runs.db"), out)) {
      store.start("r1");
      assertThrows(IllegalArgumentException.class, () -> store.decide("r1", "approve", "bad"));
      assertFalse(Files.exists(out));
    }
  }

  @Test
  void postApprovalStorageTamperingIsRejected() throws Exception {
    Path root = Files.createTempDirectory("c12-java-tamper");
    Path out = root.resolve("draft.txt");
    try (Main.Store store = new Main.Store(root.resolve("runs.db"), out)) {
      store.start("r1");
      store.decide("r1", "approve", store.hash("r1"));
      String tampered = "tampered after approval";
      try (PreparedStatement p = store.c.prepareStatement("UPDATE runs SET args=?,proposal_hash=? WHERE run_id=?")) {
        p.setString(1, tampered);
        p.setString(2, Main.digest(Main.ACTION, tampered, out.toString()));
        p.setString(3, "r1");
        p.executeUpdate();
      }
      assertThrows(IllegalArgumentException.class, () -> store.resume("r1"));
      assertFalse(Files.exists(out));
    }
  }

  @Test
  void postApprovalRequestReplacementIsRejected() throws Exception {
    Path root = Files.createTempDirectory("c12-java-request");
    Path out = root.resolve("draft.txt");
    try (Main.Store store = new Main.Store(root.resolve("runs.db"), out)) {
      store.start("r1");
      store.decide("r1", "approve", store.hash("r1"));
      try (PreparedStatement p = store.c.prepareStatement("UPDATE runs SET request_id=? WHERE run_id=?")) {
        p.setString(1, "approval:replacement");
        p.setString(2, "r1");
        p.executeUpdate();
      }
      assertThrows(IllegalArgumentException.class, () -> store.resume("r1"));
      assertFalse(Files.exists(out));
    }
  }

  @Test
  void postApprovalOutputSwapIsRejected() throws Exception {
    Path root = Files.createTempDirectory("c12-java-outputswap");
    Path out = root.resolve("draft.txt");
    Path other = root.resolve("other.txt");
    try (Main.Store first = new Main.Store(root.resolve("runs.db"), out)) {
      first.start("r1");
      first.decide("r1", "approve", first.hash("r1"));
    }
    try (Main.Store second = new Main.Store(root.resolve("runs.db"), other)) {
      assertThrows(IllegalArgumentException.class, () -> second.resume("r1"));
      assertFalse(Files.exists(other));
      assertFalse(Files.exists(out));
    }
  }

  @Test
  void existingDifferentOutputIsNotOverwritten() throws Exception {
    Path root = Files.createTempDirectory("c12-java-conflict");
    Path out = root.resolve("draft.txt");
    try (Main.Store store = new Main.Store(root.resolve("runs.db"), out)) {
      store.start("r1");
      store.decide("r1", "approve", store.hash("r1"));
      Files.writeString(out, "belongs to another operation");
      assertThrows(IllegalArgumentException.class, () -> store.resume("r1"));
      assertEquals("belongs to another operation", Files.readString(out));
    }
  }

  @Test
  void rejectedRunCannotBeApprovedOrResumed() throws Exception {
    Path root = Files.createTempDirectory("c12-java-reject");
    try (Main.Store store = new Main.Store(root.resolve("runs.db"), root.resolve("draft.txt"))) {
      store.start("r1");
      store.decide("r1", "reject", store.hash("r1"));
      assertThrows(IllegalArgumentException.class, () -> store.decide("r1", "approve", store.hash("r1")));
      assertThrows(IllegalArgumentException.class, () -> store.resume("r1"));
    }
  }

  @Test
  void showTraceContainsPendingRequestAndDetails() throws Exception {
    Path root = Files.createTempDirectory("c12-java-trace");
    try (Main.Store store = new Main.Store(root.resolve("runs.db"), root.resolve("draft.txt"))) {
      store.start("r1");
      String json = store.showJson("r1");
      assertTrue(json.contains("\"request_id\":\"approval:r1\""));
      assertTrue(json.contains("\"detail\":{"));
    }
  }
}
