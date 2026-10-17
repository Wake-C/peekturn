#!/bin/sh
# Small wrapper entry point; the official Gradle wrapper JAR owns distribution setup.
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -z "$JAVA_HOME" ] && [ "$(uname)" = "Darwin" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null) || exit 1
    export JAVA_HOME
fi
if [ -n "$JAVA_HOME" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=java; fi
exec "$JAVA" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
