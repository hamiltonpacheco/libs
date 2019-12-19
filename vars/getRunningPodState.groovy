def call(Map args) {
  try {
    def podResultJSON = sh(
      script: "kubectl get pods -l \"app=${args.app}\" -n ${args.namespace} -o json",
      returnStdout: true
    ).trim()

    if ("".equals(podResultJSON)) return [:]

    def podResult = readJSON text: podResultJSON
    if (podResult == null) return [:]
    
    def container = podResult.items.get(0).spec.containers.get(0)

    def state = [
      imageUrl: container.image,
      environment: container.env.inject([:], { acc, curr ->
        acc.put curr.name, curr.value
        return acc
      })
    ]
    return state
  } catch (Exception e) {
    echo "Failed due to " + e.message
    return null
  }
}