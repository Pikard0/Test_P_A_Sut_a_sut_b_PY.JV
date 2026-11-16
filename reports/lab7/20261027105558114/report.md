# Lab 7 performance test report

Run: `20261027105558114`

## Scenario

`POST /rentals → GET /rentals/{id} → POST /rentals/{id}/return → GET /rentals/{id}`. The `id` comes from the POST response and is used dynamically by the next requests.

## Acceptance criteria

- p95 response time <= 1000.00 ms
- error rate <= 1.00%

## Results

| Profile | VUs | Requests | Average | p95 | Throughput | Error rate | Result |
|---|---:|---:|---:|---:|---:|---:|---|
| baseline | 1 | 40 | 94.14 ms | 181.29 ms | 10.60 rps | 0.00% | PASS |
| load | 5 | 300 | 104.10 ms | 133.92 ms | 46.59 rps | 0.00% | PASS |
| spike | 20 | 400 | 335.87 ms | 623.48 ms | 55.27 rps | 0.00% | PASS |

## Short conclusion

The spike profile used 20 virtual users instead of 1 in baseline. p95 changed from 181.29 ms to 623.48 ms, and throughput changed from 10.60 rps to 55.27 rps. The observed error rate under spike was 0.00%. The threshold result is PASS.
