// Runs the compiled Nakama module (build/index.js) against a fake Nakama runtime.
// Checks input validation, rate limiting and the PostGIS (lon, lat) parameter order.
// Run: npm run build && npm test
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const CODES = { INVALID_ARGUMENT: 3, UNAUTHENTICATED: 16, RESOURCE_EXHAUSTED: 8 };
const USER = "11111111-1111-1111-1111-111111111111";
const NODE_ID = "22222222-2222-2222-2222-222222222222";

/** Loads the module into a fresh sandbox and returns its RPCs plus a recorder of SQL calls. */
function load({ recentCount = 0 } = {}) {
  const code = fs.readFileSync(path.join(__dirname, "..", "build", "index.js"), "utf8");
  const sandbox = { nkruntime: { Codes: CODES } };
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox);

  const calls = [];
  const nk = {
    sqlExec: (q, p) => { calls.push({ kind: "exec", q, p }); return { rowsAffected: 1 }; },
    sqlQuery: (q, p) => {
      calls.push({ kind: "query", q, p });
      if (q.includes("count(*)")) return [{ n: recentCount }];
      return [{ id: "a", lat: 47.8, lon: 10.9, content_type: "TEXT", payload: "hi",
        owner_id: "u", content_status: "LIVE", created_at_ms: 1700000000000.4 }];
    },
  };
  const logger = { info() {}, warn() {}, error() {} };
  const rpcs = {};
  sandbox.InitModule({}, logger, nk, { registerRpc: (name, fn) => { rpcs[name] = fn; } });
  calls.length = 0; // drop the schema statements
  const call = (name, body, ctx = { userId: USER }) => JSON.parse(rpcs[name](ctx, logger, nk, JSON.stringify(body)));
  const fail = (name, raw, ctx = { userId: USER }) => {
    try { rpcs[name](ctx, logger, nk, raw); } catch (e) { return e.code; }
    return null;
  };
  return { rpcs, calls, call, fail };
}

const valid = (over = {}) => JSON.stringify({ id: NODE_ID, lat: 47.81, lon: 10.89, content_type: "TEXT", payload: "Hallo", ...over });

test("registers exactly the expected RPCs", () => {
  assert.deepEqual(Object.keys(load().rpcs).sort(), ["node_create", "nodes_nearby"]);
});

test("node_create stores lon before lat, trims text, returns owner and status", () => {
  const m = load();
  const r = m.call("node_create", { id: NODE_ID, lat: 47.81, lon: 10.89, content_type: "TEXT", payload: "  Fuer Anja  " });
  assert.equal(r.id, NODE_ID);
  assert.equal(r.owner_id, USER);
  assert.equal(r.content_status, "LIVE");
  const insert = m.calls.find((c) => c.kind === "exec");
  assert.equal(insert.p[1], 10.89, "longitude first");
  assert.equal(insert.p[2], 47.81, "latitude second");
  assert.equal(JSON.parse(insert.p[4]).text, "Fuer Anja");
});

test("node_create rejects invalid input", () => {
  const m = load();
  assert.equal(m.fail("node_create", "not json"), CODES.INVALID_ARGUMENT);
  assert.equal(m.fail("node_create", valid({ id: "x" })), CODES.INVALID_ARGUMENT);
  assert.equal(m.fail("node_create", valid({ lat: 91 })), CODES.INVALID_ARGUMENT);
  assert.equal(m.fail("node_create", valid({ lon: -181 })), CODES.INVALID_ARGUMENT);
  assert.equal(m.fail("node_create", valid({ content_type: "VIDEO" })), CODES.INVALID_ARGUMENT);
  assert.equal(m.fail("node_create", valid({ payload: "   " })), CODES.INVALID_ARGUMENT);
  assert.equal(m.fail("node_create", valid({ payload: "x".repeat(501) })), CODES.INVALID_ARGUMENT);
});

test("node_create requires a logged-in user", () => {
  assert.equal(load().fail("node_create", valid(), {}), CODES.UNAUTHENTICATED);
});

test("node_create enforces the hourly rate limit", () => {
  assert.equal(load({ recentCount: 30 }).fail("node_create", valid()), CODES.RESOURCE_EXHAUSTED);
});

test("nodes_nearby clamps the radius, passes lon first and maps rows", () => {
  const m = load();
  const r = m.call("nodes_nearby", { lat: 47.8, lon: 10.9, radius_m: 99999 });
  assert.equal(r.nodes.length, 1);
  assert.equal(r.nodes[0].created_at_ms, 1700000000000);
  assert.equal(m.calls[0].p[0], 10.9, "longitude first");
  assert.equal(m.calls[0].p[2], 500, "radius clamped to the configured maximum");
});
