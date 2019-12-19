def call(Map args) {
  writeYaml file: args.name, data: args.data
  archiveArtifacts artifacts: args.name
}