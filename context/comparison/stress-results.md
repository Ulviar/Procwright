# Stress comparison raw results

Generated from `icli-comparison:stressComparisonReport` using `:icli-test-cli`.

Candidates: [iCLI rewrite, JDK ProcessBuilder, Apache Commons Exec, ZeroTurnaround zt-exec, NuProcess]

| Scenario | Candidate | Status | Passed | Median ms | Total ms | Note |
| --- | --- | --- | ---: | ---: | ---: | --- |
| ST01 parallel burst stdout/stderr bounded capture | Apache Commons Exec | PASS | 12/12 | 160 | 1898 |  |
| ST01 parallel burst stdout/stderr bounded capture | JDK ProcessBuilder | PASS | 12/12 | 140 | 1674 |  |
| ST01 parallel burst stdout/stderr bounded capture | NuProcess | PASS | 12/12 | 468 | 5612 |  |
| ST01 parallel burst stdout/stderr bounded capture | ZeroTurnaround zt-exec | PASS | 12/12 | 161 | 1950 |  |
| ST01 parallel burst stdout/stderr bounded capture | iCLI rewrite | PASS | 12/12 | 165 | 1995 |  |
| ST02 seeded flaky success/failure mix | Apache Commons Exec | PASS | 40/40 | 344 | 13351 | successes=20, failures=20 |
| ST02 seeded flaky success/failure mix | JDK ProcessBuilder | PASS | 40/40 | 426 | 15905 | successes=20, failures=20 |
| ST02 seeded flaky success/failure mix | NuProcess | PASS | 40/40 | 454 | 16767 | successes=20, failures=20 |
| ST02 seeded flaky success/failure mix | ZeroTurnaround zt-exec | PASS | 40/40 | 326 | 12884 | successes=20, failures=20 |
| ST02 seeded flaky success/failure mix | iCLI rewrite | PASS | 40/40 | 391 | 15396 | successes=20, failures=20 |
| ST03 hanging flaky timeout churn | Apache Commons Exec | PASS | 16/16 | 180 | 2859 |  |
| ST03 hanging flaky timeout churn | JDK ProcessBuilder | PASS | 16/16 | 101 | 1610 |  |
| ST03 hanging flaky timeout churn | NuProcess | PASS | 16/16 | 105 | 1686 |  |
| ST03 hanging flaky timeout churn | ZeroTurnaround zt-exec | PASS | 16/16 | 101 | 1721 |  |
| ST03 hanging flaky timeout churn | iCLI rewrite | PASS | 16/16 | 190 | 3013 |  |
| ST04 timeout stops parent and spawned child | Apache Commons Exec | FAIL | 0/1 | 1311 | 4331 | childPid=29949, childStopped=false, outcome=status=TIMEOUT, exit=143, timeout=true, stdoutBytes=12, stderrBytes=0, stdoutTruncated=false, stderrTruncated=false, note=watchdog killed process |
| ST04 timeout stops parent and spawned child | JDK ProcessBuilder | FAIL | 0/1 | 1008 | 4030 | childPid=29857, childStopped=false, outcome=status=TIMEOUT, exit=137, timeout=true, stdoutBytes=12, stderrBytes=0, stdoutTruncated=false, stderrTruncated=false, note=process exceeded timeout |
| ST04 timeout stops parent and spawned child | NuProcess | FAIL | 0/1 | 1016 | 4046 | childPid=30156, childStopped=false, outcome=status=TIMEOUT, exit=9, timeout=true, stdoutBytes=12, stderrBytes=0, stdoutTruncated=false, stderrTruncated=false, note=process still running after timeout |
| ST04 timeout stops parent and spawned child | ZeroTurnaround zt-exec | FAIL | 0/1 | 1025 | 4049 | childPid=30056, childStopped=false, outcome=status=TIMEOUT, exit=empty, timeout=true, stdoutBytes=12, stderrBytes=0, stdoutTruncated=false, stderrTruncated=false, note=timeout exception |
| ST04 timeout stops parent and spawned child | iCLI rewrite | PASS | 1/1 | 1052 | 1064 | childPid=29756, childStopped=true, outcome=status=TIMEOUT, exit=143, timeout=true, stdoutBytes=12, stderrBytes=0, stdoutTruncated=false, stderrTruncated=false, note=timeout result |
| ST05 pooled line-session mixed success/timeouts | Apache Commons Exec | UNSUPPORTED | 0/0 | 0 | 0 | no built-in pooled line-session abstraction; requires a custom pool and protocol state machine |
| ST05 pooled line-session mixed success/timeouts | JDK ProcessBuilder | UNSUPPORTED | 0/0 | 0 | 0 | no built-in pooled line-session abstraction; requires a custom pool and protocol state machine |
| ST05 pooled line-session mixed success/timeouts | NuProcess | UNSUPPORTED | 0/0 | 0 | 0 | no built-in pooled line-session abstraction; requires a custom pool and protocol state machine |
| ST05 pooled line-session mixed success/timeouts | ZeroTurnaround zt-exec | UNSUPPORTED | 0/0 | 0 | 0 | no built-in pooled line-session abstraction; requires a custom pool and protocol state machine |
| ST05 pooled line-session mixed success/timeouts | iCLI rewrite | PASS | 10/10 | 270 | 270 | successes=5, timeouts=5, metrics=PooledLineSessionMetrics[size=1, idle=1, leased=0, created=6, retired=5, completedRequests=5, failedRequests=5] |

## Scenario definitions

- `ST01`: parallel large stdout/stderr burst with bounded capture.
- `ST02`: deterministic seeded flaky success/failure mix.
- `ST03`: parallel timeout churn for hanging processes.
- `ST04`: timeout cleanup of parent plus spawned child process.
- `ST05`: mixed pooled line-session successes and request timeouts.
