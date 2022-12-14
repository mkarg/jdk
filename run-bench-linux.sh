#!/bin/bash
pushd benchmarks
export JAVA_HOME=../build/linux-x86_64-server-release/images/jdk
mvn clean package && mvn exec:exec
popd
