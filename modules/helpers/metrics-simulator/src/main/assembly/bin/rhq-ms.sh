#!/bin/bash

_DOLLARZERO=`readlink "$0" 2>/dev/null || echo "$0"`
RHQ_MS_BIN_DIR_PATH=`dirname "$_DOLLARZERO"`

if [ -z "$RHQ_MS_HOME" ]; then
  pushd $RHQ_MS_BIN_DIR_PATH/..
fi
RHQ_MS_HOME=`pwd`
popd

prepare_java()
{
  if [ -z "$JAVA_HOME" ]; then
    echo "JAVA_HOME must be set"
    exit 1
  fi
  RHQ_MS_JAVA_EXE=$JAVA_HOME/bin/java
}

prepare_classpath() 
{
  RHQ_MS_CLASSPATH="$RHQ_MS_HOME/conf"
  lib_dir=`cd "$RHQ_MS_HOME/lib";ls -1 *.jar`
  for jar in $lib_dir ; do
    RHQ_MS_CLASSPATH="$RHQ_MS_CLASSPATH:$RHQ_MS_HOME/lib/$jar"
  done
}

prepare_java_opts()
{
  if [ -z "$RHQ_MS_JAVA_OPTS" ]; then
    RHQ_MS_JAVA_OPTS="-Xms64M -Xmx64M -Djava.net.preferIPv4Stack=true"
  fi
}

launch_simulator()
{
  $RHQ_MS_JAVA_EXE $JAVA_OPTS $RHQ_MS_ADDITIONAL_JAVA_OPTS -cp "$RHQ_MS_CLASSPATH" org.rhq.metrics.simulator.SimulatorCLI "$@" &
}

source "$RHQ_MS_HOME/bin/rhq-ms-env.sh"

prepare_java
prepare_classpath
prepare_java_opts
launch_simulator $@
