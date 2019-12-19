def call(Map args){
    def containers = [
        containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:latest', command: 'cat', ttyEnabled: true)
    ]
    def name = "jenkinsBackup"

    def registryConfig = [
        host: '454706396284.dkr.ecr.sa-east-1.amazonaws.com',
        url: "https://454706396284.dkr.ecr.sa-east-1.amazonaws.com",
        credentials: "ecr:sa-east-1:add8ebd8-8c84-4ddb-a72c-3dc70cbf754d"
    ]
    
    def label = "job-${name}-${UUID.randomUUID().toString()}".take(63)
    def jenkinsCredentialsId = "c1cc47da-e3db-40e6-bbe9-f7fdb38b04aa"
    
    def volumes = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]

    podTemplate(label: label, containers: containers, volumes: volumes, serviceAccount: 'jenkins') {

        node(label) {
            container('kubectl'){
                def jenkinsPod = sh(script: "kubectl get pods -n jenkins  -o=jsonpath='{.items[0].metadata.name}'", returnStdout: true).trim()
                stage('Prepare for backup'){
                    sh "kubectl exec -it -n jenkins ${jenkinsPod} -- apt update -y"
                    sh "kubectl exec -it -n jenkins ${jenkinsPod} -- apt install python-pip -y"
                    sh "kubectl exec -it -n jenkins ${jenkinsPod} -- pip install awscli"
                }
                stage('Incremental Backup'){
                    sh "kubectl exec -it -n jenkins ${jenkinsPod} -- aws s3 sync /var/jenkins_home/ s3://jenkins-backup-nexa/backup_sync"
                }
                stage('Today Backup'){
                    def date = sh(script: "TZ='America/Sao_Paulo' date +%Y%m%d%H%M%S", returnStdout: true).trim()
                    sh "kubectl exec -it -n jenkins ${jenkinsPod} -- aws s3 cp --recursive /var/jenkins_home/ s3://jenkins-backup-nexa/${date}"
                }
            }
        }
    }
}