/* Copyright (c) 2019 - 2019 TomTom N.V. All rights reserved.
 *
 * This software is the proprietary copyright of TomTom N.V. and its subsidiaries and may be
 * used for internal evaluation purposes or commercial use strictly subject to separate
 * licensee agreement between you and TomTom. If you are the licensee, you are only permitted
 * to use this Software in accordance with the terms of your license agreement. If you are
 * not the licensee then you are not authorised to use this software in any manner and should
 * immediately return it to TomTom N.V.
 */

def call(Map pipelineParams) {
  def LOG_TAG = "[gradlePipeline]"

  if (!pipelineParams.sshAgentUser && !pipelineParams.scmCredentialsId) {
    error("${LOG_TAG} Please provide a SCM checkout credentials id [scmCredentialsId]")
  }
  if (pipelineParams.sshAgentUser) {
    echo("${LOG_TAG} DEPRECATION WARNING: 'sshAgentUser' is deprecated. Use 'scmCredentialsId' instead")
  }
  pipelineParams["scmCredentialsId"] = pipelineParams.scmCredentialsId ?: pipelineParams.sshAgentUser

  pipeline {
    agent {
      label "docker"
    }

    parameters {
      booleanParam(defaultValue: false, description: 'Release', name: 'doRelease')
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
        post {
          always {
            junit allowEmptyResults: true, testResults: 'build/test-results/**/*.xml'
          }
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
          withGitEnv([scmCredentialsId: pipelineParams.scmCredentialsId]) {
            sh "./gradlew release"
          }
        }
        post {
          always {
            junit allowEmptyResults: true, testResults: 'build/test-results/**/*.xml'
          }
        }
      }
    }
  }
}
