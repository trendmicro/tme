#!/bin/bash

source /opt/trend/tme/conf/common/common-env.sh

MEMORY=`sed -e '/imq\.system\.max_size/!d ; s/.*=//g' /opt/trend/tme/conf/broker/config.properties`

CLASSPATH="$CLASSPATH:/opt/trend/tme/conf/broker"
CLASSPATH="$CLASSPATH:/opt/trend/tme/lib/*"
CLASSPATH=`echo "$CLASSPATH" | sed -e 's/^://'` # remove leading colon

JVM_ARGS="$JVM_ARGS -server -Xmx$MEMORY -Xms$MEMORY"
JVM_ARGS="$JVM_ARGS -Dcom.sun.management.jmxremote.port=5566"
JVM_ARGS="$JVM_ARGS -Dcom.sun.management.jmxremote.authenticate=true"
JVM_ARGS="$JVM_ARGS -Dcom.sun.management.jmxremote.ssl=false"
JVM_ARGS="$JVM_ARGS -Dcom.sun.management.jmxremote.password.file=/opt/trend/tme/conf/broker/jmxremote.password"
JVM_ARGS="$JVM_ARGS -Dcom.sun.management.jmxremote.access.file=/opt/trend/tme/conf/broker/jmxremote.access"

if [ "$1" == "daemon" ]
then
    $JAVA_CMD -cp $CLASSPATH $JVM_ARGS com.trendmicro.tme.broker.EmbeddedOpenMQ > /var/log/tme/broker.err 2>&1 &
    echo $! > /var/run/tme/tme-broker.pid
else
    $JAVA_CMD -cp $CLASSPATH $JVM_ARGS com.trendmicro.tme.broker.EmbeddedOpenMQ
fi

