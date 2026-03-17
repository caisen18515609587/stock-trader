#!/bin/sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-8.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
cd /Users/caisen/IdeaProjects/MyProject
nohup java -jar target/stock-trader-1.0.0-SNAPSHOT-jar-with-dependencies.jar auto 100000 3 60 30 >> logs/auto-trade.log 2>&1 &
echo $!

