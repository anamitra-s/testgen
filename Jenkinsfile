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
    string(name: 'TESTGEN_LLM_PROVIDER',   defaultValue: 'anthropic', description: 'anthropic | gemini | mock')
  }
  stages {
    stage('Checkout') {
      steps {
        git branch: 'main', url: env.ORCHESTRATOR_REPO_URL ?: 'https://gitlab.example.com/qa/testgen-orchestrator.git'
        withEnv([
          "PARAM_RECORDING_BRANCH=${params.RECORDING_BRANCH}",
          "PARAM_TEST_REPO_URL=${env.TEST_REPO_URL}"
        ]) {
          sh '''
            git clone --depth 1 --branch "$PARAM_RECORDING_BRANCH" "$PARAM_TEST_REPO_URL" recording
            git clone --depth 1 --branch main "$PARAM_TEST_REPO_URL" test-workspace
          '''
        }
      }
    }
    stage('Run pipeline') {
      steps {
        withCredentials([
          string(credentialsId: 'anthropic-api-key', variable: 'ANTHROPIC_API_KEY'),
          string(credentialsId: 'gemini-api-key',    variable: 'GEMINI_API_KEY'),
          string(credentialsId: 'gitlab-bot-token',  variable: 'GITLAB_TOKEN'),
          string(credentialsId: 'testgen-db-url',    variable: 'TESTGEN_DB_URL'),
          usernamePassword(credentialsId: 'testgen-db', usernameVariable: 'TESTGEN_DB_USER',
                                                        passwordVariable: 'TESTGEN_DB_PASSWORD')
        ]) {
          withEnv([
            "PARAM_RECORDING_BRANCH=${params.RECORDING_BRANCH}",
            "PARAM_TARGET_AREA=${params.TARGET_AREA}",
            "PARAM_TEST_DESCRIPTION=${params.TEST_DESCRIPTION}",
            "PARAM_REQUESTER=${params.REQUESTER_GITLAB_USER}",
            "PARAM_IDEMPOTENCY_KEY=${params.IDEMPOTENCY_KEY}",
            "PARAM_PROVIDER=${params.TESTGEN_LLM_PROVIDER ?: 'anthropic'}"
          ]) {
            script {
              def rc = sh(returnStatus: true, script: '''
                export TESTGEN_LLM_PROVIDER="$PARAM_PROVIDER"
                export ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-}"
                export GEMINI_API_KEY="${GEMINI_API_KEY:-}"
                java -jar target/testgen-orchestrator.jar run \
                  --recording-dir=recording \
                  --workspace=test-workspace \
                  --recording-branch="$PARAM_RECORDING_BRANCH" \
                  --target-area="$PARAM_TARGET_AREA" \
                  --description="$PARAM_TEST_DESCRIPTION" \
                  --requester="$PARAM_REQUESTER" \
                  --idempotency-key="$PARAM_IDEMPOTENCY_KEY" \
                  --provider="$PARAM_PROVIDER"
              ''')
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
