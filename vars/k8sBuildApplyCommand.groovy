def call(Map args) {
  def files = args.files
  def manifestArguments = files.collect({ "-f ${it}" }).join(" ")
  return "kubectl apply ${manifestArguments}"
}