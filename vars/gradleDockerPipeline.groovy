/* Copyright (c) 2018 - 2018 TomTom N.V. All rights reserved.
 *
 * This software is the proprietary copyright of TomTom N.V. and its subsidiaries and may be
 * used for internal evaluation purposes or commercial use strictly subject to separate
 * licensee agreement between you and TomTom. If you are the licensee, you are only permitted
 * to use this Software in accordance with the terms of your license agreement. If you are
 * not the licensee then you are not authorised to use this software in any manner and should
 * immediately return it to TomTom N.V.
 */

def call(Map pipelineParams) {
  def LOG_TAG = "[gradleDockerPipeline]"

  if (!pipelineParams.dockerRegistryCredentials) {
    error ("${LOG_TAG} Please provide pipelineParams.dockerRegistryCredentials")
  }
  if (!pipelineParams.sshAgentUser) {
    error ("${LOG_TAG} Please provide pipelineParams.sshAgentUser")
  }
  if (!pipelineParams.changesOnlyInFiles) {
    pipelineParams["changesOnlyInFiles"] = ["CHANGELOG", "README", ".gitignore"]
    echo("${LOG_TAG} Using default value for changesOnlyInFiles: '${pipelineParams.changesOnlyInFiles}'")
  }
  if (!pipelineParams.commitsOnlyWithMessages) {
    pipelineParams["commitsOnlyWithMessages"] = ["Gradle Release Plugin"]
    echo("${LOG_TAG} Using default value for commitsOnlyWithMessages: '${pipelineParams.commitsOnlyWithMessages}'")
  }

  pipeline {
    agent {
      label "docker"
    }

    options {
      disableConcurrentBuilds()
    }

    stages {
      stage("Build & Test") {
        when {
          beforeAgent true
          not {
            anyOf {
              branch "master"
              branch "release/*"
            }
          }
        }
        steps {
          sh "./gradlew check"
        }
      }

      stage("Deploy") {
        when {
          beforeAgent true
          allOf {
            anyOf {
              branch "master"
              branch "release/*"
            }
            not {
              expression {
                commits.onlyWith(pipelineParams.commitsOnlyWithMessages as String[]) || changes.onlyIn(pipelineParams.changesOnlyInFiles as String[])
              }
            }
          }
        }
        steps {
          withCredentials([usernamePassword(credentialsId: pipelineParams.dockerRegistryCredentials, usernameVariable: "USERNAME", passwordVariable: "PASSWORD")]) {
            sshagent([pipelineParams.sshAgentUser]) {
              sh "./gradlew release -Prelease.useAutomaticVersion=true -Pdocker.registry.user=$USERNAME -Pdocker.registry.password=$PASSWORD"
            }
          }
        }
      }
    }
  }
}
