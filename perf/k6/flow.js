// End-to-end payment flow: create -> poll until the async authorization
// settles (outbox poll + provider simulator) -> capture. Two custom trends:
//
//   auth_lag_ms   create 201 -> status AUTHORIZED|FAILED observed via GET.
//                 Dominated by the outbox dispatch interval (2s poll) plus the
//                 simulated provider (~500ms); this IS the outbox throughput
//                 story, measured from the client's side.
//   capture_ms    the synchronous capture call: state transition + double-entry
//                 posting group + deferred balance trigger at commit.
//
// The simulator authorizes ~90% and fails ~10% by design; failures are counted,
// not treated as errors.
//
// Run: docker run --rm --network=host -v "$PWD/perf/k6:/scripts" grafana/k6 \
//        run /scripts/flow.js
import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Counter } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const KEY = __ENV.API_KEY || "test-api-key-123";
const MID = __ENV.MERCHANT_ID || "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11";

const authLag = new Trend("auth_lag_ms");
const captureMs = new Trend("capture_ms");
const authorized = new Counter("payments_authorized");
const failed = new Counter("payments_failed");
const stuck = new Counter("payments_stuck");

export const options = {
  scenarios: {
    flow: {
      executor: "constant-vus",
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || "120s",
    },
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

const HEADERS = { "X-Api-Key": KEY, "Content-Type": "application/json" };

export default function () {
  const created = http.post(
    `${BASE}/api/v1/payments`,
    JSON.stringify({
      merchantId: MID,
      amount: 10000,
      currency: "EUR",
      description: `k6 flow ${__VU}-${__ITER}`,
      paymentMethod: '{"type": "card"}',
    }),
    {
      headers: {
        ...HEADERS,
        "Idempotency-Key": `k6-flow-${__VU}-${__ITER}-${Date.now()}`,
      },
    }
  );
  if (!check(created, { "created 201": (r) => r.status === 201 })) return;

  const id = created.json("id");
  const t0 = Date.now();
  let status = created.json("status");

  // Poll until the async authorization settles. 15s deadline: outbox poll is
  // 2s and the simulator ~500ms, so anything near the deadline is a stall.
  while (status !== "AUTHORIZED" && status !== "FAILED") {
    if (Date.now() - t0 > 15000) {
      stuck.add(1);
      return;
    }
    sleep(0.25);
    const got = http.get(`${BASE}/api/v1/payments/${id}`, { headers: HEADERS });
    if (got.status !== 200) continue;
    status = got.json("status");
  }
  authLag.add(Date.now() - t0);

  if (status === "FAILED") {
    failed.add(1);
    return;
  }
  authorized.add(1);

  const cap = http.post(`${BASE}/api/v1/payments/${id}/capture`, null, {
    headers: {
      ...HEADERS,
      "Idempotency-Key": `k6-cap-${__VU}-${__ITER}-${Date.now()}`,
    },
  });
  check(cap, { "captured 200": (r) => r.status === 200 });
  captureMs.add(cap.timings.duration);
}
