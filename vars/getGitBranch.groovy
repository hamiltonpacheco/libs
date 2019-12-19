def call() {
  sh(script: "git branch | grep ^'*'", returnStdout: true).trim()
}