mkdir -p /var/run/tme
chown TME:TME /var/run/tme

if [ -e /etc/redhat-release ]
then
    source /etc/init.d/functions

    start() {
        checkpid `cat $pidfile 2>/dev/null`
        if [ $? == 0 ]
        then
            echo "$progname (`cat $pidfile`) is running, stop it first"
            exit 1
        fi

        echo -n $"Starting $progname: "
        daemon --user=$user $progpath daemon
        checkpid `cat $pidfile`
        RETVAL=$?
        echo
        [ $RETVAL = 0 ] && touch $lockfile
    }

    stop() {
        echo -n $"Stopping $progname: "
        killproc -p $pidfile
        RETVAL=$?
        echo
        rm -f $lockfile
        rm -f $pidfile
    }

    status() {
        checkpid `cat $pidfile 2>/dev/null`
        RETVAL=$?
        if [ $RETVAL == 0 ]
        then
            echo "$progname (`cat $pidfile`) is running"
        else
            echo "$progname does not exist!"
        fi
    }

    reload_monit() {
        /etc/init.d/monit reload
    }
elif [ -e /etc/lsb-release ]
then
    source /etc/lsb-release
    if [ "$DISTRIB_ID" == "Ubuntu" ]
    then
        start() {
            kill -0 `cat $pidfile` &> /dev/null
            if [ $? == 0 ]
            then
                echo "$progname (`cat $pidfile`) is running, stop it first"
                echo "FAILED"
                exit 1
            fi

            start-stop-daemon -v -c $user -p $pidfile -S --exec $progpath daemon
            kill -0 `cat $pidfile` &> /dev/null
            RETVAL=$?
            if [ $RETVAL == 0 ]
            then
                echo "DONE"
            else
                echo "FAILED"
            fi
        }

        stop() {
            start-stop-daemon -v -p $pidfile -K
            RETVAL=$?
            if [ $RETVAL == 0 ]
            then
                echo "DONE"
            else
                echo "FAILED"
            fi
        }

        status() {
            kill -0 `cat $pidfile` &> /dev/null
            RETVAL=$?
            if [ $RETVAL == 0 ]
            then
                echo "$progname (`cat $pidfile`) is running"
            else
                echo "$progname is not running"
            fi
        }

        reload_monit() {
            /etc/init.d/monit force-reload
        }
    fi
fi

