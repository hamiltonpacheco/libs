def call(String buildName, Closure body) {
  gitlabCommitStatus {
    try {
      updateGitlabCommitStatus name: buildName, state: 'pending'
      body()
      updateGitlabCommitStatus name: buildName, state: 'success'
    } catch (e) {
      updateGitlabCommitStatus name: buildName, state: 'failed'
      throw e
    }
  }
}