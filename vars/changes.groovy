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

def onlyIn(String[] files) {
  def LOG_TAG = "[changes.onlyIn]"
  echo("${LOG_TAG} Validating changes against '${files}' only")

  def foundFiles = []
  def foundChangesInOnly = true
  currentBuild.changeSets.each { changeSet ->
    changeSet.items.each { entry ->
      entry.affectedFiles.each { affectedFile ->
        if (!Helpers.any(files, {affectedFile.path.contains(it)})) {
          foundChangesInOnly = false
        }
        else {
          foundFiles << affectedFile
        }
      }
    }
  }

  if (!foundFiles.isEmpty() && foundChangesInOnly) {
    def message = "Found changes only in files ('${files}'):"
    foundFiles.each { file -> message += "\n  - ${file.path}" }
    echo("${LOG_TAG} ${message}")
  }
  echo("${LOG_TAG} result: ${foundChangesInOnly}")
  return foundChangesInOnly
}
