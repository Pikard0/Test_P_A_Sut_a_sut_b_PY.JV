# Lab 7 performance test report

Run: `20261027020343686`

## Scenario

`POST /rentals → GET /rentals/{id} → POST /rentals/{id}/return → GET /rentals/{id}`. The `id` comes from the POST response and is used dynamically by the next requests.

## Acceptance criteria

- p95 response time <= 1000.00 ms
- error rate <= 1.00%

## Results

| Profile | VUs | Requests | Average | p95 | Throughput | Error rate | Result |
|---|---:|---:|---:|---:|---:|---:|---|
| baseline | 1 | 40 | 93.08 ms | 188.07 ms | 10.72 rps | 0.00% | PASS |
| load | 5 | 300 | 113.20 ms | 203.61 ms | 42.54 rps | 0.00% | PASS |
| spike | 20 | 400 | 326.95 ms | 530.67 ms | 58.10 rps | 0.00% | PASS |

## Short conclusion

The spike profile used 20 virtual users instead of 1 in baseline. p95 changed from 188.07 ms to 530.67 ms, and throughput changed from 10.72 rps to 58.10 rps. The observed error rate under spike was 0.00%. The threshold result is PASS.
