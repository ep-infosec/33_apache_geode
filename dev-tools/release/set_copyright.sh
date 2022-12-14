#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e

usage() {
    echo "Usage: set_copyright.sh -j ticket dirs"
    echo "  -j   The GEODE-nnnnn Jira identifier for this chore"
    echo "  dirs one or more directories to search for copyrights"
    exit 1
}

if [[ "$3" == "" ]] ; then
    usage
fi

if [[ "$1" != "-j" ]] ; then
    usage
fi

JIRA=$2
shift 2

function failMsg {
  errln=$1
  echo "ERROR: set_copyright script did NOT complete successfully"
  echo "Comment out any steps that already succeeded (approximately lines 46-$(( errln - 1 ))) and try again"
}
trap 'failMsg $LINENO' ERR


echo ""
echo "============================================================"
echo "Checking Copyright NOTICE and updating year if necessary"
echo "============================================================"
set -x
year=$(date +%Y)
for DIR in $@ ; do
    cd ${DIR}
    git grep -l '^Copyright.*Apache' | grep NOTICE | while read NOTICE ; do
      sed \
        -e "2s/ \(20[0-9][0-9]\) / \1-${year} /" \
        -e "2s/-20[0-9][0-9] /-${year} /" \
        -e "2s/${year}-${year}/${year}/" \
        -i.bak $NOTICE
      rm -f $NOTICE.bak
      git add $NOTICE
    done
    if [ $(git diff --staged | wc -l) -gt 0 ] ; then
      git diff --staged --color | cat
      git commit -a -m "$JIRA: Bumping copyright year to ${year}"
    fi
done
set +x
