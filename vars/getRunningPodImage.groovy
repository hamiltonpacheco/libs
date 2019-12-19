def call(Map args) {
  try {
    return sh(
      script: "kubectl get pods \"--context=${args.context}\" -l \"app=${args.app}\" -n ${args.namespace} -o jsonpath={.items[0].spec.containers[0].image}",
      returnStdout: true
    ).trim()
  } catch (Exception e) {
    echo "Failed due to " + e.message
    return null
  }
  
}