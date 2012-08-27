#!/bin/bash

if [ $# != 2 ]
then
    echo "Usage: $0 [rrd dir] [max record count]"
    exit 1
fi

cd $1
for json in `ls *.json`
do
    archive=`echo $json | sed -e 's/\.json$/\.archive/g'`
    cat $json >> $archive
    echo "," >> $archive
    if [ $((`wc -l $archive | cut -d ' ' -f 1` - $2)) -gt 100 ]
    then
	tail -n $2 $archive > $archive.tmp
	mv -f $archive.tmp $archive
    fi
done

