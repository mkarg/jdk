#!/bin/sh
sudo apt-get update -y
sudo apt-get upgrade -y
sudo apt-get install -y libasound2-dev libcups2-dev libx11-dev libxext-dev libxrender-dev libxrandr-dev libxtst-dev libxt-dev
sudo sdk install java 19-tem
unset JAVA_TOOL_OPTS

