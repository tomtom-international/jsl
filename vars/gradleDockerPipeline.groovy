/* Copyright (c) 2018 - 2019 TomTom N.V. All rights reserved.
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

  pipeline {
    agent {
      label "docker"
    }

    parameters {
      booleanParam(defaultValue: false, description: 'Release the image', name: 'doRelease')
      booleanParam(defaultValue: false, description: 'Push a snapshot version of the image', name: 'doSnapshot')
    }

    options {
      disableConcurrentBuilds()
    }

    stages {
      stage("Build & Test") {
        when {
          beforeAgent true
          expression {
            !params.doRelease
          }
        }
        steps {
          sh "./gradlew check"
        }
      }

      stage("Push snapshot") {
        when {
          beforeAgent true
          expression {
            params.doSnapshot && !params.doRelease
          }
        }
        steps {
          sh "./gradlew dockerPush"
        }
      }

      stage("Release") {
        when {
          beforeAgent true
          allOf {
            anyOf {
              branch "master"
              branch "release/*"
            }
            expression {
              params.doRelease
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
