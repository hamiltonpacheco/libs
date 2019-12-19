def call(Map args) {
  properties([gitLabConnection('Gitlab')])
  // -- arguments --
  def name = args.name
  def branch = args.branch
  def servicePath = args.servicePath ?: args.name
  def nodeImage = args.nodeImage ?: 'node:dubnium'
  
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
    containerTemplate(name: 'node', image: nodeImage, ttyEnabled: true),
    containerTemplate(name: 'docker', image: 'docker', ttyEnabled: true),
    containerTemplate(name: 'gcloud', image: 'google/cloud-sdk', ttyEnabled: true),
    containerTemplate(name: 'aws-cli', image: '454706396284.dkr.ecr.us-east-1.amazonaws.com/aws-cli:latest', ttyEnabled: true),
    containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:latest', command: 'cat', ttyEnabled: true),
    containerTemplate(name: 'chrome', image: 'nexadigital/node:10-browsers', command: 'cat', ttyEnabled: true)
  ]
  def DEFAULT_VOLUMES = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]
  def REGISTRY_CONFIG = [
    host: '454706396284.dkr.ecr.us-east-1.amazonaws.com',
    url: "https://454706396284.dkr.ecr.us-east-1.amazonaws.com",
    credentials: "ecr:us-east-1:jenkins-ecr"
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
              sh 'npm install'
            }
            // ** LINT CHECK **
            // stage('Lint') {
            //   sh 'npm run lint'
            // }
          //   container('chrome') {
          //     stage('Unit') {
          //       def testEnvList = testEnv.collect({ key, value -> "${key}=${value}" })
          //       withEnv(testEnvList) {
          //         sh 'npm run test:unit'
          //       }
          //     }
          //   // },
          // // "Run coverage tests":{
          //     stage('Coverage') {
          //       def testEnvList = testEnv.collect({ key, value -> "${key}=${value}" })
          //       withEnv(testEnvList) {
          //         sh 'npm run codacy-coverage'
          //       }
          //     }
          // // },
          // // "Run puppeteer acceptance tests": {
          //     stage('Puppeteer') {
          //       def testEnvList = testEnv.collect({ key, value -> "${key}=${value}" })
          //       withEnv(testEnvList) {
          //         sh 'npm run test:acceptance:puppeteer'
          //       }
          //     }
          // // }
          // // "Run webdriverio acceptance tests": {
          //     stage('wdio') {
          //       def testEnvList = testEnv.collect({ key, value -> "${key}=${value}" })
          //       withEnv(testEnvList) {
          //         // sh 'npm run test:ci'
          //         sh 'npm run test:acceptance:wdio -- local'
          //       }
          //     }
          //   }
          
            // -- image arguments --
            def awsImageName = "${registryRepository}:mobileTests"

            stage('Build and publish image to ECR') {
              container('docker') {
                sh "rm -rf Dockerfile && mv Dockerfile-Mobile Dockerfile"
                docker.withRegistry(REGISTRY_CONFIG.url, REGISTRY_CONFIG.credentials) {
                  docker
                    .build(awsImageName)
                    .push()
                }
              }
            }
          }
        }
      }
    }
  }
}