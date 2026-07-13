const strictObject = (properties, required = []) => ({
  type: "object",
  additionalProperties: false,
  properties,
  required
});

const workspace = { type: "string", minLength: 1, maxLength: 160 };
const session = { type: "string", minLength: 1, maxLength: 160 };

const catalog = [
  ["inspect_processes", "Inspect the allow-listed runtime process table without exposing secrets.", false,
    strictObject({ workspace_id: workspace, include_command_lines: { type: "boolean" } }, ["workspace_id"])],
  ["read_terminal", "Read a bounded tail from an approved Elysium terminal session.", false,
    strictObject({ session_id: session, max_lines: { type: "integer", minimum: 1, maximum: 500 } }, ["session_id"])],
  ["create_build", "Create a build for an approved workspace target.", true,
    strictObject({ workspace_id: workspace, target: { type: "string", enum: ["android", "make", "cmake"] }, variant: { type: "string", enum: ["debug", "release", "default"] } }, ["workspace_id", "target"])],
  ["install_package", "Install a named package into an approved Linux workspace.", true,
    strictObject({ workspace_id: workspace, package_name: { type: "string", maxLength: 160 }, manager: { type: "string", enum: ["apt", "apk", "pacman"] } }, ["workspace_id", "package_name", "manager"])],
  ["apply_patch", "Apply a unified patch inside an approved workspace after a diff preview.", true,
    strictObject({ workspace_id: workspace, patch: { type: "string", minLength: 1, maxLength: 100_000 } }, ["workspace_id", "patch"])],
  ["start_service", "Start an allow-listed service inside the selected workspace.", true,
    strictObject({ workspace_id: workspace, service: { type: "string", maxLength: 120 } }, ["workspace_id", "service"])],
  ["stop_service", "Stop an allow-listed service inside the selected workspace.", true,
    strictObject({ workspace_id: workspace, service: { type: "string", maxLength: 120 } }, ["workspace_id", "service"])],
  ["create_snapshot", "Create a named recoverable workspace snapshot.", true,
    strictObject({ workspace_id: workspace, label: { type: "string", maxLength: 120 } }, ["workspace_id", "label"])],
  ["rollback_snapshot", "Restore a named workspace snapshot after explicit confirmation.", true,
    strictObject({ workspace_id: workspace, snapshot_id: { type: "string", maxLength: 160 } }, ["workspace_id", "snapshot_id"])],
  ["publish_port", "Publish an allow-listed local service port with an expiry.", true,
    strictObject({ workspace_id: workspace, port: { type: "integer", minimum: 1, maximum: 65535 }, ttl_minutes: { type: "integer", minimum: 1, maximum: 120 } }, ["workspace_id", "port", "ttl_minutes"])],
  ["mount_workspace", "Register an existing user-storage workspace for newly created Linux runtime sessions.", true,
    strictObject({ workspace_id: workspace, mount_path: { type: "string", maxLength: 240 }, read_only: { type: "boolean" } }, ["workspace_id", "mount_path"])],
  ["verify_artifact", "Verify an artifact by checksum, signature metadata, and bounded diagnostics.", false,
    strictObject({ workspace_id: workspace, artifact_path: { type: "string", maxLength: 512 } }, ["workspace_id", "artifact_path"])]
];

export const AGENT_TOOLS = Object.freeze(catalog.map(([name, description, requiresApproval, parameters]) => Object.freeze({
  type: "function",
  name,
  description,
  strict: true,
  parameters,
  requiresApproval
})));

const policyByName = new Map(AGENT_TOOLS.map((tool) => [tool.name, tool]));

export function toOpenAiTools() {
  return AGENT_TOOLS.map(({ requiresApproval, ...tool }) => tool);
}

export function normalizeToolCalls(output = []) {
  return output
    .filter((item) => item?.type === "function_call")
    .map((item) => {
      const tool = policyByName.get(item.name);
      if (!tool) throw new Error(`model requested an unknown tool: ${item.name}`);
      return {
        callId: item.call_id,
        name: item.name,
        argumentsJson: item.arguments,
        requiresApproval: tool.requiresApproval
      };
    });
}

export const TOOL_NAMES = Object.freeze(AGENT_TOOLS.map((tool) => tool.name));
