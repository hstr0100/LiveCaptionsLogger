#!/bin/bash

cd "$(dirname "$0")"

echo "Running: ./gradlew clean build"
./gradlew clean build

if [ $? -ne 0 ]; then
    echo "Gradle command failed. Exiting..."
    exit $?
fi

echo "Build and packaging completed successfully."
 
