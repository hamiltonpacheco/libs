def call(Map args) {
  properties([gitLabConnection('Gitlab')])
    def serviceName = args.name
    def branch = args.branch

    def containers = [
    // containerTemplate(name: 'ansible', image: '454706396284.dkr.ecr.us-east-1.amazonaws.com/ansible:v2', ttyEnabled: true),
      containerTemplate(name: 'ubuntu', image: 'ubuntu:bionic', ttyEnabled: true)
    ]

    // def volumes = [hostPathVolume(hostPath: '/var/run/', mountPath: '/var/run/')]    def volumes = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]
    def volumes = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]

    def label = "job-${serviceName}-${UUID.randomUUID().toString()}".take(63)

    def jenkinsCredentialsId = "ssh-jenkins"
    println(branch)
    def masterBranch = branch == 'master'

    podTemplate(label: label, containers: containers, volumes: volumes, serviceAccount: 'jenkins') {

    node(label) {
        notifyStatus {
            gitlabCommitStatus(name: currentBuild.fullDisplayName) {
                container('ubuntu') {
                    stage('Checkout and Clone Repo Libs') {
                        checkout scm
                    }
                    stage('Configure Nexus Twine Repository') {
                        sh """
                        #!/bin/sh
                        cat << EOF > ~/.pypirc
                        [distutils]
                        index-servers=
                            Nexus
                        [Nexus]
                        repository: http://nexus-repo.nexadigital.com.br/repository/python-private/
                        username: test.nexus
                        password: test123
                        """
                    }
                    stage('Configure Nexus Nexa Pip Repository') {
                        sh "mkdir -p ~/.config/pip"
                        sh """
                        #!/bin/sh
                        cat << EOF > ~/.config/pip/pip.conf
                        [global]
                        trusted-host = nexus-repo.nexadigital.com.br
                                       urllib3.readthedocs.io
                        index-url = http://test.nexus:test123@nexus-repo.nexadigital.com.br/repository/py-internal/simple
                        """
                    }
                    stage('Configure AWS default Region') {
                        sh "mkdir ~/.aws"
                        sh """
                        #!/bin/sh
                        cat << EOF > ~/.aws/config
                        [default]
                        region=sa-east-1
                        """
                    }
                    stage('Prepare Enviroment'){
                        sh "apt-get update && \
                            apt-get install -y software-properties-common && \
                            add-apt-repository -y ppa:cran/poppler && \
                            apt-get install -y poppler-utils=0.74.0-bionic0 \
                            libsm6 \
                            python3 \
                            unzip \
                            wget \
                            python3-pip \
                            libssl-dev"
                        sh  "pip3 install -e ${WORKSPACE}" 
                        // sh  "pip3 install pytest"
                        // sh  "pip3 install pytest-cov"
                        // sh  "pip3 install twine"
                        sh  "pip3 install .[testing]"  
                        }

                    if (serviceName != "py-models") {    
                        stage('Running Tests'){
                            sh "pytest test --doctest-modules"
                            }
                        stage('Generating Coverage Tests Report'){
                            sh "pytest --cov-report xml:cobertura.xml --cov=${serviceName} test"
                            }
                    }
                    stage('SonarQube analysis') {
                        def sonar_path = readFile file: "sonar-project.properties", encoding: "UTF-8"
                        sonar_path = sonar_path.replace("{{workspace}}", "${WORKSPACE}")
                        writeFile file: "sonar-project.properties", text: sonar_path, encoding: "UTF-8"
                        sh "wget https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-3.3.0.1492-linux.zip"
                        sh "unzip sonar-scanner-cli-3.3.0.1492-linux.zip"
                        sh "${WORKSPACE}/sonar-scanner-3.3.0.1492-linux/bin/sonar-scanner -Dproject.settings=${WORKSPACE}/sonar-project.properties"
                        }
                    stage('Check quality: Quality Gate Status') {
                        def qualityGateStatus = getQualityGateStatus(["."])
                        if (!qualityGateStatus) {
                        error "Failed because at least one version did not pass the Quality Gate"
                        } else {
                        echo "Passed Quality Gate check"
                        }
                    }
                    println(masterBranch)
                    if (masterBranch) {
                        stage('Creating Package'){
                            sh "python3 setup.py sdist bdist_wheel"
                            }    
                        stage('Publish Package to Nexus Repository'){
                            sh "twine upload -r Nexus dist/*"
                            }
                        }
                    }
                }
                jobCounter(jobname: env.JOB_NAME, stack: "datascience", author: getUserCommit(), commit: getGitCommit(), branch: args.branch, commitTag: getTagCommit() , repoUrl: getRepoUrl())
            }
        }
    }
}