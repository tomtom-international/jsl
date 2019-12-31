/* Copyright (c) 2019 - 2019 TomTom N.V. All rights reserved.
 *
 * This software is the proprietary copyright of TomTom N.V. and its subsidiaries and may be
 * used for internal evaluation purposes or commercial use strictly subject to separate
 * licensee agreement between you and TomTom. If you are the licensee, you are only permitted
 * to use this Software in accordance with the terms of your license agreement. If you are
 * not the licensee then you are not authorised to use this software in any manner and should
 * immediately return it to TomTom N.V.
 */

def call(Map args, Closure body) {
  def LOG_TAG = "[withGitEnv]"

  if (!args.scmCredentialsId) {
    error("${LOG_TAG} Please provide a SCM credentials ID (scmCredentialsId)")
  }

  if (isSshCheckout()) {
    sshagent([args.scmCredentialsId]) {
      body()
    }
  }
  else if (isHttpsCheckout()) {
    withCredentials([usernamePassword(credentialsId: args.scmCredentialsId, passwordVariable: "PASSWORD", usernameVariable: "USERNAME")]) {
      writeFile file: "askpass.sh", text: """#!/usr/bin/env sh
case "\$1" in
Username*) echo \$USERNAME ;;
Password*) echo \$PASSWORD ;;
esac
"""
      sh "chmod +x askpass.sh"
      withEnv(["GIT_ASKPASS=${env.WORKSPACE}/askpass.sh"]) {
        body()
      }
    }
  }
  else {
    error("${LOG_TAG} Cannot push changes due to unsupported clone type (only SSH and HTTPS are supported)")
  }
}

def isSshCheckout() {
  return sh(script: "git config --get remote.origin.url | grep -q ssh", returnStatus: true) == 0
}

def isHttpsCheckout() {
  return sh(script: "git config --get remote.origin.url | grep -q https", returnStatus: true) == 0
}
