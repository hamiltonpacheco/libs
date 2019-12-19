import groovy.json.JsonSlurperClassic
import groovy.json.JsonSlurper

def call(Map args){
    def containers = [
        containerTemplate(name: 'ruby', image: 'ruby:2.5', ttyEnabled: true)
    ]
    def name = "JobCreateRepo"

    def registryConfig = [
        host: '454706396284.dkr.ecr.sa-east-1.amazonaws.com',
        url: "https://454706396284.dkr.ecr.sa-east-1.amazonaws.com",
        credentials: "ecr:sa-east-1:add8ebd8-8c84-4ddb-a72c-3dc70cbf754d"
    ]

    def label = "job-${name}-${UUID.randomUUID().toString()}".take(63)
    def jenkinsCredentialsId = "c1cc47da-e3db-40e6-bbe9-f7fdb38b04aa"
    
    def volumes = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]
    def repoName = args.RepoName
    def groupID = args.GroupID
    def webhookUrl = args.HostWebHook
    
    def getRepoID = { json ->
        def jsonSlurper = new JsonSlurper() 
        def resultJson = jsonSlurper.parseText(json)
        return resultJson["result"]["id"]
    }
    
    podTemplate(label: label, containers: containers, volumes: volumes, serviceAccount: 'jenkins') {

        node(label) {
            container('ruby'){
                stage('Setting up Environment'){
                    sh 'gem install gitlab'
                    sh 'apt update -y && apt install git -y'
                    sh 'curl -LO https://storage.googleapis.com/spinnaker-artifacts/spin/$(curl -s https://storage.googleapis.com/spinnaker-artifacts/spin/latest)/linux/amd64/spin'
                    sh 'chmod +x spin'
                    sh 'mv spin /usr/local/bin/spin'
                    sh 'mkdir -p ~/.spin'
                }
                stage('Create Repo and Integration'){
                    def projectJson = sh(script: "gitlab create_project \'${repoName}\' \"{ namespace_id: \'${groupID}\', visibility: \'private\' }\" --json", returnStdout: true).trim()
                    def repoID = getRepoID(projectJson)
                    if(!webhookUrl) {
                        println("Url was not given")
                    } else {
                        sh(script: "gitlab add_project_hook ${repoID} \'${webhookUrl}\' \"{ push_events: \'1\'}\" --json", returnStdout: true).trim()
                    }
                }
            }
        }
    }
}

// GroupID's
// 04 - livia/
// 05 - livia/patient/
// 06 - livia/tools/
// 14 - livia/providers/
// 31 - data/
// 32 - livia/technical-debts/
// 66 - nexa/cloud-health
// 74 - livia/professionals/
// 95 - data/gsc/