#!/bin/sh

if [ `whoami` != "root" ]; then 
    echo requires root permission
    exit 1;
fi

/sbin/chkconfig --add tme-bridge
if [ $? != 0 ]; then echo "$0: error at line $LINENO"; exit $?; fi
/sbin/service tme-bridge start
if [ $? != 0 ]; then echo "$0: error at line $LINENO"; exit $?; fi

cp -f /usr/share/mist/etc/tme-bridge.cron /etc/cron.d
if [ $? != 0 ]; then echo "$0: error at line $LINENO"; exit $?; fi

exit 0
