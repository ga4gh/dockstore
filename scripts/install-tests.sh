#!/usr/bin/env bash
# Installs dependencies for integration tests, not used for unit tests
# Includes Bash3 Boilerplate. Copyright (c) 2014, kvz.io

set -o errexit
set -o pipefail
set -o nounset
set -o xtrace

if [ "${TESTING_PROFILE}" = "unit-tests" ] || [ "${TESTING_PROFILE}" == "automated-review" ]; then
    exit 0;
fi

if [ "${TESTING_PROFILE}" = "regression-integration-tests" ]; then
    pip3 install -r dockstore-webservice/src/main/resources/requirements/1.7.0/requirements3.txt
else
    pip3 install --user -r dockstore-webservice/src/main/resources/requirements/1.10.0/requirements3.txt
fi
