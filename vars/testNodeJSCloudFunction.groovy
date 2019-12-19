def call(Map args) {
  // -- arguments --
  def name = args.name
  def branch = args.branch

  def nodeImage = args.nodeImage ?: 'node:dubnium'
  
  def testEnv = args.testEnv ?: [:]
  def homologEnv = args.homologEnv ?: [:]
  def productionEnv = args.productionEnv ?: [:]
  
  def containers = args.containers ?: []
  def volumes = args.volumes ?: []
  
  def canaryEnabled = args.canaryEnabled ?: false
  def masterBranch = branch == 'master'

  // -- global constants --
  def DEFAULT_CONTAINERS = [
    containerTemplate(name: 'node', image: nodeImage, ttyEnabled: true),
    containerTemplate(name: 'docker', image: 'docker', ttyEnabled: true),
    containerTemplate(name: 'gcloud', image: 'google/cloud-sdk', ttyEnabled: true),
    containerTemplate(name: 'aws-cli', image: '454706396284.dkr.ecr.us-east-1.amazonaws.com/aws-cli:latest', ttyEnabled: true),
    containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:v1.10.5', command: 'cat', ttyEnabled: true)

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
        container('node') {
          // ** INSTALL DEPENDENCIES **
          stage('Install Dependencies') {
            sh "pwd"
            sh 'npm install'
          }
          // ** LINT CHECK **
          stage('Lint') {
            sh 'npm run lint'
          }
          //** UNIT AND INTEGRATION TESTS **
          stage('Tests') {
            def testEnvList = testEnv.collect({ key, value -> "${key}=${value}" })
            withEnv(testEnvList) {
              sh 'npm run test:cover'
            }
          }
          //** DEPLOY TO CLOUDFUNCTION **
          // stage('Deploy') {
          //   sh 'npm run deploy'
          // }
        }
        //   if (masterBranch) {
        //     // ** SONAR SCANNER **
        //     stage('Check quality: Scan') {
        //       sh 'npm run sonar:scanner'
        //     }
        //   }
        // }

        // if (masterBranch) {
        //   // ** CODE QUALITY CHECKS **
        //   stage('Check quality: Quality Gate') {
        //     def versionFolders = listSubFolders "**/api/v*"
        //     def qualityGateStatus = getQualityGateStatus versionFolders
        //     if (!qualityGateStatus) {
        //       error "Failed because at least one version did not pass the Quality Gate"
        //     } else {
        //       echo "Passed Quality Gate check"
        //     }
        //   }
        }
      }
    }
  }
