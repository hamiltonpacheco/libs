def call() {
  sh(script: "git log -n 1 --pretty=format:'%H'", returnStdout: true).trim()
}