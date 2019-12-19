def call(String buildStatus = 'STARTED') {
  // for some reason these constants can't be outside the function body as 'global constants'
  def COLOR_YELLOW = '#FFFF00'
  def COLOR_GREEN = '#00FF00'
  def COLOR_RED = '#FF0000'
  def STATUS_COLOR = [
    STARTED: COLOR_YELLOW,
    SUCCESSFUL: COLOR_GREEN,
    FAILED: COLOR_RED
  ]
  
  // build status of null means successful
  def status =  buildStatus ?: 'SUCCESSFUL'
  def subject = "${status}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
  def summary = "${subject} (${env.BUILD_URL})"
  def colorCode = STATUS_COLOR[status] ?: COLOR_YELLOW

  // Send notifications
  slackSend color: colorCode, message: summary
}
