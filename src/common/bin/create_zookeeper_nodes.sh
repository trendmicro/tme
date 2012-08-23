#!/bin/bash

if [[ "$1" != "" && "$2" != "" ]]
then

CMDS=`cat <<EOF
create $2 ''
create $2/global ''
create $2/global/graph ''
create $2/global/graph/processor ''
create $2/global/graph/route ''
create $2/global/fixed_exchange ''
create $2/global/reserved_broker ''
create $2/global/drop_exchange ''
create $2/global/limit_exchange ''
create $2/global/alert_limit_exchange ''
create $2/bridge ''
create $2/local ''
create $2/forwarder ''
create $2/broker ''
create $2/exchange ''
EOF
`
    echo -e "$CMDS" | java -cp '/opt/trend/tme/lib/*' org.apache.zookeeper.ZooKeeperMain -server $1

else
    echo Usage: $0 [ZooKeeperQuorum] [Prefix Path for TME]
fi

