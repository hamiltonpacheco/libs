def call(Map args) {
  properties([gitLabConnection('Gitlab')])
    def serviceName = args.name
    def branch = args.branch
    def ignore_tests = "false"
    // def env = env.gitlabBranch == "master" ? "hmg" : "prd"
    // def env = args.branch == "production" ? "prd" : "hmg" 
    def env = ""
    if(args.branch == "production"){
        env = "prd"
    }else if(args.branch == "master"){
        env = "hmg"
    }else{
        env = "feature"
    }
    def min = env == "prd" ? 2 : 2
    def max = env == "prd" ? 50 : 5
    def disableSonar = args.disableSonar ?: false
    def sonarProjectPaths = args.sonarProjectPaths ?: [ 'hand/v*' ]
    println(args.branch)
    println(env)

    def containers = [
    // containerTemplate(name: 'ansible', image: '454706396284.dkr.ecr.us-east-1.amazonaws.com/ansible:v2', ttyEnabled: true),
      containerTemplate(name: 'ubuntu', image: 'ubuntu:bionic', ttyEnabled: true),
      containerTemplate(name: 'docker', image: 'docker', ttyEnabled: true)
    ]

    // def volumes = [hostPathVolume(hostPath: '/var/run/', mountPath: '/var/run/')]
    def volumes = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]

    def registryConfig = [
        host: '454706396284.dkr.ecr.us-east-1.amazonaws.com',
        url: "https://454706396284.dkr.ecr.us-east-1.amazonaws.com",
        credentials: "ecr:us-east-1:jenkins-ecr"
    ]

    def label = "job-${serviceName}-${UUID.randomUUID().toString()}".take(63)

    def jenkinsCredentialsId = "ssh-jenkins"

    podTemplate(label: label, containers: containers, volumes: volumes, serviceAccount: 'jenkins') {

    node(label) {
        notifyStatus {
            gitlabCommitStatus(name: currentBuild.fullDisplayName) {
                container('ubuntu') {
                    stage('Checkout and Clone Repo Libs') {
                        checkout scm 

                        sh "apt-get update && \
                            apt-get install -y software-properties-common &&\
                            add-apt-repository -y ppa:cran/poppler && \
                            apt-get install -y poppler-utils=0.74.0-bionic0 \
                            libsm6 \
                            libpq-dev \
                            python3 \
                            wget \
                            unzip \
                            git \
                            python3-pip \
                            libssl-dev"
                        sh "echo ttf-mscorefonts-installer msttcorefonts/accepted-mscorefonts-eula select true | debconf-set-selections && \
                            apt-get install -y ttf-mscorefonts-installer"
                    }
                    stage('Install Libs and Application') {
                        sh "mkdir -p ~/.config/pip"
                        sh """
                        #!/bin/sh
                        cat << EOF > ~/.config/pip/pip.conf
                        [global]
                        trusted-host = nexus-repo.nexadigital.com.br
                        index-url = http://jenkins-nexus:yV9vsda7eW34gMxbB8@nexus-repo.nexadigital.com.br/repository/py-internal/simple
                        """
                        sh "pip3 install -r requirements.deploy.txt  --upgrade"
                        sh "cp ~/.config/pip/pip.conf ."
                    }
                    if(serviceName == 'spine'){
                        ignore_tests = "true"
                    } else if(serviceName == 'captation'){
                        ignore_tests = "true"
                    } else if(serviceName == 'hand'){
                        ignore_tests = "true"
                    } else if(serviceName == 'risk-model'){
                        ignore_tests = "true"
                    }
                    if (ignore_tests != "true") {
                        stage('Running Tests'){
                            sh "pytest test --doctest-modules"
                            sh "pytest --cov-report xml:cobertura.xml --cov=${serviceName} test"
                        }
                    }
                    if(serviceName == 'hand'){
                        stage('Running Lint'){
                            sh "paver lint"
                        }
                        stage('SonarQube analysis') {
                            sh "wget https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-4.0.0.1744-linux.zip"
                            sh "unzip sonar-scanner-cli-4.0.0.1744-linux.zip"
                            sh "export PATH=\"$PATH:${WORKSPACE}/sonar-scanner-4.0.0.1744-linux/bin\" && \
                                paver scan"
                            sh "cd ${WORKSPACE} && pwd ${WORKSPACE}"
                        }
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
                    if (!disableSonar) {
                        stage('SonarQube analysis') {
                            def sonar_path = readFile file: "sonar-project.properties", encoding: "UTF-8"
                            sonar_path = sonar_path.replace("{{workspace}}", "${WORKSPACE}")
                            writeFile file: "sonar-project.properties", text: sonar_path, encoding: "UTF-8"
                            sh "wget https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-4.0.0.1744-linux.zip"
                            sh "unzip sonar-scanner-cli-4.0.0.1744-linux.zip"
                            sh "${WORKSPACE}/sonar-scanner-4.0.0.1744-linux/bin/sonar-scanner -Dproject.settings=${WORKSPACE}/sonar-project.properties"
                        }
                        stage('Check quality: Quality Gate Status') {
                            def qualityGateStatus = getQualityGateStatus(["."])
                            if (!qualityGateStatus) {
                            error "Failed because at least one version did not pass the Quality Gate"
                            } else {
                            echo "Passed Quality Gate check"
                            println branch
                            }
                        }
                    }
                }
                def tag = getGitCommit()
                def stack = env == "prd" ? "PRODUCTION" : "STAGING"
                def imageName = "${serviceName}-${env}:${tag}"
                def namespace = env == "prd" ? "datascience-production" : "datascience-homolog"
                def host = env == "prd" ? "${serviceName}.nexadigital.com.br" : "${serviceName}-${env}.nexadigital.com.br"

                // ** BUILD AND PUBLISH **
                if (env == "hmg" || env == "prd") {
                    stage('Build and Publish'){
                        container('docker') {
                            stage('Build and publish image') {
                                // sh "echo commitID=${tag} > digestor/.env"
                                sh "sed -i s/{{stack}}/${stack}/g  Dockerfile"
                                sh "sed -i s/{{commitID}}/${tag}/g  Dockerfile"
                                docker.withRegistry(registryConfig.url, registryConfig.credentials) {
                                    docker
                                    .build(imageName)
                                    // sh "docker build -t ${imageName}"
                                 //Container Image Scanner
                                 sh """
                                    docker save ${imageName} -o ${imageName}.tar && \
                                    apk add curl git && \
                                    wget https://github.com/aquasecurity/trivy/releases/download/v0.1.6/trivy_0.1.6_Linux-64bit.tar.gz && \
                                    tar zxvf trivy_0.1.6_Linux-64bit.tar.gz && \
                                    mv trivy /usr/local/bin && \
                                    trivy --exit-code 0 --severity CRITICAL --no-progress --auto-refresh  --input ${imageName}.tar && \
                                    docker tag ${imageName} ${registryConfig.host}/${imageName} && \
                                    docker push ${registryConfig.host}/${imageName}
                                    """
                                }
                            }
                        }
                    }
                    // ** DEPLOY TO PRODUCTION **
                    stage("Deploy properties") {
                        def imageUrl = "${registryConfig.host}/${imageName}"
                        def deployData = [
                        application: serviceName,
                        imageUrl: imageUrl,
                        namespace: namespace,
                        stack: stack,
                        host: host,
                        min: min,
                        max: max,
                        team: "datascience",
                        jobname: JOB_NAME,
                        author: getUserCommit(), 
                        commit: getGitCommit(), 
                        branch: branch, 
                        commitTag: getTagCommit(), 
                        repoUrl: getRepoUrl()
                        ]
                        archiveDeployTriggerYaml name: 'deploy.yml', data: deployData
                        //jobCounter(stack: "datascience", author: getUserCommit(), commit: getGitCommit(), branch: args.branch, commitTag: getTagCommit(), repoUrl: getRepoUrl())
                    }
                }
            }
        }
    }
  }
}
