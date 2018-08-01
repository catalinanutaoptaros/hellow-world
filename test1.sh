#!/bin/bash
LAST_TAG_NUMBER=$(git describe --tags)
VERSION_NR=${LAST_TAG_NUMBER%"-SNAPSHOOT"}

echo "-->> snapshot: $LAST_TAG_NUMBER"
echo "-->> version: $VERSION_NR"


