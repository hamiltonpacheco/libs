def call(Map args) {
  properties([gitLabConnection('Gitlab')])
    def build = args.task
    def name = args.name
    def folderName = args.folderName
    def jobList = args.jobList
    def label = "job-${name}-${UUID.randomUUID().toString()}".take(63)
    def jenkinsCredentialsId = "c1cc47da-e3db-40e6-bbe9-f7fdb38b04aa"
    def USER="svc_jenkins"
    def TOKEN="11037bcd9a1017ed36e780abbca11fe9e6"
    def containers = [
        containerTemplate(name: 'openjdk', image: 'openjdk', command: 'cat', ttyEnabled: true)
    ]
    def volumes = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]
    podTemplate(label: label, containers: containers, volumes: volumes, serviceAccount: 'jenkins') {
        node(label) {
            stage('Start/Stop Jobs'){
                container('openjdk') {
                    sh 'yum install wget curl -y'
                    sh 'wget https://jenkins.nexadigital.com.br/jnlpJars/jenkins-cli.jar --no-check-certificate'
                    sh 'ls'
                    println(args)
                    if (build == "start") {
                        for (JOB in jobList) {
                            sh "java -jar jenkins-cli.jar -s http://jenkins.jenkins.svc.cluster.local:8080 -auth ${USER}:${TOKEN} stop-builds MobileCenter/${folderName}/${JOB}"
                            sleep(5)
                            sh "java -jar jenkins-cli.jar -s http://jenkins.jenkins.svc.cluster.local:8080 -auth ${USER}:${TOKEN} build MobileCenter/${folderName}/${JOB}"
                            println("Iniciando o job: ${JOB}")
                            sleep(25)
                        }
                    }
                    if (build == "stop") {
                        for (JOB in jobList) {
                            sh "java -jar jenkins-cli.jar -s http://jenkins.jenkins.svc.cluster.local:8080 -auth ${USER}:${TOKEN} stop-builds MobileCenter/${folderName}/${JOB}"
                            println("Parando o job: ${JOB}")
                        }
                    }
                }
            }
        }
    }
}