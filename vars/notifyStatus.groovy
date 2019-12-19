def call(Closure body) {
  try {
    // notifyBuild 'STARTED'
    body()
    // notifyBuild 'SUCCESSFUL'
  } catch (e) {
    notifyBuild 'FAILED'
    throw e
  }
}