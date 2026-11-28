#!/bin/sh
# Usage: GRADLE=/path/to/gradle ./run-hct.sh [extra -Dzephyr.hct.* flags]
G=${GRADLE:-gradle}
rm -rf hct-out; mkdir -p hct-out
"$G" runHctHost --console=plain "$@" > hct-out/host.log 2>&1 &
HOST=$!
while [ ! -f hct-out/ready.txt ]; do
  kill -0 $HOST 2>/dev/null || { echo "host exited early"; tail -60 hct-out/host.log; exit 1; }
  sleep 2
done
"$G" runHctVictim --console=plain "$@" > hct-out/victim.log 2>&1 &
VICTIM=$!
wait $HOST
echo quit > hct-out/control.txt
wait $VICTIM
cat hct-out/report.txt 2>/dev/null || { echo "no report"; grep -E "HCT|Exception" hct-out/host.log | tail -30; exit 1; }
