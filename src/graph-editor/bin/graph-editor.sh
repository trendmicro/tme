#!/bin/bash

source /opt/trend/tme/conf/common/common-env.sh

CLASSPATH="$CLASSPATH:/opt/trend/tme/conf/graph-editor"
CLASSPATH="$CLASSPATH:/opt/trend/tme/lib/*"
CLASSPATH="$CLASSPATH:/opt/trend/tme/lib/jersey/*"
CLASSPATH="$CLASSPATH:/opt/trend/tme/lib/jetty/*"
CLASSPATH=`echo "$CLASSPATH" | sed -e 's/^://'` # remove leading colon

JVM_ARGS="$JVM_ARGS -Djava.security.auth.login.config=/opt/trend/tme/conf/graph-editor/ldaploginmodule.conf"
JVM_ARGS="$JVM_ARGS -Djava.security.egd=file:/dev/./urandom"

rm -rf /var/lib/tme/graph-editor/jsp
mkdir -p /var/lib/tme/graph-editor/jsp

if [ "$1" == "daemon" ]
then
    $JAVA_CMD -cp $CLASSPATH $JVM_ARGS com.trendmicro.tme.grapheditor.GraphEditorMain > /var/log/tme/graph-editor.err 2>&1 &
    echo $! > /var/run/tme/tme-graph-editor.pid
else
    $JAVA_CMD -cp $CLASSPATH $JVM_ARGS com.trendmicro.tme.grapheditor.GraphEditorMain
fi

