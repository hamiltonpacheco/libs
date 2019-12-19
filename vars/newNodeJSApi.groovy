def call(Map args) {
  properties([gitLabConnection('Gitlab')])
  // -- arguments --
  def serviceName = args.name
  def name = args.name
  def servicePath = args.servicePath ?: args.name
  
  def nodeImage = args.nodeImage ?: 'node:erbium'
  def containers = args.containers ?: []
  def volumes = args.volumes ?: []
  def registryRepository = args.registryRepository ?: name
  def namespace = args.namespace ?: "ligma-production"
  
  def testEnvFile = args.testEnvFile ?: null
  def testEnv = args.testEnv ?: [:]
  
  def sonarProjectPaths = args.sonarProjectPaths ?: [ 'api/v*' ]
  def k8sManifests = args.kubernetesManifests ?: []

  def branch = args.branch
  def masterBranch = branch == 'master'
  
  // -- global constants --
  def DEFAULT_CONTAINERS = [
    containerTemplate(name: 'node', image: nodeImage, ttyEnabled: true),
    containerTemplate(name: 'docker', image: 'docker', ttyEnabled: true),
    containerTemplate(name: 'gcloud', image: 'google/cloud-sdk', ttyEnabled: true),
    containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:latest', command: 'cat', ttyEnabled: true)
  ]
  def DEFAULT_VOLUMES = [
    hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')
  ]
  def GCP_REGISTRY_CONFIG = [
    host: 'gcr.io',
    url: 'https://gcr.io',
    credentials: 'nexa-production',
    projectId: 'nexa-production'
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
  def label = "job-${name}-${UUID.randomUUID().toString()}".take(63)
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
              sh 'npm install'
            }
            
            // ** LINT CHECK **
            stage('Lint') {
              sh 'npm run lint'
            }
            
            // ** UNIT AND INTEGRATION TESTS **
            stage('Tests') {
              def allTestEnvVars = (testEnvFile ? loadDotEnv(testEnvFile) : [:]) + testEnv
              def testEnvList = allTestEnvVars.collect({ key, value -> "${key}=${value}" })
              withEnv(testEnvList) {
                sh 'npm run test:ci'
              }
            }
            
            if (masterBranch) {
              // ** SONAR SCANNER **
              stage('Check quality: Scan') {
                sh 'npm run sonar:scanner'
              }
          
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
            }
      
            if (masterBranch) {
              // -- image arguments --
              def tag = getGitCommit()
              def gcrImageName = "${GCP_REGISTRY_CONFIG.projectId}/${registryRepository}:${tag}"
              def gcrCredentialsName = "gcr:${GCP_REGISTRY_CONFIG.credentials}"
              
              stage('Build and publish image to GCR') {
                container('gcloud') {
                  docker.withRegistry(GCP_REGISTRY_CONFIG.url, gcrCredentialsName) {
                    docker
                      .build(gcrImageName)
                      .push()
                  }
                }
              }

              stage('Set docker image url') {
                // Replace 'image url' token in k8s manifests -> reference currently built image
                def imageUrl = "${GCP_REGISTRY_CONFIG.host}/${gcrImageName}"
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
