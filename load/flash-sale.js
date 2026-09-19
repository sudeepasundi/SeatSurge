// SeatSurge flash-sale stampede.
//
// setup():    organizer creates a venue + event (SEATS seats, on sale now).
// default():  every fan (one VU each) registers, waits for a shared start instant, then tries to hold ONE
//             random seat.
//             Expected: 201 (won the seat) or 409 (seat already taken). Anything else is a failure.
// teardown(): verifies the no-oversell invariant from the server's point of view: no seat is held by two
//             fans, and the number of winners equals the held-seat count on the organizer dashboard.
//
// Run (inside the compose network, app started with `docker compose --profile app up -d --build`):
//   docker run --rm -i --network seatsurge_default -v "<repo>/load:/load" -e BASE_URL=http://app:8080 \
//     grafana/k6 run /load/flash-sale.js

import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const FANS = parseInt(__ENV.FANS || '1000', 10);
const SEATS = parseInt(__ENV.SEATS || '200', 10);
const SEATS_PER_ROW = 50;

const holdsWon = new Counter('holds_won');
const holdsLost = new Counter('holds_lost');
const holdsFailed = new Counter('holds_failed');
const holdLatency = new Trend('hold_latency', true);
const wonLatency = new Trend('hold_latency_won', true);
const lostLatency = new Trend('hold_latency_lost', true);

export const options = {
  setupTimeout: '5m',
  teardownTimeout: '5m',
  scenarios: {
    stampede: { executor: 'per-vu-iterations', vus: FANS, iterations: 1, maxDuration: '2m' },
  },
  thresholds: {
    holds_won: [`count<=${SEATS}`], // never more winners than seats
    holds_failed: ['count==0'],     // only 201/409 are acceptable answers
    // Regression guard for a single-laptop run (k6, app, Postgres and Redis share one machine).
    // Override with P95_MS on dedicated hardware.
    hold_latency: [`p(95)<${__ENV.P95_MS || 1500}`],
  },
};

const json = (token) => ({
  headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { Authorization: `Bearer ${token}` } : {}),
});

function register(email, role) {
  const res = http.post(`${BASE}/api/v1/auth/register`,
    JSON.stringify({ email, password: 'load-test-pass', fullName: 'Load Test', role }), json());
  if (res.status !== 201) fail(`register ${email}: ${res.status} ${res.body}`);
  return res.json('accessToken');
}

export function setup() {
  const run = Date.now();
  const organizer = register(`organizer-${run}@load.test`, 'ORGANIZER');

  const venue = http.post(`${BASE}/api/v1/venues`,
    JSON.stringify({ name: 'Load Arena', address: '1 Stress St', city: `LoadCity-${run}` }), json(organizer)).json();
  const rows = [];
  for (let i = 0; i * SEATS_PER_ROW < SEATS; i++) {
    rows.push({ label: `R${i + 1}`, seatCount: Math.min(SEATS_PER_ROW, SEATS - i * SEATS_PER_ROW) });
  }
  const layout = http.post(`${BASE}/api/v1/venues/${venue.id}/sections`,
    JSON.stringify({ name: 'Floor', rows }), json(organizer)).json();

  const now = new Date();
  const event = http.post(`${BASE}/api/v1/events`, JSON.stringify({
    venueId: venue.id, title: 'Load Test Drop', artist: 'The Stampede',
    startsAt: new Date(now.getTime() + 30 * 86400000).toISOString(),
    saleStartsAt: new Date(now.getTime() - 60000).toISOString(),
    maxTicketsPerUser: 4,
    priceTiers: [{ name: 'GA', priceCents: 5000, sectionIds: [layout.sections[0].id] }],
  }), json(organizer)).json();
  http.post(`${BASE}/api/v1/events/${event.id}/publish`, null, json(organizer));

  const seatMap = http.get(`${BASE}/api/v1/events/${event.id}/seats`).json();
  const seatIds = seatMap.sections.flatMap((s) => s.rows.flatMap((r) => r.seats.map((seat) => seat.id)));
  if (seatIds.length !== SEATS) fail(`expected ${SEATS} seats, got ${seatIds.length}`);

  // Keep the setup data small: k6 hands every VU its own copy, so shipping 1000 tokens here would mean
  // ~300 MB of JSON parsing right at the start gun. Each VU registers its own fan account instead.
  return { run, eventId: event.id, seatIds, organizer, startAt: Date.now() + START_DELAY_MS };
}

const START_DELAY_MS = parseInt(__ENV.START_DELAY_MS || '30000', 10);
const lateStarts = new Counter('late_starts');
const fanEmail = (run, i) => `fan-${run}-${i}@load.test`;

export default function (data) {
  // Before the start gun: sign up (one BCrypt hash each, spread over the waiting period).
  const token = register(fanEmail(data.run, exec.vu.idInTest), 'FAN');

  const wait = data.startAt - Date.now();
  if (wait > 0) sleep(wait / 1000);
  else lateStarts.add(1); // registration overran the start instant; the stampede is less synchronized

  const seat = data.seatIds[Math.floor(Math.random() * data.seatIds.length)];
  const res = http.post(`${BASE}/api/v1/events/${data.eventId}/holds`, JSON.stringify({ seatIds: [seat] }),
    Object.assign(json(token), { tags: { name: 'hold' } }));
  holdLatency.add(res.timings.duration);

  if (res.status === 201) {
    holdsWon.add(1);
    wonLatency.add(res.timings.duration);
  } else if (res.status === 409) {
    holdsLost.add(1);
    lostLatency.add(res.timings.duration);
  } else {
    holdsFailed.add(1);
    console.error(`unexpected ${res.status}: ${res.body}`);
  }
  check(res, { '201 or 409': (r) => r.status === 201 || r.status === 409 });
}

const seatsOwned = new Counter('seats_owned_after_run');

export function teardown(data) {
  // Ask the server which seats each fan actually holds (log every fan back in, 50 at a time).
  const owned = [];
  for (let i = 1; i <= FANS; i += 50) {
    const logins = [];
    for (let j = i; j < Math.min(i + 50, FANS + 1); j++) {
      logins.push(['POST', `${BASE}/api/v1/auth/login`,
        JSON.stringify({ email: fanEmail(data.run, j), password: 'load-test-pass' }), json()]);
    }
    const tokens = http.batch(logins).filter((r) => r.status === 200).map((r) => r.json('accessToken'));
    http.batch(tokens.map((t) => ['GET', `${BASE}/api/v1/holds`, null, json(t)]))
      .forEach((res) => res.json().forEach((hold) => hold.seats.forEach((s) => owned.push(s.eventSeatId))));
  }
  const distinct = new Set(owned).size;
  const stats = http.get(`${BASE}/api/v1/events/${data.eventId}/stats`, json(data.organizer)).json();
  seatsOwned.add(owned.length);

  console.log(`INVARIANT seats owned by fans=${owned.length} distinct=${distinct} ` +
    `dashboard heldSeats=${stats.heldSeats} capacity=${stats.totalSeats}`);
  check(null, {
    'no seat is held by two fans': () => owned.length === distinct,
    'winners match seat inventory': () => owned.length === stats.heldSeats,
    'never above capacity': () => stats.heldSeats <= stats.totalSeats,
  });
  if (owned.length !== distinct || owned.length !== stats.heldSeats) fail('OVERSELL DETECTED');
}

export function handleSummary(data) {
  const m = data.metrics;
  const lat = m.hold_latency.values;
  const count = (name) => (m[name] ? m[name].values.count : 0);
  const pct = (metric) => (metric ? metric.values['p(95)'].toFixed(1) : 'n/a');
  const lines = [
    '',
    '================ SeatSurge flash-sale stampede ================',
    `fans: ${FANS}   seats: ${SEATS}`,
    `holds won: ${count('holds_won')}   lost (409): ${count('holds_lost')}   failed: ${count('holds_failed')}`,
    `hold latency  avg ${lat.avg.toFixed(1)} ms   p50 ${lat.med.toFixed(1)} ms   p90 ${lat['p(90)'].toFixed(1)} ms   ` +
      `p95 ${lat['p(95)'].toFixed(1)} ms   max ${lat.max.toFixed(1)} ms`,
    `  winners (201) p95 ${pct(m.hold_latency_won)} ms   losers (409) p95 ${pct(m.hold_latency_lost)} ms`,
    `201 responses: ${count('holds_won')}   seats actually owned after the run: ${count('seats_owned_after_run')}` +
      `   => ${count('holds_won') === count('seats_owned_after_run') ? 'ZERO OVERSELLS' : 'MISMATCH!'}`,
    `late starters (missed the start gun): ${count('late_starts')}`,
    `invariant checks passed: ${m.checks ? (m.checks.values.rate * 100).toFixed(1) : 'n/a'}%`,
    '===============================================================',
    '',
  ];
  return {
    stdout: lines.join('\n'),
    '/load/results/summary.json': JSON.stringify(data, null, 2),
  };
}
