/* Copyright (c) 2018 - 2018 TomTom N.V. All rights reserved.
 *
 * This software is the proprietary copyright of TomTom N.V. and its subsidiaries and may be
 * used for internal evaluation purposes or commercial use strictly subject to separate
 * licensee agreement between you and TomTom. If you are the licensee, you are only permitted
 * to use this Software in accordance with the terms of your license agreement. If you are
 * not the licensee then you are not authorised to use this software in any manner and should
 * immediately return it to TomTom N.V.
 */

import com.tomtom.Helpers

def onlyWith(String[] messages) {
  def LOG_TAG = "[commits.onlyWith]"
  echo("${LOG_TAG} Validating commit messages against '${messages}' only")

  def entries = []
  def foundCommitTagOnly = true
  currentBuild.changeSets.each { changeSet ->
    changeSet.items.each { entry ->
      entries << entry
      if (!Helpers.any(messages, { entry.msg.contains(it) })) {
        foundCommitTagOnly = false
      }
    }
  }

  if (!entries.isEmpty() && foundCommitTagOnly) {
    def message = "Found commit(s) with the message '${messages}' only:"
    entries.each { entry -> message += "\n  - ${entry.commitId} by ${entry.author}: ${entry.msg}" }
    echo("${LOG_TAG} " + message)
  }
  echo("${LOG_TAG} result: ${foundCommitTagOnly}")
  return foundCommitTagOnly
}
