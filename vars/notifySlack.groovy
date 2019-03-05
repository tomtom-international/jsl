def call(channel, message = null, skipStatus = ['NOT_BUILT']) {
  if (currentBuild.currentResult in skipStatus) {
    return
  }

  if (message == null) {
    message = "Build ${currentBuild.currentResult} - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Link>)"
  }
  def slackColors = ['SUCCESS': 'good', 'FAILURE': 'danger', 'UNSTABLE': 'danger', 'ABORTED': 'danger']
  slackSend channel: channel, color: slackColors[currentBuild.currentResult], message: message
}
