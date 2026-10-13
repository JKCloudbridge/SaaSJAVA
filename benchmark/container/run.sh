#!/bin/bash
# Runs INSIDE the benchmark container: one pgbench scenario, then the percentiles of the latency of every transaction.
# Usage: run.sh <name> <scenario file> <clients> <seconds> <database user> [warm-up seconds] [transactions per second limit, 0 = none]
# Environment: THIRDS=1 also prints the 95th percentile of each third of the run (for the update-heavy scenario);
#              PGOPTIONS is passed on to the sessions (for example a statement timeout).
# Output: the pgbench report, a line "---LATENCY---", then "RESULT <count> <p50> <p95> <p99> <max>" in microseconds.
set -u
name=$1; file=$2; clients=$3; seconds=$4; user=$5; warm=${6:-0}
rate=${7:-0}
ratearg=""
if [ "$rate" -gt 0 ]; then ratearg="-R $rate"; fi
mkdir -p /tmp/bench
rm -f /tmp/bench/"$name".*
# A scan that the limit of the organization cancels is the limit working, not a failed run: the others go on.
continuearg=""
if [ "$name" = "s07-bg-limited-scan" ]; then continuearg="--continue-on-error"; fi
threads=$(( clients < 4 ? clients : 4 ))
vars=$(cat /bench/vars)

if [ "$warm" -gt 0 ]; then
    pgbench -n -M prepared -U "$user" -d bench -c "$clients" -j "$threads" -T "$warm" \
        $vars -f "$file" > /dev/null 2>&1
fi

pgbench -n -M prepared -U "$user" -d bench -c "$clients" -j "$threads" -T "$seconds" \
    -l --log-prefix=/tmp/bench/"$name" $continuearg $ratearg $vars -f "$file" 2>&1

echo "---LATENCY---"
cat /tmp/bench/"$name".* 2>/dev/null | awk '$3 ~ /^[0-9]+$/ {print $3}' | sort -n | awk '
    function pick(p,   i, j) { i = NR * p; j = int(i); if (j < i) j++; if (j < 1) j = 1; return a[j] }
    { a[NR] = $1 }
    END {
        if (NR == 0) { print "RESULT 0 0 0 0 0" }
        else { printf "RESULT %d %d %d %d %d\n", NR, pick(0.50), pick(0.95), pick(0.99), a[NR] }
    }'

if [ "${THIRDS:-0}" = "1" ]; then
    cat /tmp/bench/"$name".* 2>/dev/null | awk '$3 ~ /^[0-9]+$/ {print $5, $3}' | sort -n | awk -v dur="$seconds" '
        NR == 1 { t0 = $1 }
        { b = int(($1 - t0) / (dur / 3)); if (b > 2) b = 2; print b, $2 }' | sort -k1,1n -k2,2n | awk '
        { n[$1]++; v[$1, n[$1]] = $2 }
        END {
            for (b = 0; b < 3; b++) {
                i = n[b] * 0.95; j = int(i); if (j < i) j++; if (j < 1) j = 1
                printf "THIRD %d %d %d\n", b + 1, n[b], v[b, j]
            }
        }'
fi
