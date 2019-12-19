def call(projects = []) {

  def getProps = { project ->
    def task = readProperties(file: "${project}/.scannerwork/report-task.txt")
    def config = readProperties(file: "${project}/sonar-project.properties")
    return [task: task, config: config]
  }

  def basicAuthHeader = { username ->
    def creds = "${username}:".bytes.encodeBase64().toString()
    return [name: "Authorization", value: "Basic ${creds}"]
  }

  return projects
    .collect(getProps)
    .collect({ props ->
      def login = props.config["sonar.login"]
      def ceTask
      timeout(time: 1, unit: 'MINUTES') {
        waitUntil {
          def taskUrl = props.task['ceTaskUrl']
          echo "SonarQube Quality Gate: GET task url ${taskUrl}"
          def response = httpRequest(url: taskUrl, customHeaders: [basicAuthHeader(login)]) 
          ceTask = readJSON text: response.content
          return "SUCCESS".equals(ceTask['task']['status'])
        }
      }
      return [ceTask: ceTask, props: props]
    })
    .collect({ res ->
      def login = res.props.config["sonar.login"]
      def analysisUrl = "${res.props.task['serverUrl']}/api/qualitygates/project_status?analysisId=${res.ceTask['task']['analysisId']}"
      echo "SonarQube Quality Gate: GET analysis url ${analysisUrl}"
      def response = httpRequest(url: analysisUrl, customHeaders: [basicAuthHeader(login)]) 
      def qualityGate = readJSON text: response.content
      def status = qualityGate['projectStatus']['status']
      return [project: res.ceTask['task']['componentKey'], status: status]
    })
    .inject(true, { status, curr ->
      echo "${curr.project} has status ${curr.status}"
      return status && !"ERROR".equals(curr.status)
    })
}
