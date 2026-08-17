// Pipeline for storage-manager. Lives at the repo root so Jenkins' "Pipeline from SCM"
// can pick it up without extra config. archiveArtifacts globs each version's jar
// separately (no combined zip).
//
// Triggers: pollSCM every 2 min until the Gitea plugin is enabled and restarted.
// Once it is, swap the triggers block for `gitea(events: ['push', 'pull_request'])`
// to get webhook speed and PR status reporting back to Gitea.

pipeline {
    agent any

    options {
        timestamps()
        // Keep build history tight; per-version jars are archived per-build anyway.
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    triggers {
        pollSCM('H/2 * * * *')
    }

    stages {
        stage('Build') {
            steps {
                sh './gradlew buildAndCollect --no-daemon'
            }
        }
    }

    post {
        success {
            // build/libs/1.0.0/ has one jar per version (storage-manager-1.0.0+1.21.8.jar,
            // +26.1.2.jar, +26.2.jar) plus matching sources jars. Glob keeps them
            // separate artifacts in the build, not a zip.
            archiveArtifacts artifacts: 'build/libs/1.0.0/*.jar',
                             fingerprint: true,
                             allowEmptyArchive: false
        }
    }
}