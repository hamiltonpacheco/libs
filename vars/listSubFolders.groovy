def call(String path = "./") {
  sh(script: "ls -d ${path}", returnStdout: true)
    .trim()
    .split(System.getProperty("line.separator"))
}
