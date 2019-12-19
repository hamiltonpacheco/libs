import groovy.json.JsonSlurperClassic
import groovy.json.JsonSlurper

def call(Map args){
    def containers = [
        containerTemplate(name: 'client-jenkins', image: 'java:latest', ttyEnabled: true)
    ]
    def name = "JobCreateJob"

    def registryConfig = [
        host: '454706396284.dkr.ecr.sa-east-1.amazonaws.com',
        url: "https://454706396284.dkr.ecr.sa-east-1.amazonaws.com",
        credentials: "ecr:sa-east-1:add8ebd8-8c84-4ddb-a72c-3dc70cbf754d"
    ]
    
    def label = "job-${name}-${UUID.randomUUID().toString()}".take(63)
    def jenkinsCredentialsId = "c1cc47da-e3db-40e6-bbe9-f7fdb38b04aa"
    
    def volumes = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]

    podTemplate(label: label, containers: containers, volumes: volumes, serviceAccount: 'jenkins') {

		def userJenkins = "svc_jenkins"
		def tokenJenkins = "11527ac8e3d4648f75e37f3a640e220be4"

        node(label) {
            container('client-jenkins'){
                def newJobName = args.newJobName
                stage('Setting up Environment'){
                    sh 'uname -a'
                }
                stage('Cloning template XML'){
                    dir("template") {
                        checkout([$class: 'GitSCM', branches: [[name: '*/master']],
                        userRemoteConfigs: [[url: 'git@gitlab.nexadigital.com.br:sre/pipeline-lib.git',credentialsId:'jenkins']]]) 
                    }
                    sh """
                        curl -s http://jenkins:8080/jnlpJars/jenkins-cli.jar -o jenkins-cli.jar 
                        java -jar jenkins-cli.jar -s http://jenkins:8080/ -auth ${userJenkins}:${tokenJenkins} list-jobs
                        java -jar jenkins-cli.jar -s http://jenkins:8080/ -auth ${userJenkins}:${tokenJenkins} create-job ${newJobName} < ${WORKSPACE}/template/createRepo/template.xml
                        java -jar jenkins-cli.jar -s http://jenkins:8080/ -auth ${userJenkins}:${tokenJenkins} list-jobs
                    """
                }
            }
        }
    }
}