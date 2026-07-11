// Balance-read profile: GET /api/v1/merchants/{id}/balance at a constant
// arrival rate. Run against a ledger pre-grown by perf/balance-bench.sh so the
// read crosses millions of rows for the seeded merchant.
//
// Run: docker run --rm --network=host -v "$PWD/perf/k6:/scripts" grafana/k6 \
//        run /scripts/balance.js
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const KEY = __ENV.API_KEY || "test-api-key-123";
const MID = __ENV.MERCHANT_ID || "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11";

export const options = {
  scenarios: {
    balance: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RATE || 50), // requests per second
      timeUnit: "1s",
      duration: __ENV.DURATION || "60s",
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export default function () {
  const res = http.get(`${BASE}/api/v1/merchants/${MID}/balance`, {
    headers: { "X-Api-Key": KEY },
  });
  check(res, { "status 200": (r) => r.status === 200 });
}
