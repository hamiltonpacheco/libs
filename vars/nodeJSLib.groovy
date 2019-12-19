def call(Map args) {
  properties([gitLabConnection('Gitlab')])
  // -- arguments --
  def name = args.name
  def branch = args.branch

  def nodeImage = args.nodeImage ?: 'node:dubnium'

  def testEnv = args.testEnv ?: [:]
  def testEnvFile = args.testEnvFile ?: null

  def containers = args.containers ?: []
  def volumes = args.volumes ?: []

  def masterBranch = branch == 'master'

  def sonarProjectPaths = args.sonarProjectPaths ?: [ '.' ]

  // -- global constants --
  def DEFAULT_CONTAINERS = [
  containerTemplate(name: 'node', image: nodeImage, ttyEnabled: true)
  ]
  def DEFAULT_VOLUMES = []

  // -- execution --
  def label = "job-${UUID.randomUUID().toString()}"
  def podContainers = DEFAULT_CONTAINERS + containers
  def podVolumes = DEFAULT_VOLUMES + volumes

  def buildEnvVarList = { envVarMap -> 
    return envVarMap.collect({ key, value -> "${key}=${value}" }) 
  }

  podTemplate(label: label, containers: podContainers, volumes: podVolumes, serviceAccount: 'jenkins') {
    node(label) {
      notifyStatus {
        // ** CLONE REPO **
        stage('Checkout') {
          checkout scm
        }
        gitlabCommitStatus(name: currentBuild.fullDisplayName) {
          container('node') {
            // ** INSTALL DEPENDENCIES **
            stage('Install Dependencies') {
               sh  """
               #!/bin/sh
               cat << EOF > ~/.npmrc 
               @nexa:registry=http://nexus-repo.nexadigital.com.br/repository/npm-internal/
               email=svc_jenkins@nexadigital.com.br
               always-auth=true
               _auth=c3ZjX2plbmtpbnM6NUtJbG1kYkxuZHdCVnhkWkRzS3E=
               EOF"""
              sh 'npm install'
            }
            // ** LINT CHECK **
            stage('Lint') {
              sh 'npm run lint'
            }
            //** UNIT AND INTEGRATION TESTS **
            stage('Tests') {
              def allTestEnvVars = (testEnvFile ? loadDotEnv(testEnvFile) : [:]) + testEnv
                def testEnvList = buildEnvVarList(allTestEnvVars)
                withEnv(testEnvList) {
                  sh 'npm run test:ci'
                }
            }
            if (masterBranch) {
              // ** SONAR SCANNER **
              stage('Check quality: Scan') {
                sh 'npm run sonar:scanner'
              }
            }
            if (masterBranch) {
              // ** CODE QUALITY CHECKS **
              stage('Check quality: Quality Gate') {
                def scannedFolders = sonarProjectPaths.collect({ it.contains('*') ? listSubFolders(it) : it }).flatten()
                def qualityGateStatus = getQualityGateStatus scannedFolders
                if (!qualityGateStatus) {
                  error "Failed because at least one version did not pass the Quality Gate"
                } else {
                  echo "Passed Quality Gate check"
                }
              }
              stage('Publish Nexus Repository') {
                sh 'npm publish --verbose'
              }
              jobCounter(
                jobname: env.JOB_NAME, 
                stack: "backend", 
                author: getUserCommit(), 
                commit: getGitCommit(), 
                branch: "${branch}", 
                commitTag: getTagCommit() , 
                repoUrl: getRepoUrl()
              )
            }
          }
        }
      }
    }
  }
}
