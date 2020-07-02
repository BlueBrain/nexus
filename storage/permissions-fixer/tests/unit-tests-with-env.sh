#!/usr/bin/env bash
set -x
if [[ $EUID -ne 0 ]]; then
   echo "Error: this script must be run as root"
   exit 1
fi

WORKSPACE=$PWD
RND=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w 16 | head -n 1)
TMP_DIR="/tmp/$RND"
mkdir $TMP_DIR
cp -a . $TMP_DIR
cd $TMP_DIR || exit 1

export NEXUS_PATH_PREFIX=/tmp/nexus-fixer
mkdir $NEXUS_PATH_PREFIX
cargo test

rm -rf $NEXUS_PATH_PREFIX
rm -rf $TMP_DIR
cd $WORKSPACE || exit 1
