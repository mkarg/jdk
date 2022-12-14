pushd benchmarks
set JAVA_HOME=..\build\windows-x86_64-server-release\images\jdk
mvn clean package && mvn exec:exec
popd
