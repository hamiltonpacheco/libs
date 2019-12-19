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
              // sh  """
              // #!/bin/sh
              // cat << EOF > ~/.npmrc
              // registry=https://nexus.nexadigital.com.br/repository/npm-group/
              // strict-ssl=false
              // always-auth=true
              // _auth="c3ZjX25wbTooZ3JiSnU2T1Q1VU4="
              // email=jenkins@nexadigital.com.br
              // EOF"""
              sh 'npm install'
            }

            // ** LINT CHECK **
            stage('Lint') {
              sh 'npm run lint'
            }

            // parallel(
              //** UNIT AND INTEGRATION TESTS **
              // "Run unit tests":{
                container('chrome') {
                  stage('Unit') {
                    def testEnvList = testEnv.collect({ key, value -> "${key}=${value}" })
                    withEnv(testEnvList) {
                      sh 'npm run test:unit'
                    }
                  }
                // },
              // "Run coverage tests":{
                  stage('Coverage') {
                    def testEnvList = testEnv.collect({ key, value -> "${key}=${value}" })
                    withEnv(testEnvList) {
                      sh 'npm run codacy-coverage'
                    }
                  }
              // },
              // "Run puppeteer acceptance tests": {
                  stage('Puppeteer') {
                    def testEnvList = testEnv.collect({ key, value -> "${key}=${value}" })
                    withEnv(testEnvList) {
                      sh 'npm run test:acceptance:puppeteer'
                    }
                  }
              // }
              // "Run webdriverio acceptance tests": {
                  stage('wdio') {
                    def testEnvList = testEnv.collect({ key, value -> "${key}=${value}" })
                    withEnv(testEnvList) {
                      // sh 'npm run test:ci'
                      sh 'npm run test:acceptance:wdio -- local'
                    }
                  }
                }
              // },
            // )

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
            def awsImageName = "${registryRepository}:${tag}"
            def gcrStagingImageName = "${GCP_REGISTRY_CONFIG.homolog.projectId}/${registryRepository}:${tag}"
            def gcrProductionImageName = "${GCP_REGISTRY_CONFIG.production.projectId}/${registryRepository}:${tag}"

            // parallel(
              // ** BUILD AND PUBLISH AWS **
              // "Build and publish image to ECR": {
              //   stage('Build and publish image to ECR') {
              //     container('docker') {
              //       docker.withRegistry(REGISTRY_CONFIG.url, REGISTRY_CONFIG.credentials) {
              //         docker
              //           .build(awsImageName)
              //           .push()
              //       }
              //     }
              //   }
              // },
              // ** BUILD AND PUBLISH GCP **
              // "Build and publish image to GCR Staging": {
                stage('Build and publish image to GCR Staging') {
                  def homologEnvList = buildEnvVarList(homologEnv)
                  container('node') {
                    withEnv(homologEnvList) {
                      sh 'npm run build'
                    }
                  }
                  container('gcloud') {
                    docker.withRegistry(GCP_REGISTRY_CONFIG.url, "gcr:${GCP_REGISTRY_CONFIG.homolog.credentials}") {
                      docker
                        .build(gcrStagingImageName)
                        .push()
                    }
                  }
                }
              // },
              // "Build and publish image to GCR Production": {
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
              // }
            // )

            stage("Deploy properties") {
              def imageProductionName = isAWS ? awsImageName : gcrProductionImageName
              def imageStagingName = isAWS ? awsImageName : gcrStagingImageName
              def imageHost = isAWS ? REGISTRY_CONFIG.host : GCP_REGISTRY_CONFIG.host
              def imageProductionUrl = "${imageHost}/${imageProductionName}"
              def imageStagingUrl = "${imageHost}/${imageStagingName}"
              
              def deployData = [
                application: name,
                servicePath: servicePath,
                imageProductionUrl: imageProductionUrl,
                imageStagingUrl: imageStagingUrl,
                team: "frontend",
                jobname: JOB_NAME,
                author: getUserCommit(), 
                commit: getGitCommit(), 
                branch: branch, 
                commitTag: getTagCommit(), 
                repoUrl: getRepoUrl(),
                environment: [
                  homolog: homologEnv,
                  production: productionEnv,
                ]
              ]

              if (canaryEnabled) {
                echo 'Canary enabled: Building canary config'
                container('kubectl') {
                  def currentProductionImageUrl = getRunningPodImage context: context, app: name, namespace: namespace
                  def currentProductionState = getRunningPodState context: context, app: name, namespace: namespace
                  deployData.put 'canary', [
                    baselineState: currentProductionState,
                    baselineImageUrl: currentProductionImageUrl,
                    canaryImageUrl: imageUrl
                  ]
                }
              }
              
              archiveDeployTriggerYaml name: 'deploy.yml', data: deployData
              //jobCounter(stack: "frontend", author: getUserCommit(), commit: getGitCommit(), branch: "${branch}", commitTag: getTagCommit(), repoUrl: getRepoUrl())
            }
          }
        }
      }
    }
  }
}