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

  validateParameter(pipelineParams)
  initParameterWithBaseValues(pipelineParams)
  log("Pipeline params: ${pipelineParams}")

  pipeline {
    agent {
      node {
        label "docker"
      }
    }

    parameters {
      booleanParam(defaultValue: false, description: "Release the module", name: "doRelease")
      booleanParam(defaultValue: false, description: "Deploy snapshot Docker image", name: "doDockerSnapshot")
    }

    options {
      disableConcurrentBuilds()
      buildDiscarder(logRotator(numToKeepStr: '5'))
    }

    stages {

      // We create the build image only once and use it in later stages (reduces job time by ~50%).
      stage("Create build image") {
        steps {
          script {
            buildImage = docker.build("python-setup-py-build", "-f ${pipelineParams.dockerBuildFile} ${pipelineParams.dockerBuildArgs} .")
          }
        }
      } // Create build image

      // Create a release version (removing the .dev0 from `version`) if we are on master and the doRelease checkbox is ticked.
      stage("Bump version Release") {
       agent {
          docker {
            image buildImage.imageName()
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
        parallel {
          stage("Build module") {
            agent {
              docker {
                image buildImage.imageName()
                args pipelineParams.dockerRunArgs
                reuseNode true
              }
            }
            steps {
              sh "python setup.py build"
            }
          } // Build module

          stage("Build docs") {
            agent {
              docker {
                image buildImage.imageName()
                args pipelineParams.dockerRunArgs
                reuseNode true
              }
            }
            steps {
              // Necessary to be able to locally install packages, which is required for build_sphinx
              withEnv(["HOME=$WORKSPACE"]) {
                // * Use `develop` over `install` to avoid creating an egg file in dist/ otherwise
                //   the Docker deploy build will fail when pip will try to install the egg package.
                // * We need to call `develop` because otherwise no dependencies of the module itself
                //   will be installed and the doc generation will fail due to not found imports.
                sh "python setup.py develop --user"
                sh "python setup.py build_sphinx"
              }
            }
          } // Build docs
        }
      } // Build

      stage("Validate") {
        parallel {
          stage("Run Linter") {
            agent {
              docker {
                image buildImage.imageName()
                args pipelineParams.dockerRunArgs
                reuseNode true
              }
            }
            steps {
              // Necessary to avoid issues with creating pylint files that track the delta of warnings.
              withEnv(["HOME=$WORKSPACE"]) {
                // setuptools-lint does not do a good job in installing all its dependencies.
                // use `develop` over `install` to avoid creating an egg file in dist/
                sh "python setup.py develop --user"
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
            agent {
              docker {
                image buildImage.imageName()
                args pipelineParams.dockerRunArgs
                reuseNode true
              }
            }
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

      stage("Package") {
        agent {
          docker {
            image buildImage.imageName()
            args pipelineParams.dockerRunArgs
            reuseNode true
          }
        }
        steps {
          sh "python setup.py sdist"
          sh "twine check dist/*"

          script {
            // Gather the module name and version so that it can be used in the Docker deploy stage
            moduleName = sh(script: "python setup.py --name", returnStdout: true).trim()
            moduleVersion = sh(script: "python setup.py --version", returnStdout: true).trim()
          }
        }
      } // Package

      stage("Deploy") {
        parallel {
          stage("Deploy PyPI") {
            agent {
              docker {
                image buildImage.imageName()
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
              withCredentials([usernamePassword(credentialsId: pipelineParams.pypiCredentialsId, usernameVariable: "USERNAME", passwordVariable: "PASSWORD")]) {
                sh "twine upload --verbose -u $USERNAME -p $PASSWORD --repository-url ${pipelineParams.pypiRepo} dist/*"
              }
            }
          } // Deploy PyPI

          stage("Deploy Docker") {
            when {
              beforeAgent true
              expression {
                // On master builds deploy always if dockerDeploy == true.
                // On branch/PR builds deploy only on-demand.
                (BRANCH_NAME ==~ /(master)/ && pipelineParams.dockerDeploy) || params.doDockerSnapshot
              }
            }
            steps {
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
                    try {
                      sh "docker build -t ${image.imageName()} -f ${pipelineParams.dockerDeployFile} ${pipelineParams.dockerBuildArgs} ."
                      sh "docker push ${image.imageName()}"
                    } finally {
                      sh "docker rmi ${image.imageName()}"
                    }
                  }
                }
              }
            }
          } // Deploy Docker
        }
      } // Deploy

      stage("Bump version Patch") {
        agent {
          docker {
            image buildImage.imageName()
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

    post {
      cleanup {
        // Leave the campground cleaner than you found it.
        deleteDir()
        // The declarative pipeline produces many images (with hash names) and does not clean them up (and won't
        // do it: https://issues.jenkins-ci.org/browse/JENKINS-40723).
        sh "docker system prune --volumes --force"
      }
    }
  } // pipeline
} // call


def validateParameter(Map pipelineParams) {
  if (!pipelineParams.pypiCredentials && !pipelineParams.pypiCredentialsId) {
    throwError("Please provide a Jenkins credentials id for the specified PyPI repository [pypiCredentials]")
  }
  if (!pipelineParams.sshAgentUser) {
    throwError("Please provide a SSH agent user [sshAgentUser]")
  }

  if (pipelineParams.dockerDeploy) {
    if (!pipelineParams.dockerRegistryUrl) {
      throwError("Please provide a Docker registry URL (eg. https://registry.example.com) [dockerRegistryUrl]")
    }
    if (!pipelineParams.dockerRegistryCredentialsId) {
      throwError("Please provide a Jenkins credentials id for deploying to ${pipelineParams.dockerRegistryUrl} [dockerRegistryCredentialsId]")
    }
    if (!pipelineParams.dockerRepo) {
      throwError("Please provide a Docker repository name (eg. acme). The image name will be based on the Python module name (eg. acme/mymodule) [dockerRepo]")
    }
  }
}

def initParameterWithBaseValues(Map pipelineParams) {
  // TODO: once all pipelines are updated to use pypiCredentialsId, remove this line:
  pipelineParams["pypiCredentialsId"] = pipelineParams.pypiCredentialsId ?: pipelineParams.pypiCredentials
  // TODO: once all pipelines are updated to use dockerBuildFile, remove all references to dockerFilename.
  pipelineParams["dockerFilename"] = pipelineParams.dockerFilename ?: "Dockerfile.build"
  pipelineParams["dockerBuildFile"] = pipelineParams.dockerBuildFile ?: pipelineParams.dockerFilename
  pipelineParams["dockerDeployFile"] = pipelineParams.dockerDeployFile ?: "Dockerfile.deploy"
  pipelineParams["dockerBuildArgs"] = pipelineParams.dockerBuildArgs ?: ""
  pipelineParams["dockerBuildArgs"] += " --no-cache --network host"
  pipelineParams["dockerRunArgs"] = pipelineParams.dockerRunArgs ?: ""
  pipelineParams["dockerRunArgs"] += " -v /etc/passwd:/etc/passwd:ro -v /opt/jenkins/.ssh:/opt/jenkins/.ssh:ro --network host"
  pipelineParams["pypiRepo"] = pipelineParams.pypiRepo ?: "https://test.pypi.org/legacy/"
}

def log(message) {
  echo("[pythonSetupPyPipeline] ${message}")
}

def throwError(message) {
  error("[pythonSetupPyPipeline] ${message}")
}

def isSnapshot(version) {
  return (version.contains("-") || version.contains("dev"))
}
