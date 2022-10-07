#!/bin/bash
DEPTH="${1:-1}"

echo "Depth is $DEPTH"

sudo mn --controller=remote,127.0.0.1:6653 \
--topo=tree,depth=$DEPTH \
--switch=ovs,protocols=OpenFlow14