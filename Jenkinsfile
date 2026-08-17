// Pipeline for storage-manager. Lives at the repo root so Jenkins' "Pipeline from SCM"
// can pick it up without extra config. archiveArtifacts globs each version's jar
// separately (no combined zip).
//
// Triggers via the Gitea plugin (installed + active on Jenkins). Requires
// `Manage Jenkins > System > Gitea servers` to have an entry pointing at
// http://192.168.8.76:3030 with the `gitea-storage-manager` credentials
// and "Manage hooks" enabled. Once configured, pushes trigger builds
// instantly and PRs get ✓/✗ status reported back to Gitea.

pipeline {
    agent any

    options {
        timestamps()
        // Keep build history tight; per-version jars are archived per-build anyway.
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    triggers {
        gitea(events: ['push', 'pull_request'])
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