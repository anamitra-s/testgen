pipeline {
  agent { label 'testgen-worker' }
  options {
    timeout(time: 20, unit: 'MINUTES')
    disableConcurrentBuilds(abortPrevious: false)   // idempotency handles it, but reduces noise
    buildDiscarder(logRotator(numToKeepStr: '200'))
  }
  parameters {
    string(name: 'RECORDING_BRANCH',       description: 'Scratch branch holding the recording')
    string(name: 'TARGET_AREA',            description: 'dashboard | composer | statsimport')
    string(name: 'TEST_DESCRIPTION',       description: 'What the test verifies')
    string(name: 'REQUESTER_GITLAB_USER',  description: 'GitLab username to assign the MR to')
    string(name: 'IDEMPOTENCY_KEY',        description: 'Computed by the CLI — do not edit')
  }
  stages {
    stage('Checkout') {
      steps {
        git branch: 'main', url: env.ORCHESTRATOR_REPO_URL ?: 'https://gitlab.example.com/qa/testgen-orchestrator.git'
        sh "git clone --depth 1 --branch ${params.RECORDING_BRANCH} ${env.TEST_REPO_URL} recording"
      }
    }
    stage('Run pipeline') {
      steps {
        withCredentials([
          string(credentialsId: 'anthropic-api-key', variable: 'ANTHROPIC_API_KEY'),
          string(credentialsId: 'gitlab-bot-token',  variable: 'GITLAB_TOKEN'),
          usernamePassword(credentialsId: 'testgen-db', usernameVariable: 'TESTGEN_DB_USER',
                                                        passwordVariable: 'TESTGEN_DB_PASSWORD')
        ]) {
          script {
            def rc = sh(returnStatus: true, script: """
              java -jar target/testgen-orchestrator.jar run \\
                --recording-dir=recording \\
                --recording-branch=${params.RECORDING_BRANCH} \\
                --target-area=${params.TARGET_AREA} \\
                --description='${params.TEST_DESCRIPTION}' \\
                --requester=${params.REQUESTER_GITLAB_USER} \\
                --idempotency-key=${params.IDEMPOTENCY_KEY}
            """)
            // Read MR URL from job-summary.json before any cleanup
            if (fileExists('job-summary.json')) {
              try {
                def summary = readJSON file: 'job-summary.json'
                env.TESTGEN_MR_URL = summary.mrUrl ?: 'N/A'
              } catch (Exception e) {
                echo "Failed to parse job-summary.json: ${e.message}"
              }
            }
            // Exit-code contract (§9.2)
            if (rc in [10, 50])       currentBuild.result = 'UNSTABLE'
            else if (rc in [0,20,41]) currentBuild.result = 'SUCCESS'
            else                      error("testgen exited ${rc}")
          }
        }
      }
    }
  }
  post {
    always {
      archiveArtifacts artifacts: 'job-summary.json', allowEmptyArchive: true
      cleanWs()                                   // NFR-17: wipe session material
    }
    success   { slackSend channel: '#qa-automation', color: 'good',
                          message: "✅ Test-gen MR ready: ${readMrUrl()}" }
    unstable  { slackSend channel: '#qa-automation', color: 'warning',
                          message: "⚠️ Test-gen needs review: ${readMrUrl()}" }
    failure   { slackSend channel: '#qa-automation', color: 'danger',
                          message: "🔴 Test-gen failed (${params.TARGET_AREA}): ${env.BUILD_URL}" }
  }
}

def readMrUrl() {
  if (env.TESTGEN_MR_URL) {
    return env.TESTGEN_MR_URL
  }
  if (fileExists('job-summary.json')) {
    try {
      def summary = readJSON file: 'job-summary.json'
      return summary.mrUrl ?: 'N/A'
    } catch (Exception ignored) {}
  }
  return 'N/A'
}
