#!/bin/bash
set -e

current_directory=$(pwd)
test_framework_directory="$current_directory/.jenkinsfile-runner-test-framework"
working_directory="$test_framework_directory/work-test-buildPlugin"
src_directory="$test_framework_directory/source"

. $test_framework_directory/init-jfr-test-framework.inc

oneTimeSetUp() {
  rm -rf "$working_directory"
  mkdir -p "$working_directory"
  cd "$current_directory"
}

# Test https://github.com/jenkinsci/extended-read-permission-plugin
test_Smoke() {
  #TODO: Move to arguments
  local JFR_IMAGE=onenashev/ci.jenkins.io-runner:latest
  local pluginName=extended-read-permission
  local pluginRepo=${pluginName}-plugin
  local pluginVersion=6e8e27d2623d22d456b32e81878411c8f696f5a6
  git clone https://github.com/jenkinsci/${pluginRepo}.git && cd ${pluginRepo} && git checkout ${pluginVersion}
  docker run --rm -v "$current_directory/${pluginRepo}":/workspace -v "$current_directory/../..":/var/jenkins_home/pipeline-library "$JFR_IMAGE"
  #jenkinsfile_execution_should_succeed "$?" "$result"
}

init_framework
