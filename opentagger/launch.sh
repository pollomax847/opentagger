#!/bin/bash
java \
  -Xms256m \
  -Xmx8g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:SoftRefLRUPolicyMSPerMB=1 \
  -Dawt.useSystemAAFontSettings=on \
  -Dswing.aatext=true \
  -jar "$(dirname "$0")/target/opentagger.jar" "$@"
