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
  def namespace = args.namespace ?: "production"
  
  def testEnvFile = args.testEnvFile ?: null
  def testEnv = args.testEnv ?: [:]
  
  def k8sManifests = args.kubernetesManifests ?: []

  def branch = args.branch
  def masterBranch = branch == 'master'
  
  def context = args.context ?: "dasa-digital.internal"
  
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
  def REGISTRY_CONFIG = [
    host: '454706396284.dkr.ecr.us-east-1.amazonaws.com',
    url: "https://454706396284.dkr.ecr.us-east-1.amazonaws.com",
    credentials: "ecr:us-east-1:jenkins-ecr"
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

            if (masterBranch) {
              // -- image arguments --
              def tag = getGitCommit()
              def awsImageName = "${registryRepository}:${tag}"
              
              stage('Build and publish image to ECR') {
                container('docker') {
                  docker.withRegistry(REGISTRY_CONFIG.url, REGISTRY_CONFIG.credentials) {
                    docker.build(awsImageName)
                    // Container Image Scanner
                    sh """
                    mkdir -p ${registryRepository}
                    docker save ${awsImageName} -o ${registryRepository}/${tag}.tar && \
                    docker tag ${awsImageName} ${REGISTRY_CONFIG.host}/${awsImageName} && \
                    docker push ${REGISTRY_CONFIG.host}/${awsImageName}
                    """
                  }
                }
              }
              stage("Deploy properties") {
                def imageName = awsImageName
                def imageNameHmg = awsImageName
                def imageHost = REGISTRY_CONFIG.host
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
                ]

                archiveDeployTriggerYaml name: 'deploy.yml', data: deployData
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
