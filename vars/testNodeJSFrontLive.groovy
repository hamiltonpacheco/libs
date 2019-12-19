def call(Map args) {
  properties([gitLabConnection('Gitlab')])
  // -- arguments --
  def name = args.name

  def nodeImage = args.nodeImage ?: 'node:dubnium'
  
  def testEnv = args.testEnv ?: [:]
  
  def containers = args.containers ?: []
  def volumes = args.volumes ?: []
  
  // -- global constants --
  def DEFAULT_CONTAINERS = [
    containerTemplate(name: 'node', image: nodeImage, ttyEnabled: true),
    containerTemplate(name: 'chrome', image: 'nexadigital/node:10-browsers', command: 'cat', ttyEnabled: true)
  ]
  def DEFAULT_VOLUMES = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]

  // -- execution --
  def label = "job-${UUID.randomUUID().toString()}"
  def podContainers = DEFAULT_CONTAINERS + containers
  def podVolumes = DEFAULT_VOLUMES + volumes

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
              sh 'echo "127.0.0.1  cdn4.mxpnl.com" >> /etc/hosts'
              sh 'echo "127.0.0.1  google-analytics.com" >> /etc/hosts'
              sh 'echo "127.0.0.1  googletagmanager.com" >> /etc/hosts'
              sh 'npm install'
            }

            //** LIVE ACCEPTANCE TESTS **
            // "Run webdriverio acceptance tests": {
              container('chrome') {
                stage('wdio') {
                  def testEnvList = testEnv.collect({ key, value -> "${key}=${value}" })
                  withEnv(testEnvList) {
                    // sh 'npm run test:ci'
                    sh 'npm run test:acceptance:wdio:production'
                  }
                }
              }
            // },
          }
        }
      }
    }
  }
}