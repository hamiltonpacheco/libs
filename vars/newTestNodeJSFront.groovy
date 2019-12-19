def call(Map args) {
  properties([gitLabConnection('Gitlab')])
  // -- arguments --
  def name = args.name
  def branch = args.branch
  def servicePath = args.servicePath ?: args.name

  def nodeImage = args.nodeImage ?: 'node:dubnium'

  def testEnv = args.testEnv ?: [:]
  def productionEnv = args.productionEnv ?: [:]

  def containers = args.containers ?: []
  def volumes = args.volumes ?: []

  def registryRepository = args.registryRepository ?: name
  def masterBranch = branch == 'master'

  def clusterName = args.clusterName ?: []

  def namespace = args.namespace ?: "production"
  def env = productionEnv

  def sonarProjectPaths = args.sonarProjectPaths ?: [ 'api/v*' ]

  def k8sManifests = args.kubernetesManifests ?: []

  // -- global constants --
  def DEFAULT_CONTAINERS = [
    containerTemplate(name: 'node', image: nodeImage, ttyEnabled: true),
    containerTemplate(name: 'docker', image: 'docker', ttyEnabled: true),
    containerTemplate(name: 'gcloud', image: 'google/cloud-sdk', ttyEnabled: true),
    containerTemplate(name: 'chrome', image: 'nexadigital/node:10-browsers', command: 'cat', ttyEnabled: true),
    containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:latest', command: 'cat', ttyEnabled: true)
  ]
  def DEFAULT_VOLUMES = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]

  def GCP_REGISTRY_CONFIG = [
    host: 'gcr.io',
    url: "https://gcr.io",
    production: [
      projectId: 'nexa-production',
      credentials: 'nexa-production'
    ]
  ]
  def GKE_KUBECTL_PARAMS = [
    credentialsId: 'gke-nexa-production-jenkins-robot-token',
    serverUrl: 'https://35.231.43.84',
    contextName: 'gke_nexa-production_us-east1_nexa-production',
    clusterName: 'gke_nexa-production_us-east1_nexa-production',
    namespace: 'ligma-production'
  ]
  def IMAGE_URL_REPLACE_TOKEN = '<IMAGE_URL>'

  // -- execution --
  def label = "job-${UUID.randomUUID().toString()}".take(63)
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
              sh 'npm install'
            }

            // ** LINT CHECK **
            stage('Lint') {
              sh 'npm run lint'
            }

            container('chrome') {
              stage('Unit') {
                def testEnvList = buildEnvVarList(testEnv)
                withEnv(testEnvList) {
                  sh 'npm run test:unit'
                }
              }

              stage('Puppeteer') {
                def testEnvList = buildEnvVarList(testEnv)
                withEnv(testEnvList) {
                  sh 'npm run test:acceptance:puppeteer'
                }
              }

              stage('wdio') {
                def testEnvList = buildEnvVarList(testEnv)
                withEnv(testEnvList) {
                  sh 'npm run test:acceptance:wdio -- local'
                }
              }
            }

            if (masterBranch) {
              // ** SONAR SCANNER **
              stage('Check quality: Scan') {
                sh 'npm run sonar:scanner'
              }
            }
          }

          if (masterBranch) {
            // ** CODE QUALITY CHECKS **
            stage('Check quality: Quality Gate') {
              def scannedFolders = sonarProjectPaths.collect({ it.contains('*') ? listSubFolders(it) : it }).flatten()
              def qualityGateStatus = getQualityGateStatus
              if (!qualityGateStatus) {
                error "Failed because at least one version did not pass the Quality Gate"
              } else {
                echo "Passed Quality Gate check"
              }
            }

            // -- image arguments --
            def tag = getGitCommit()
            def gcrProductionImageName = "${GCP_REGISTRY_CONFIG.production.projectId}/${registryRepository}:${tag}"

            stage('Build and publish image to GCR Production') {
              def productionEnvList = buildEnvVarList(productionEnv)
              container('node') {
                withEnv(productionEnvList) {
                  sh 'npm run build'
                }
              }
              container('gcloud') {
                docker.withRegistry(GCP_REGISTRY_CONFIG.url, "gcr:${GCP_REGISTRY_CONFIG.production.credentials}") {
                  docker
                    .build(gcrProductionImageName)
                    .push()
                }
              }
            }

            stage('Set docker image url') {
              // Replace 'image url' token in k8s manifests -> reference currently built image
              def imageUrl = "${GCP_REGISTRY_CONFIG.host}/${gcrProductionImageName}"
              filesReplaceValue(files: k8sManifests, regex: IMAGE_URL_REPLACE_TOKEN, replacement: imageUrl)
            }

            stage('Deploy to GKE') {
              // Deploy to GKE using kubectl -> using 'jenkins-robot' kubernetes service account
              container('kubectl') {
                withKubeConfig( GKE_KUBECTL_PARAMS ) {
                  def command = k8sBuildApplyCommand(files: k8sManifests)
                  sh command
                }
              }
            }

            jobCounter(
              jobname: env.JOB_NAME, 
              stack: "backend", 
              author: getUserCommit(), 
              commit: getGitCommit(), 
              branch: branch, 
              commitTag: getTagCommit() , 
              repoUrl: getRepoUrl()
            )
            
          }
          
        }
      }
    }
  }
}