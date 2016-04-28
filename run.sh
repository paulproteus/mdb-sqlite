#!/bin/bash
### This is a very simple script to run
### the converter via its Main class.

### It relies on java being on the path, and
### will only work on UNIX-like systems presumably.

set -e

PATH_TO_SCRIPT=$(readlink -f "$0")
DIRNAME=$(dirname "$PATH_TO_SCRIPT")


### Initialize comments
CLASSPATH=""

### Add lib/*.jar to classpath

for thing in "$DIRNAME"/lib/*jar
do
    CLASSPATH="$CLASSPATH:$thing"
done

### Add actual compiled code to classpath
CLASSPATH="$CLASSPATH:$DIRNAME/dist/classes"

### go!
java  -classpath "$CLASSPATH" com.plausiblelabs.mdb.Main "$@"
