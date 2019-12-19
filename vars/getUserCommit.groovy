def call() {
  sh(script: "git show -s --pretty=%an", returnStdout: true).trim()
}