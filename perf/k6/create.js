// Payment-create profile: POST /api/v1/payments at a constant arrival rate.
// Measures the synchronous write path: bean validation, idempotency
// fingerprint, transaction insert, outbox intent — all in one commit.
// Provider dispatch happens later on the outbox poll and is NOT in this
// latency; flow.js measures that lag end to end.
//
// Run: docker run --rm --network=host -v "$PWD/perf/k6:/scripts" grafana/k6 \
//        run /scripts/create.js
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const KEY = __ENV.API_KEY || "test-api-key-123";
const MID = __ENV.MERCHANT_ID || "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11";

export const options = {
  scenarios: {
    create: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RATE || 25),
      timeUnit: "1s",
      duration: __ENV.DURATION || "60s",
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export default function () {
  const res = http.post(
    `${BASE}/api/v1/payments`,
    JSON.stringify({
      merchantId: MID,
      amount: 10000,
      currency: "EUR",
      description: `k6 create ${__VU}-${__ITER}`,
      paymentMethod: '{"type": "card"}',
    }),
    {
      headers: {
        "X-Api-Key": KEY,
        "Content-Type": "application/json",
        // unique per iteration: every request is a genuinely new payment
        "Idempotency-Key": `k6-create-${__VU}-${__ITER}-${Date.now()}`,
      },
    }
  );
  check(res, { "status 201": (r) => r.status === 201 });
}
