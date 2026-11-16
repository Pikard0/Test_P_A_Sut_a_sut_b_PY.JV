# Lab 7 performance test report

Run: `20261027122351168`

## Scenario

`POST /rentals → GET /rentals/{id} → POST /rentals/{id}/return → GET /rentals/{id}`. The `id` comes from the POST response and is used dynamically by the next requests.

## Acceptance criteria

- p95 response time <= 1000.00 ms
- error rate <= 1.00%

## Results

| Profile | VUs | Requests | Average | p95 | Throughput | Error rate | Result |
|---|---:|---:|---:|---:|---:|---:|---|
| baseline | 1 | 40 | 110.45 ms | 212.70 ms | 9.03 rps | 0.00% | PASS |
| load | 5 | 300 | 113.50 ms | 200.97 ms | 41.49 rps | 0.00% | PASS |
| spike | 20 | 400 | 312.06 ms | 553.04 ms | 59.87 rps | 0.00% | PASS |

## Short conclusion

The spike profile used 20 virtual users instead of 1 in baseline. p95 changed from 212.70 ms to 553.04 ms, and throughput changed from 9.03 rps to 59.87 rps. The observed error rate under spike was 0.00%. The threshold result is PASS.
