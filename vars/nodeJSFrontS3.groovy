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
  def releaseBranch = branch == 'release'
  def clusterName = args.clusterName ?: []

  def namespace = args.namespace ?: "production"

  def releaseDistribid = 'E2HMLHGPTAAN81'
  def masterDistribid = 'E2WJQ9RJ9LFY7A'

  def context = args.context ?: "dasa-digital.internal"
  def sonarProjectPaths = args.sonarProjectPaths ?: [ 'api/v*' ]
  def buildEnvVarList = { envVarMap -> 
    return envVarMap.collect({ key, value -> "${key}=${value}" }) 
  }

  // -- global constants --
  def DEFAULT_CONTAINERS = [
    containerTemplate(name: 'node', image: nodeImage, ttyEnabled: true),
    containerTemplate(name: 'docker', image: 'docker', ttyEnabled: true),
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
  def label = "job-${UUID.randomUUID().toString()}"
  def podContainers = DEFAULT_CONTAINERS + containers
  def podVolumes = DEFAULT_VOLUMES + volumes

  podTemplate(label: label, containers: podContainers, volumes: podVolumes, serviceAccount: 'jenkins') {
    node(label) {
      notifyStatus {
        stage('Checkout') {
          checkout scm
        }
        gitlabCommitStatus(name: currentBuild.fullDisplayName) {
          container('node') {
            stage('Install Dependencies') {
              sh 'apt update && apt install awscli -y'
              sh 'npm install'              
            }

            stage('Lint') {
              sh 'npm run lint'
            }

            stage('Unit tests') {
              sh 'npm run test'
            }

            container('chrome') {
              stage('Coverage') {
                sh 'npm run test:lighthouse'
              }
            }
          }

          // -- image arguments --
              def tag = getGitCommit()
              def awsStagingImageName = "${registryRepository}:homolog_${tag}"
              def awsProductionImageName = "${registryRepository}:production_${tag}"

          if (releaseBranch) {
              // ** BUILD AND PUBLISH **
              stage('Build and publish image to ECR Production') {
                def productionEnvList = buildEnvVarList(productionEnv)
                container('node') {
                  withEnv(productionEnvList) {
                    sh 'npm run build'
                  }
                }
                container('docker') {
                  docker.withRegistry(REGISTRY_CONFIG.url, REGISTRY_CONFIG.credentials) {
                    docker
                      .build(awsProductionImageName)
                    
                    //Container Image Scanner
                    sh """
                    mkdir -p ${registryRepository}
                    docker save ${awsProductionImageName} -o ${registryRepository}/${tag}.tar && \
                    apk add curl git && \
                    wget https://github.com/aquasecurity/trivy/releases/download/v0.1.6/trivy_0.1.6_Linux-64bit.tar.gz && \
                    tar zxvf trivy_0.1.6_Linux-64bit.tar.gz && \
                    mv trivy /usr/local/bin && \
                    trivy --exit-code 0 --severity CRITICAL --no-progress --auto-refresh  --input ${registryRepository}/${tag}.tar && \
                    docker tag ${awsProductionImageName} ${REGISTRY_CONFIG.host}/${awsProductionImageName} && \
                    docker push ${REGISTRY_CONFIG.host}/${awsProductionImageName}
                    """
                  }
                }
                container('node') {
                  stage('Sync Objects to S3 PRD') {
                      sh "aws s3 sync dist/ ${productionEnv.S3_BUCKET}  --region ${productionEnv.AWS_REGION} --acl public-read"
                  }
                }
                container('aws-cli') {
                  stage('Invalidate Cache') {
                      sh "aws cloudfront create-invalidation --distribution-id ${releaseDistribid} --paths '/*'"
                  }
                }
              }
            }
            else if (masterBranch) {
              container('node') {
                stage('Check quality: Scan') {
                  sh 'npm run sonar:scanner'
                }

                stage('Check quality: Quality Gate') {
                  def scannedFolders = sonarProjectPaths.collect({ it.contains('*') ? listSubFolders(it) : it }).flatten()
                  def qualityGateStatus = getQualityGateStatus
                  if (!qualityGateStatus) {
                    echo "Failed because at least one version did not pass the Quality Gate"
                  } else {
                    echo "Passed Quality Gate check"
                  }
                }
              }

              stage('Build and publish image to ECR Staging') {
                  def homologEnvList = buildEnvVarList(homologEnv)
                  container('node') {
                    withEnv(homologEnvList) {
                      sh 'npm run build'
                    }
                  }

                container('docker') {
                  docker.withRegistry(REGISTRY_CONFIG.url, REGISTRY_CONFIG.credentials) {
                    docker
                      .build(awsStagingImageName)
                      .push()
                  }
                }
                container('node') {
                  stage('Sync Objects to S3 HMG') {
                    sh """
                    aws s3 sync dist/ ${homologEnv.S3_BUCKET} --region ${homologEnv.AWS_REGION} --acl public-read\
                      --exclude '/*.gz' \
                      --delete \
                    """
                  }
                }
                container('aws-cli') {
                  stage('Invalidate Cache') {
                      sh "aws cloudfront create-invalidation --distribution-id ${masterDistribid} --paths '/*'"
                  }
                }
              }
            }

            stage("Deploy properties") {
              def imageStagingName = awsStagingImageName
              def imageProductionName = awsProductionImageName
              def imageHost = REGISTRY_CONFIG.host
              def imageStagingUrl = "${imageHost}/${imageStagingName}"
              def imageProductionUrl = "${imageHost}/${imageProductionName}"
              
              def deployData = [
                application: name,
                servicePath: servicePath,
                imageStagingUrl: imageStagingUrl,
                imageProductionUrl: imageProductionUrl,
                team: "frontend",
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