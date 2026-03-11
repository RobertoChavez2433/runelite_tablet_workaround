#!/usr/bin/env bash
# Wrapper to run tests with output logging
exec /data/data/com.termux/files/home/gl-tests/scripts/run-tests.sh --quick \
    > /data/data/com.termux/files/home/gl-test-output.log 2>&1
