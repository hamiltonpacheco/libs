def call() {
  sh(script: "git tag -l |tail -1", returnStdout: true).trim()
}