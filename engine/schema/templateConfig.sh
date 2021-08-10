#!/bin/bash

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

function getTemplateVersion() {
  projVersion=$1
  version="$(cut -d'-' -f1 <<<"$projVersion")"
  subversion1="$(cut -d'.' -f1 <<<"$version")"
  subversion2="$(cut -d'.' -f2 <<<"$version")"
  minorversion="$(cut -d'.' -f3 <<<"$version")"
  export CS_VERSION="${subversion1}"."${subversion2}"
  export CS_MINOR_VERSION="${minorversion}"
}

function getGenericName() {
  hypervisor=$(echo "$1" | tr "[:upper:]" "[:lower:]")
  if [[ "$hypervisor" == "ovm3" ]]; then
    echo "ovm"
  elif [[ "$hypervisor" == "lxc" ]]; then
    echo "kvm"
  elif [[ "$hypervisor" == "xenserver" ]]; then
    echo "xen"
  else
    echo "$hypervisor"
  fi
}

function getChecksum() {
  local fileData="$1"
  local hvName=$2
  while IFS= read -r line; do
    if [[ $line == *"$hvName"* ]]; then
      echo "$(cut -d' ' -f1 <<<"$line")"
    fi
  done <<< "$fileData"
}

function createMetadataFile() {
  local fileData=$(cat $SOURCEFILE)
  for i in "${!templates[@]}"
  do
    section="$i"
    hvName=$(getGenericName $i)
    templatename="systemvm-${i}-${CS_VERSION}"
    checksum=$(getChecksum "$fileData" $hvName)
    downloadurl="${templates[$i]}"
    filename=$(echo ${downloadurl##*'/'})
    echo -e "["$section"]\ntemplatename = $templatename\nchecksum = $checksum\ndownloadurl = $downloadurl\nfilename = $filename\n" >> $METADATAFILE
  done


}

declare -A templates
getTemplateVersion $1
# TODO: Update correct url post testing
#templates=( ["kvm"]="https://download.cloudstack.org/systemvm/${CS_VERSION}/systemvmtemplate-${CS_VERSION}.${CS_MINOR_VERSION}-kvm.qcow2.bz2"
#            ["vmware"]="https://download.cloudstack.org/systemvm/${CS_VERSION}/systemvmtemplate-${CS_VERSION}.${CS_MINOR_VERSION}-vmware.ova"
#            ["xenserver"]="https://download.cloudstack.org/systemvm/$CS_VERSION/systemvmtemplate-$CS_VERSION.$CS_MINOR_VERSION-xen.vhd.bz2"
#            ["hyperv"]="https://download.cloudstack.org/systemvm/$CS_VERSION/systemvmtemplate-$CS_VERSION.$CS_MINOR_VERSION-hyperv.vhd.zip"
#            ["lxc"]="https://download.cloudstack.org/systemvm/$CS_VERSION/systemvmtemplate-$CS_VERSION.$CS_MINOR_VERSION-kvm.qcow2.bz2"
#            ["ovm3"]="https://download.cloudstack.org/systemvm/$CS_VERSION/systemvmtemplate-$CS_VERSION.$CS_MINOR_VERSION-ovm.raw.bz2" )

templates=( ["kvm"]="http://10.0.3.122/systemvmtemplate/custom/cks-debian/${CS_VERSION}/systemvmtemplate-${CS_VERSION}.${CS_MINOR_VERSION}-kvm.qcow2.bz2"
            ["vmware"]="http://10.0.3.122/systemvmtemplate/custom/cks-debian/${CS_VERSION}/systemvmtemplate-${CS_VERSION}.${CS_MINOR_VERSION}-vmware.ova"
            ["xenserver"]="http://10.0.3.122/systemvmtemplate/custom/cks-debian/systemvmtemplate-$CS_VERSION.$CS_MINOR_VERSION-xen.vhd.bz2"
            ["hyperv"]="http://10.0.3.122/systemvmtemplate/custom/cks-debian/$CS_VERSION/systemvmtemplate-$CS_VERSION.$CS_MINOR_VERSION-hyperv.vhd.zip"
            ["lxc"]="http://10.0.3.122/systemvmtemplate/custom/cks-debian/$CS_VERSION/systemvmtemplate-$CS_VERSION.$CS_MINOR_VERSION-kvm.qcow2.bz2"
            ["ovm3"]="http://10.0.3.122/systemvmtemplate/custom/cks-debian/$CS_VERSION/systemvmtemplate-$CS_VERSION.$CS_MINOR_VERSION-ovm.raw.bz2" )


PARENTPATH="$( cd -- "$(dirname "$0")" >/dev/null 2>&1 ; pwd -P )/dist/systemvm-templates/"
mkdir -p $PARENTPATH
METADATAFILE=${PARENTPATH}"metadata.ini"
echo > $METADATAFILE
SOURCEFILE=${PARENTPATH}'md5sum.txt'
createMetadataFile
