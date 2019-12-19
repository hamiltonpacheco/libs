import groovy.json.JsonSlurperClassic
import groovy.json.JsonSlurper
import hudson.model.*

def call(Map args){
    def containers = [
    containerTemplate(name: 'ruby', image: 'ubuntu:latest', ttyEnabled: true)
    ]
    def name = "CreateStructure"
    def jenkinsHook = "http://jenkins.nexadigital.com.br/project"
    def userJenkins = "svc_jenkins"
    

    def volumes = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]
    def repoName = args.RepoName
    def groupID = args.GroupID
    def templateLib = args.TemplateLib
    def templateXML = args.TemplateXML
    def registryConfig = [
    host: '454706396284.dkr.ecr.sa-east-1.amazonaws.com',
    url: "https://454706396284.dkr.ecr.sa-east-1.amazonaws.com",
    credentials: "ecr:sa-east-1:add8ebd8-8c84-4ddb-a72c-3dc70cbf754d"
    ]

    def label = "job-${name}-${UUID.randomUUID().toString()}".take(63)
    def jenkinsCredentialsId = "c1cc47da-e3db-40e6-bbe9-f7fdb38b04aa"
    def projectJson = ""
    def repoID = ""
    def getRepoID = { json ->
        def jsonSlurper = new JsonSlurper() 
        def resultJson = jsonSlurper.parseText(json)
        return resultJson["result"]["id"]
    }
    def pathWithNamespace = ""
    def path = ""
    def getPathWithNamespace = { json ->
        def jsonSlurper = new JsonSlurper() 
        def resultJson = jsonSlurper.parseText(json)
        return resultJson["result"]["path_with_namespace"]
    }
    
    podTemplate(label: label, containers: containers, volumes: volumes, serviceAccount: 'jenkins') {

        node(label) {
            container('ruby'){
                stage('Setting up Environment'){
                    sh 'apt update -y && apt install curl ruby git default-jdk -y'
                    sh 'gem install gitlab'
                    // sh 'set +x && export GITLAB_API_ENDPOINT=http://gitlab.nexadigital.com.br/api/v4 && set-x'
                    // sh 'set +x && export GITLAB_API_PRIVATE_TOKEN=xJ3ipVqLMyMZibstXLy_ && set-x'
                    sh 'curl -LO https://storage.googleapis.com/spinnaker-artifacts/spin/$(curl -s https://storage.googleapis.com/spinnaker-artifacts/spin/latest)/linux/amd64/spin'
                    sh 'chmod +x spin'
                    sh 'mv spin /usr/local/bin/spin'
                    sh 'mkdir -p ~/.spin'
                    sh 'curl -s http://jenkins:8080/jnlpJars/jenkins-cli.jar -o jenkins-cli.jar'
                }
                stage('Create Repo and Integration'){
                    projectJson = sh(script: "gitlab create_project \'${repoName}\' \"{ namespace_id: \'${groupID}\', visibility: \'private\' }\" --json", returnStdout: true).trim()
                    repoID = getRepoID(projectJson)
                }
                stage('Cloning and Pushing Templates'){
                    dir("templates") {
                        checkout([$class: 'GitSCM', branches: [[name: '*/master']],
                        userRemoteConfigs: [[url: 'git@gitlab.nexadigital.com.br:sre/templates.git',credentialsId:'jenkins']]])
                    }
                    sh 'mv templates/config-spin ~/.spin/config'
                }
                stage('Replace and Push'){
                    dir("templates"){
                        pathWithNamespace = sh(script: "gitlab project ${repoID} --only=path_with_namespace --json", returnStdout: true).trim()
                        path = getPathWithNamespace(pathWithNamespace)
                        def pipeline = readFile file: "pipeline.json", encoding: "UTF-8"
                        pipeline = pipeline.replace("{{artifactID}}", "${UUID.randomUUID().toString()}".take(24))
                        pipeline = pipeline.replace("{{repoName}}", "${repoName}")
                        pipeline = pipeline.replace("{{repoID}}", "${repoID}")
                        pipeline = pipeline.replace("{{jobName}}", "${path}")
                        writeFile file: "pipeline.json", text: pipeline, encoding: "UTF-8"
                    }
                    dir("templates"){
                        def jenkinsfile = readFile file: "Jenkinsfile", encoding: "UTF-8"
                        jenkinsfile = jenkinsfile.replace("{{repoName}}", "${repoName}")
                        jenkinsfile = jenkinsfile.replace("{{templateLib}}", "${templateLib}")
                        writeFile file: "Jenkinsfile", text: jenkinsfile, encoding: "UTF-8"
                        sh "mv kubernetes/application kubernetes/${repoName}"
                        sh "cp files/* kubernetes/${repoName}/"
                    }
                    dir("templates"){
                        sh "mkdir -p ~/.ssh"
                        sh "cp id_rsa ~/.ssh/"
                        sh "cp id_rsa.pub ~/.ssh/"
                        sh "chmod 400 ~/.ssh/id_rsa"
                        sh "/bin/echo -e \"Host gitlab.nexadigital.com.br\n StrictHostKeyChecking no\n\" > ~/.ssh/config"
                        sh "cat ~/.ssh/config"
                        sh """
                            rm -rf .git
                            git init
                            git remote add origin git@gitlab.nexadigital.com.br:${path}.git
                            git config --global user.email \'webmaster@nexadigital.com.br\'
                            git config --global user.name \'webmaster-jenkins\'
                            git add --all
                            git commit -m \'first commit\'
                            git push origin master
                        """
                    }
                }
                stage('Replace Jenkins XML'){
                    dir("templates"){
                        def jenkinsXML = readFile file: "multibranch.xml"
                        jenkinsXML = jenkinsXML.replace("{{path}}", "${path}")
                        writeFile file: "multibranch.xml", text: jenkinsXML
                    }
                    println(path)
                }
                // em time que está ganhando, não se mexe.
                stage('Create Jenkins Job'){
                    if(path.contains('nexa/')){
                        // path.replace('nexa/', 'cloud-health/')
                        path = "cloud-health/${repoName}"
                        println(path)
                    }
                    if(path.contains('data/')){
                        // path.replace('data', 'DataScience')
                        path = "DataScience/${repoName}"
                        println(path)
                    }
                    withCredentials([string(credentialsId: 'svc_jenkins', variable: 'token')]) {
                        sh """
                            java -jar jenkins-cli.jar -s http://jenkins:8080/ -auth ${userJenkins}:${token} create-job ${path} < ${WORKSPACE}/templates/${templateXML}
                        """
                    }    
                }
                stage('Add Webhook GitLab'){
                        sh(script: "gitlab add_project_hook ${repoID} \'${jenkinsHook}/${path}\' \"{ push_events: \'1\'}\" --json", returnStdout: true).trim()
                }
                stage('Creating Spinnaker Application'){
                    dir("templates"){
                        sh "spin -k application save --application-name ${repoName} --owner-email webmaster@nexadigital.com.br --cloud-providers kubernetes"
                        timeout(time: 10, unit: 'MINUTES') {
                            waitUntil {
                                script {
                                    def r = sh script: 'spin -k pi save -f pipeline.json', returnStatus: true
                                    return "Pipeline save succeeded".equals(r)
                                }   
                            }
                        }
                    }
                }
            }
        }
    }
} 