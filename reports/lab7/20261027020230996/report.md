# Lab 7 performance test report

Run: `20261027020230996`

## Scenario

`POST /rentals → GET /rentals/{id} → POST /rentals/{id}/return → GET /rentals/{id}`. The `id` comes from the POST response and is used dynamically by the next requests.

## Acceptance criteria

- p95 response time <= 1000.00 ms
- error rate <= 1.00%

## Results

| Profile | VUs | Requests | Average | p95 | Throughput | Error rate | Result |
|---|---:|---:|---:|---:|---:|---:|---|
| baseline | 1 | 40 | 88.42 ms | 91.59 ms | 11.28 rps | 0.00% | PASS |
| load | 5 | 300 | 112.57 ms | 196.54 ms | 43.61 rps | 0.00% | PASS |
| spike | 20 | 400 | 320.27 ms | 542.40 ms | 57.61 rps | 0.00% | PASS |

## Short conclusion

The spike profile used 20 virtual users instead of 1 in baseline. p95 changed from 91.59 ms to 542.40 ms, and throughput changed from 11.28 rps to 57.61 rps. The observed error rate under spike was 0.00%. The threshold result is PASS.
