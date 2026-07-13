import assert from "node:assert/strict";
import test from "node:test";

import { AGENT_TOOLS, normalizeToolCalls, TOOL_NAMES } from "../src/tool-contract.mjs";

test("exposes the complete constrained Elysium tool surface", () => {
  assert.deepEqual(TOOL_NAMES, [
    "inspect_processes",
    "read_terminal",
    "create_build",
    "install_package",
    "apply_patch",
    "start_service",
    "stop_service",
    "create_snapshot",
    "rollback_snapshot",
    "publish_port",
    "mount_workspace",
    "verify_artifact"
  ]);
  assert.equal(AGENT_TOOLS.filter((tool) => tool.requiresApproval).length, 9);
  assert.ok(AGENT_TOOLS.every((tool) => tool.strict && tool.parameters.additionalProperties === false));
});

test("marks only mutations as requiring approval", () => {
  const normalized = normalizeToolCalls([
    { type: "function_call", call_id: "call_read", name: "read_terminal", arguments: '{"session_id":"session-1"}' },
    { type: "function_call", call_id: "call_build", name: "create_build", arguments: '{"workspace_id":"default","target":"apk"}' }
  ]);

  assert.deepEqual(normalized.map(({ name, requiresApproval }) => ({ name, requiresApproval })), [
    { name: "read_terminal", requiresApproval: false },
    { name: "create_build", requiresApproval: true }
  ]);
});

test("rejects tool calls outside the approved contract", () => {
  assert.throws(
    () => normalizeToolCalls([{ type: "function_call", call_id: "call_1", name: "run_shell", arguments: "{}" }]),
    /unknown tool/
  );
});
