def call(Map args) {
  properties([gitLabConnection('Gitlab')])
  // -- arguments --
  def name = args.name
  def branch = args.branch
  // def servicePath = args.servicePath ?: args.name

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

  def namespace = args.namespace 

  def isAWS = args.isAWS ?: false
  def context = args.context ?: "dasa-digital.internal"
  def sonarProjectPaths = args.sonarProjectPaths ?: [ './' ]

  def useNexus = args.useNexus ?: false
  def disableSonar = args.disableSonar ?: false
  def disableTests = args.disableTests ?: false
  
  def buildEnvVarList = { envVarMap -> 
  return envVarMap.collect({ key, value -> "${key}=${value}" }) 
}

  // -- global constants --
  def DEFAULT_CONTAINERS = [
    containerTemplate(name: 'node', image: nodeImage, ttyEnabled: true),
    containerTemplate(name: 'docker', image: 'docker', ttyEnabled: true),
    containerTemplate(name: 'gcloud', image: 'google/cloud-sdk', ttyEnabled: true),
    containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:latest', command: 'cat', ttyEnabled: true)
  ]
  def DEFAULT_VOLUMES = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]
  
    def registryConfig = [
        host: '454706396284.dkr.ecr.us-east-1.amazonaws.com',
        url: "https://454706396284.dkr.ecr.us-east-1.amazonaws.com",
        credentials: "ecr:us-east-1:jenkins-ecr"
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
        }
        gitlabCommitStatus(name: currentBuild.fullDisplayName) {    
          container('node') {
            // ** INSTALL DEPENDENCIES **
            stage('Install Dependencies') {
              if (branch == "master") {
              def productionEnvList = buildEnvVarList(productionEnv)
              container('node') {
                withEnv(productionEnvList) {
                }
              }
            }
              else if (branch != "master") {
              def homologEnvList = buildEnvVarList(homologEnv)
              container('node') {
                withEnv(homologEnvList) {
              }
            }
          }
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
              sh 'npm run build' 
            }
            // ** LINT CHECK **
              if (!disableTests) {
              stage('Lint') {
                sh 'npm run lint'
              }
            }
            
            // ** UNIT AND INTEGRATION TESTS **
              if (!disableTests) {
              stage('Tests') {
                def testEnvList = testEnv.collect({ key, value -> "${key}=${value}" })
                withEnv(testEnvList) {
                  sh 'npm run test:ci'
                }
              }
            }
              // ** SONAR SCANNER **
              if (!disableSonar) {
              stage('Check quality: Scan') {
                sh 'npm run sonar:scanner'
              }
            }
              // ** CODE QUALITY CHECKS **
              if (!disableSonar) {
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
            if (branch == "master" || branch == "develop") {
            // -- image arguments --

            def tag = getGitCommit()
            def awsImageName = "${registryRepository}:${tag}"
   
              // ** BUILD AND PUBLISH AWS **
                stage('Build and publish image to ECR') {
                  container('docker') {
                    docker.withRegistry(registryConfig.url, registryConfig.credentials) {
                      docker
                      .build(awsImageName)
                      // .push()
             //Container Image Scanner
                sh """
                mkdir -p ${registryRepository}
                docker save ${awsImageName} -o ${registryRepository}/${tag}.tar && \
                apk add curl git && \
                wget https://github.com/aquasecurity/trivy/releases/download/v0.1.6/trivy_0.1.6_Linux-64bit.tar.gz && \
                tar zxvf trivy_0.1.6_Linux-64bit.tar.gz && \
                mv trivy /usr/local/bin && \
                trivy --exit-code 0 --severity CRITICAL --no-progress --auto-refresh  --input ${registryRepository}/${tag}.tar && \
                docker tag ${awsImageName} ${registryConfig.host}/${awsImageName} && \
                docker push ${registryConfig.host}/${awsImageName}
                """
                    }
                  }
                }
              stage("Deploy properties") {
                // def imageName = isAWS ? awsImageName : gcrPrdImageName
                // def imageNameHmg = isAWS ? awsImageName : gcrHmgImageName
                // def imageHost = isAWS ? REGISTRY_CONFIG.host : GCP_REGISTRY_CONFIG.host
                def imageUrl = "${registryConfig.host}/${awsImageName}"
                
                def deployData = [
                  application: name,
                  // servicePath: servicePath,
                  imageUrl: imageUrl,
                  team: "backend",
                  namespace: namespace,
                  jobname: env.JOB_NAME,
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
              }
            }
          }
        }
      }
    }
  }
