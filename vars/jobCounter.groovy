import hudson.model.*

def call(Map args){
    def containers = [
    containerTemplate(name: 'counter', image: 'alpine', ttyEnabled: true)
    ]

    def volumes = [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')]

    def registryConfig = [
    host: '454706396284.dkr.ecr.sa-east-1.amazonaws.com',
    url: "https://454706396284.dkr.ecr.sa-east-1.amazonaws.com",
    credentials: "ecr:sa-east-1:add8ebd8-8c84-4ddb-a72c-3dc70cbf754d"
    ]

    def label = "job-${UUID.randomUUID().toString()}".take(63)
    def env_mode = ""

    // if(env.GIT_AUTHOR_NAME == null){
    //     author = currentBuild.rawBuild.getCause(Cause.UserIdCause).getUserId() //when user triggers it manually
    // } else {
    //     author = env.GIT_AUTHOR_NAME //when user triggers it from gitlab
    // }

    //author=$(git show -s --pretty=%an)
    // sh "echo ${author}"
    
    def jenkinsCredentialsId = "c1cc47da-e3db-40e6-bbe9-f7fdb38b04aa"
    
    podTemplate(label: label, containers: containers, volumes: volumes, serviceAccount: 'jenkins') {

        node(label) {
            container('counter'){
                def buildtime = new Date().format("dd-MM-yyyy HH:mm:ss", TimeZone.getTimeZone('GMT-3')).toString()
                def stack = args.stack
                def author = args.author
                def branch = args.branch
                def commit = args.commit
                def commitTag = args.commitTag
                def repoUrl = args.repoUrl
                def jobname = args.jobname
                // def psql_pass = args.psql_pass
                if(commitTag == ''){
                    commitTag = "no tag"
                }

                if(stack == 'backend'){
                    env_mode = (branch == 'master') ? 'PRD' : 'HMG'
                } else if(stack == 'datascience'){
                    env_mode = (branch == 'production') ? 'PRD' : 'HMG'
                } else if(stack == 'frontend' && branch == 'master'){
                    env_mode = 'HMG'
                }
                stage('run query') {
                    def qry_create = "CREATE TABLE IF NOT EXISTS \
                                    boardCount(jobName varchar(60) NOT NULL, \
                                    environment varchar(3) NOT NULL, \
                                    buildtime varchar(25) NOT NULL, \
                                    stack varchar(20) NOT NULL);"
                    def add_author = "ALTER TABLE boardcount ADD COLUMN IF NOT EXISTS Author VARCHAR(50);"
                    def add_commit = "ALTER TABLE boardcount ADD COLUMN IF NOT EXISTS commit VARCHAR(50);"
                    def add_branch = "ALTER TABLE boardcount ADD COLUMN IF NOT EXISTS branch VARCHAR(50);"
                    def add_timestamp = "ALTER TABLE boardcount ADD COLUMN IF NOT EXISTS timestamp TIMESTAMP(0) DEFAULT CURRENT_TIMESTAMP;"
                    def add_tag = "ALTER TABLE boardcount ADD COLUMN IF NOT EXISTS tag VARCHAR(30);"
                    def add_repo_url = "ALTER TABLE boardcount ADD COLUMN IF NOT EXISTS repo_url VARCHAR(100);"
                    sh """
                    #!/bin/sh
                    cat << EOF > /tmp/insert.sql
                    INSERT INTO boardCount (jobName, environment, buildtime, stack, author, commit, branch, tag, repo_url)
                    VALUES('${jobname}', '${env_mode}', '${buildtime}', '${stack}', '${author}', '${commit}', '${branch}', '${commitTag}', '${repoUrl}');
                    """
                    sh "apk add postgresql-client"
                    // sh "psql --version"
                    sh "cat /tmp/insert.sql"
                    sh "set +x && PGPASSWORD=${PGPASSWORD} psql -h job-count-postgresql.jenkins -U postgres -d board -c '${qry_create}' && set -x"
                    sh "set +x && PGPASSWORD=${PGPASSWORD} psql -h job-count-postgresql.jenkins -U postgres -d board -c '${add_author}' && set -x"
                    sh "set +x && PGPASSWORD=${PGPASSWORD} psql -h job-count-postgresql.jenkins -U postgres -d board -c '${add_timestamp}' && set -x"
                    sh "set +x && PGPASSWORD=${PGPASSWORD} psql -h job-count-postgresql.jenkins -U postgres -d board -c '${add_commit}' && set -x"
                    sh "set +x && PGPASSWORD=${PGPASSWORD} psql -h job-count-postgresql.jenkins -U postgres -d board -c '${add_branch}' && set -x"
                    sh "set +x && PGPASSWORD=${PGPASSWORD} psql -h job-count-postgresql.jenkins -U postgres -d board -c '${add_tag}' && set -x"
                    sh "set +x && PGPASSWORD=${PGPASSWORD} psql -h job-count-postgresql.jenkins -U postgres -d board -c '${add_repo_url}' && set -x"
                    sh "set +x && PGPASSWORD=${PGPASSWORD} psql -h job-count-postgresql.jenkins -U postgres -d board -f /tmp/insert.sql && set -x"
                }
            }
        }
    }
}