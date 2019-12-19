def call(Map args) {
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

  def namespace = args.namespace ?: "default"

  // -- image arguments --
  def tag = "${gitlabMergeRequestLastCommit}"
  def imageName = "${registryRepository}:${tag}"
  def imageLatest = "${registryRepository}:latest"

  // -- global constants --
  def DEFAULT_CONTAINERS = [
    containerTemplate(name: 'node', image: nodeImage, ttyEnabled: true),
    containerTemplate(name: 'docker', image: 'docker', ttyEnabled: true),
    containerTemplate(name: 'gcloud', image: 'google/cloud-sdk', ttyEnabled: true),
    containerTemplate(name: 'aws-cli', image: '454706396284.dkr.ecr.us-east-1.amazonaws.com/aws-cli:latest', ttyEnabled: true),
    containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:v1.10.5', command: 'cat', ttyEnabled: true)

  ]
  def DEFAULT_VOLUMES = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]
  def REGISTRY_CONFIG = [
    host: '454706396284.dkr.ecr.us-east-1.amazonaws.com',
    url: "https://454706396284.dkr.ecr.us-east-1.amazonaws.com",
    credentials: "ecr:us-east-1:jenkins-ecr"
  ]
  
  def GCP_REGISTRY_CONFIG = [
    host: 'gcr.io',
    url: "https://gcr.io",
  ]

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
          // stage('Install Dependencies') {
          //   sh 'npm install'
          // }
          // // ** LINT CHECK **
          // stage('Lint') {
          //   sh 'npm run lint'
          // }
          // //** UNIT AND INTEGRATION TESTS **
          // stage('Tests') {
          //   def testEnvList = testEnv.collect({ key, value -> "${key}=${value}" })
          //   withEnv(testEnvList) {
          //     sh 'npm run test:cover'
          //   }
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

          // ** BUILD AND PUBLISH AWS **
          container('docker') {
            stage('Build and publish image') {
              sh "rm -rf api/*/lcov-report api/*/out coverage api/*/tests config/test.env .nyc* .git .eslintrc .editorconfig .gitignore .nvmrc"
              docker.withRegistry(REGISTRY_CONFIG.url, REGISTRY_CONFIG.credentials) {
                docker
                  .build(imageName)
                  .push()
              }
            }
          }

          // ** BUILD AND PUBLISH GCP **
          container('gcloud') {
            stage('GCP Auth') {
              sh "bash -x deploy/gcloud-auth.sh"
            }
            stage('Build and Push'){
              sh "bash -x deploy/docker-build.sh"
            }
            // stage('Create Deploy yml'){
            //   sh "bash -x deploy/create-pub-deploy-yml.sh"
            //   sh "bash -x deploy/create-sub-deploy-yml.sh"
            //   sh "kubectl apply -f deploy-pub.yml -n ${homolog}"
            //   sh "kubectl apply -f deploy-sub.yml -n ${homolog}"
            // }
          }


          stage("Deploy properties") {
            def imageUrl = "${GCP_REGISTRY_CONFIG.host}/${imageName}"
            
            def deployData = [
              application: name,
              servicePath: servicePath,
              imageUrl: imageUrl,
              environment: [
                homolog: homologEnv,
                production: productionEnv,
              ]
            ]

            if (canaryEnabled) {
              echo 'Canary enabled: Building canary config'
              container('kubectl') {
                def currentProductionImageUrl = getRunningPodImage app: name, namespace: 'production'
                def currentProductionState = getRunningPodState app: name, namespace: 'production'
                deployData.put 'canary', [
                  baselineState: currentProductionState,
                  baselineImageUrl: currentProductionImageUrl,
                  canaryImageUrl: imageUrl
                ]
              }
            }
            
            archiveDeployTriggerYaml name: 'deploy.yml', data: deployData
          }
        }
      }
    }
  }
