#!/usr/bin/env python3
import subprocess, os, sys

project = "/Users/caisen/IdeaProjects/MyProject"
java_home = "/Library/Java/JavaVirtualMachines/amazon-corretto-8.jdk/Contents/Home"
jar = project + "/target/stock-trader-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
log_file = project + "/logs/auto-trade.log"

env = os.environ.copy()
env["JAVA_HOME"] = java_home
env["PATH"] = java_home + "/bin:" + env.get("PATH", "")

cmd = [
    java_home + "/bin/java",
    "-jar", jar,
    "auto", "100000", "3", "60", "30"
]

with open(log_file, "a") as log:
    p = subprocess.Popen(cmd, env=env, cwd=project, stdout=log, stderr=log)
    pid_file = project + "/trader.pid"
    with open(pid_file, "w") as f:
        f.write(str(p.pid))
    sys.stdout.write("Started PID=" + str(p.pid) + "\n")
    sys.stdout.flush()

