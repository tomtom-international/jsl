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
  def LOG_TAG = "[pythonSetupPyPipeline]"

  if (!pipelineParams.pypiCredentials) {
    error("${LOG_TAG} Please provide pipelineParams.pypiCredentials")
  }
  if (!pipelineParams.pypiRepo) {
    error("${LOG_TAG} Please provide pipelineParams.pypiRepo")
  }
  if (!pipelineParams.sshAgentUser) {
    error("${LOG_TAG} Please provide pipelineParams.sshAgentUser")
  }
  if (!pipelineParams.dockerFilename) {
    pipelineParams["dockerFilename"] = "Dockerfile"
  }
  if (!pipelineParams.dockerBuildArgs) {
    pipelineParams["dockerBuildArgs"] = "--no-cache --network host"
  }
  if (!pipelineParams.dockerRunArgs) {
    pipelineParams["dockerRunArgs"] = "-v /etc/passwd:/etc/passwd:ro -v /opt/jenkins/.ssh:/opt/jenkins/.ssh:ro --network host"
  }

  if (pipelineParams.dockerDeploy) {
    if (!pipelineParams.dockerRegistryUrl) {
      error("${LOG_TAG} Please provide a Docker registry URL (eg. https://registry.example.com)")
    }
    if (!pipelineParams.dockerRegistryCredentialsId) {
      error("${LOG_TAG} Please provide a Jenkins credentials id for deploying to ${pipelineParams.dockerRegistryUrl}")
    }
    if (!pipelineParams.dockerRepo) {
      error("${LOG_TAG} Please provide a Docker repository name (eg. acme). The image name will be based on the Python module name (eg. acme/mymodule)")
    }
    if (!pipelineParams.dockerDeployFile) {
      echo("${LOG_TAG} Using default Dockerfile ('Dockerfile.deploy') for deployment")
      pipelineParams["dockerDeployFile"] = "Dockerfile.deploy"
    }
  }

  echo("${LOG_TAG} Pipeline params: ${pipelineParams}")

  pipeline {
    agent none

    parameters {
      booleanParam(defaultValue: false, description: "Release the module", name: "doRelease")
    }

    options {
      disableConcurrentBuilds()
    }

    stages {
      stage("Bump version Release") {
       agent {
          dockerfile {
            filename pipelineParams.dockerFilename
            additionalBuildArgs pipelineParams.dockerBuildArgs
            args pipelineParams.dockerRunArgs
            reuseNode true
          }
        }
        when {
          beforeAgent true
          allOf {
            branch "master"
            expression { params.doRelease }
          }
        }
        steps {
          sh "bumpversion release"
        }
      } // Bump version Release

      stage("Build") {
        agent {
          dockerfile {
            filename pipelineParams.dockerFilename
            additionalBuildArgs pipelineParams.dockerBuildArgs
            args pipelineParams.dockerRunArgs
            reuseNode true
          }
        }
        stages {
          stage("Build") {
            parallel {
              stage("Build module") {
                steps {
                  sh "python setup.py build"

                  script {
                    // Gather the module name and version so that it can be used in a later stage
                    moduleName = sh(script: "python setup.py --name", returnStdout: true).trim()
                    moduleVersion = sh(script: "python setup.py --version", returnStdout: true).trim()
                    echo("Module name: ${moduleName} version: ${moduleVersion}")
                  }
                }
              } // Build module

              stage("Build docs") {
                steps {
                  // Necessary to be able to locally install packages, which is required for build_sphinx
                  withEnv(["HOME=$WORKSPACE"]) {
                    sh "python setup.py install --user"
                    sh "python setup.py build_sphinx"
                  }
                }
              } // Build docs
            }
          } // Build
        }
      } // Build

      stage("Validation") {
        agent {
          dockerfile {
            filename pipelineParams.dockerFilename
            additionalBuildArgs pipelineParams.dockerBuildArgs
            args pipelineParams.dockerRunArgs
            reuseNode true
          }
        }
        stages {
          stage("Validate") {
            parallel {
              stage("Run Linter") {
                steps {
                  // Necessary to avoid issues with creating pylint files that track the delta of warnings.
                  withEnv(["HOME=$WORKSPACE"]) {
                    // setuptools-lint does not make a good job in installing all its dependencies.
                    sh "python setup.py install --user"
                    sh "python setup.py lint --lint-output-format parseable"
                  }
                }
                post {
                  always {
                    recordIssues enabledForFailure: true, tool: pyLint(), healthy: 0
                  }
                }
              } // Run Linter

              stage("Run Tests") {
                steps {
                  sh "python setup.py test --addopts '--cov-report xml:build/coverage.xml --cov-report term --cov-branch --junitxml=build/test_results.xml'"
                }
                post {
                  always {
                    junit "build/test_results.xml"
                    cobertura coberturaReportFile: "build/coverage.xml"
                  }
                }
              } // Run Tests
            }
          } // Validate
        }
      } // Validation

      stage("Package") {
        agent {
          dockerfile {
            filename pipelineParams.dockerFilename
            additionalBuildArgs pipelineParams.dockerBuildArgs
            args pipelineParams.dockerRunArgs
            reuseNode true
          }
        }
        steps {
          sh "python setup.py sdist"
          sh "twine check dist/*"
          stash includes: "dist/*.tar.gz", name: "pypi"
          stash includes: "Dockerfile.deploy", name: "docker"
        }
      } // Package

      stage("Deploy") {
        parallel {
          stage("Deploy PyPI") {
            agent {
              dockerfile {
                filename pipelineParams.dockerFilename
                additionalBuildArgs pipelineParams.dockerBuildArgs
                args pipelineParams.dockerRunArgs
                reuseNode true
              }
            }
            options {
              // No need to checkout sources again since we use stashed artifacts for deployment
              skipDefaultCheckout()
            }
            when {
              beforeAgent true
              allOf {
                branch "master"
                expression { params.doRelease }
              }
            }
            steps {
              unstash "pypi"
              withCredentials([usernamePassword(credentialsId: pipelineParams.pypiCredentials, usernameVariable: "USERNAME", passwordVariable: "PASSWORD")]) {
                sh "twine upload --verbose -u $USERNAME -p $PASSWORD --repository-url ${pipelineParams.pypiRepo} dist/*"
              }
            }
          } // Deploy PyPI

          stage("Deploy Docker") {
            agent {
              node {
                label "docker"
              }
            }
            options {
              // No need to checkout sources again since we use stashed artifacts for deployment
              skipDefaultCheckout()
            }
            when {
              beforeAgent true
              expression { pipelineParams.dockerDeploy }
            }
            steps {
              unstash "docker"
              unstash "pypi"
              script {
                docker.withRegistry(pipelineParams.dockerRegistryUrl, pipelineParams.dockerRegistryCredentialsId) {
                  def image = docker.image("${pipelineParams.dockerRepo}/${moduleName}:${moduleVersion}")
                  def imageExists = true
                  try {
                    image.pull()
                  } catch(Exception) {
                    echo "Image '${image.imageName()}' already exists in registry"
                    imageExists = false
                  }

                  // Snapshot images can be overwritten, whereas a release one shouldn't be.
                  if (isSnapshot(moduleVersion) || !imageExists) {
                    // Until https://github.com/jenkinsci/docker-workflow-plugin/pull/162 is merged we have to
                    // build and push the image directly via docker commands.
                    sh "docker build -t ${image.imageName()} -f ${pipelineParams.dockerDeployFile} ${pipelineParams.dockerBuildArgs} ."
                    sh "docker push ${image.imageName()}"
                  }
                }
              }
            }
          } // Deploy Docker
        }
      } // Deploy

      stage("Bump version Patch") {
        agent {
          dockerfile {
            filename pipelineParams.dockerFilename
            additionalBuildArgs pipelineParams.dockerBuildArgs
            args pipelineParams.dockerRunArgs
            reuseNode true
          }
        }
        when {
          beforeAgent true
          allOf {
            branch "master"
            expression { params.doRelease }
          }
        }
        steps {
          sshagent([pipelineParams.sshAgentUser]) {
            sh "git checkout master"

            sh "bumpversion --no-tag patch"

            sh "git push origin master --tags"
          }
        }
      } // Bump version Patch

    } // stages
  } // pipeline
} // call

def isSnapshot(version) {
  return (version.contains("-") || version.contains("dev"))
}
