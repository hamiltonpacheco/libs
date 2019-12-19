def call(Map args) {
  properties([gitLabConnection('Gitlab')])
  // -- arguments --
  def serviceName = args.name
  def name = args.name
  def ignore_tests = "false"
  def branch = args.branch
  def servicePath = args.servicePath ?: args.name

  def nodeImage = args.nodeImage ?: 'node:dubnium'
  
  def testEnvFile = args.testEnvFile ?: null
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

  def isAWS = args.isAWS ?: false
  def isGCP = args.isGCP ?: false
  
  def context = args.context ?: "dasa-digital.internal"
  def sonarProjectPaths = args.sonarProjectPaths ?: [ 'api/v*' ]

  def useNexus = args.useNexus ?: false

  // -- global constants --
  def DEFAULT_CONTAINERS = [
    containerTemplate(name: 'node', image: nodeImage, ttyEnabled: true),
    containerTemplate(name: 'docker', image: 'docker', ttyEnabled: true),
    containerTemplate(name: 'gcloud', image: 'google/cloud-sdk', ttyEnabled: true),
    containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:latest', command: 'cat', ttyEnabled: true)
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
  def label = "job-${name}-${UUID.randomUUID().toString()}".take(63)
  def podContainers = DEFAULT_CONTAINERS + containers
  def podVolumes = DEFAULT_VOLUMES + volumes

  podTemplate(label: label, containers: podContainers, volumes: podVolumes, serviceAccount: 'jenkins') {
    node(label) {
      notifyStatus {
        // ** CLONE REPO **
        stage('Checkout') {
          checkout scm
          // author = sh(script: "git show -s --pretty=%an", returnStdout: true).trim()
          // sh "echo ${author}"
        }
        gitlabCommitStatus(name: currentBuild.fullDisplayName) {    
          container('node') {
            // ** INSTALL DEPENDENCIES **
            stage('Install Dependencies') {
              if(useNexus) {
                sh  """
                #!/bin/sh
                cat << EOF > ~/.npmrc 
                registry=https://nexus.nexadigital.com.br/repository/npm-group/  
                strict-ssl=false 
                always-auth=true 
                _auth="c3ZjX25wbTooZ3JiSnU2T1Q1VU4="
                email=jenkins@nexadigital.com.br  
                EOF"""
              }
              sh 'npm install'
            }
            // ** LINT CHECK **
            stage('Lint') {
              sh 'npm run lint'
            }
            
            //** UNIT AND INTEGRATION TESTS **
            if(serviceName != 'adfs-auth'){
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
          }
      
          if (masterBranch) {
            // -- image arguments --
            def tag = getGitCommit()
            def awsImageName = "${registryRepository}:${tag}"
            def gcrHmgImageName = "${GCP_REGISTRY_CONFIG.homolog.projectId}/${registryRepository}:${tag}"
            def gcrPrdImageName = "${GCP_REGISTRY_CONFIG.production.projectId}/${registryRepository}:${tag}"

            parallel(
              // ** BUILD AND PUBLISH AWS **
              "Build and publish image to ECR": {
                stage('Build and publish image to ECR') {
                  isGCP 
                    ? echo("Application is strictly GCP: skipping ECR image")
                    : container('docker') {
                      docker.withRegistry(REGISTRY_CONFIG.url, REGISTRY_CONFIG.credentials) {
                        docker.build(awsImageName)
                        
                        // Container Image Scanner
                        sh """
                        mkdir -p ${registryRepository}
                        docker save ${awsImageName} -o ${registryRepository}/${tag}.tar && \
                        apk add curl git && \
                        wget https://github.com/aquasecurity/trivy/releases/download/v0.1.6/trivy_0.1.6_Linux-64bit.tar.gz && \
                        tar zxvf trivy_0.1.6_Linux-64bit.tar.gz && \
                        mv trivy /usr/local/bin && \
                        trivy --exit-code 0 --severity CRITICAL --no-progress --auto-refresh  --input ${registryRepository}/${tag}.tar && \
                        docker tag ${awsImageName} ${REGISTRY_CONFIG.host}/${awsImageName} && \
                        docker push ${REGISTRY_CONFIG.host}/${awsImageName}
                        """
                      }
                    }
                }
              },
              // ** BUILD AND PUBLISH GCP STG **
              "Build and publish image to GCR STG": {
                stage('Build and publish image to GCR STG') {
                  isAWS 
                    ? echo("Application is strictly AWS: skipping GCR image")
                    : container('gcloud') {
                        docker.withRegistry(GCP_REGISTRY_CONFIG.url, "gcr:${GCP_REGISTRY_CONFIG.homolog.credentials}") {
                          docker
                            .build(gcrHmgImageName)
                            .push()
                        }
                      }
                }
              },
              // ** BUILD AND PUBLISH GCP PRD **
              "Build and publish image to GCR PRD": {
                stage('Build and publish image to GCR PRD') {
                  isAWS 
                    ? echo("Application is sctrictly AWS: skipping GCR image")
                    : container('gcloud') {
                        docker.withRegistry(GCP_REGISTRY_CONFIG.url, "gcr:${GCP_REGISTRY_CONFIG.production.credentials}") {
                          docker
                            .build(gcrPrdImageName)
                            .push()
                        }
                      }
                }
              }
            )

            stage("Deploy properties") {
              def imageName = isAWS ? awsImageName : gcrPrdImageName
              def imageNameHmg = isAWS ? awsImageName : gcrHmgImageName
              def imageHost = isAWS ? REGISTRY_CONFIG.host : GCP_REGISTRY_CONFIG.host
              def imageUrl = "${imageHost}/${imageName}"
              def imageUrlHomolog = "${imageHost}/${imageNameHmg}"
              
              def deployData = [
                application: name,
                namespace: namespace,
                servicePath: servicePath,
                imageUrl: imageUrl,
                team: "backend",
                jobname: env.JOB_NAME,
                author: getUserCommit(), 
                commit: getGitCommit(), 
                branch: branch, 
                commitTag: getTagCommit(), 
                repoUrl: getRepoUrl(),
                imageUrlHomolog: imageUrlHomolog,
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
              //jobCounter(stack: "backend", author: getUserCommit(), commit: getGitCommit(), branch: "${branch}", commitTag: getTagCommit(), repoUrl: getRepoUrl())
            }
          }
         }
       }
     }
    }
  }
}
