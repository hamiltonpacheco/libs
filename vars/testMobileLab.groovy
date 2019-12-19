def call(Map args) {
  properties([gitLabConnection('Gitlab')])
  // -- arguments --
  def name = args.name
  def branch = args.branch
  def servicePath = args.servicePath ?: args.name
  def nodeImage = args.nodeImage ?: 'node:dubnium'
  def device = args.device
  def environment = args.environment
  
  def testEnv = args.testEnv ?: [:]
  def homologEnv = args.homologEnv ?: [:]
  def productionEnv = args.productionEnv ?: [:]
  
  def containers = args.containers ?: []
  def volumes = args.volumes ?: []
  
  def registryRepository = args.registryRepository ?: name
  def canaryEnabled = args.canaryEnabled ?: false
  def masterBranch = branch == 'master'
  def clusterName = args.clusterName ?: []
  def namespace = args.namespace ?: "production"
  def isProduction = false
  def env = isProduction ? productionEnv : homologEnv
  def isAWS = args.isAWS ?: false
  def context = args.context ?: "dasa-digital.internal"
  def sonarProjectPaths = args.sonarProjectPaths ?: [ 'api/v*' ]
  // -- global constants --
  def DEFAULT_CONTAINERS = [
    containerTemplate(name: 'node', image: nodeImage, ttyEnabled: true, privileged: true, alwaysPullImage: true)
  ]
  def DEFAULT_VOLUMES = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')
    // persistentVolumeClaim(mountPath: '/etc/labmobile', claimName: 'labmobile', readOnly: true)
  ]
  def REGISTRY_CONFIG = [
    host: '454706396284.dkr.ecr.us-east-1.amazonaws.com',
    url: "https://454706396284.dkr.ecr.us-east-1.amazonaws.com",
    credentials: "ecr:us-east-1:jenkins-ecr"
  ]
  def GCP_REGISTRY_CONFIG = [
    host: 'gcr.io',
    url: "https://gcr.io",
    homolog: [
      projectId: 'staging-211214',
      credentials: 'staging-211214'
    ],
    production: [
      projectId: 'dominica-202420',
      credentials: 'dominica-202420'
    ]
  ]
  // -- execution --
  def label = "job-${name}-${UUID.randomUUID().toString()}".take(63)
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
            checkout([$class: 'GitSCM', branches: [[name: '*/labMobile']],
            userRemoteConfigs: [[url: 'git@gitlab.nexadigital.com.br:livia/patient/web.git',credentialsId:'jenkins']]])
        }
        gitlabCommitStatus(name: currentBuild.fullDisplayName) {
          container('node') {
            // ** INSTALL DEPENDENCIES **
            stage('Install Dependencies') {
              sh 'node ./__tests__/labMobile/workarounds/replacePackage.js'
              sh 'npm install'
            }
            stage('Run Fix'){
              sh 'npm run labmobile:wdio:fix'
            }
            stage('Device Status Update'){
              sh 'npm run labmobile:devices:status:update'
            }
            stage('Test Mobile'){
              sh "DEVICE_NAME='${device}' npm run test:acceptance:wdio:${environment}:labmobile"
            }
            stage('Clean Reservations'){
              sh "DEVICE_NAME='${device}' npm run labmobile:devices:reservation:remove"
            }
          }
        }
      }
    }
  }
}