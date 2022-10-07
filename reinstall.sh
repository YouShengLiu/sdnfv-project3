#!/bin/bash
mvn clean install -DskipTests
onos-app localhost deactivate nctu.winlab.bridge
onos-app localhost uninstall nctu.winlab.bridge
onos-app localhost install! target/bridge-016-1.0-SNAPSHOT.oar
# onos-app localhost install target/bridge-016-1.0-SNAPSHOT.oar
