def call() {
  sh(script: "git remote -v|grep push|awk '{print \$2}'", returnStdout: true).trim()
}

