// Pipeline for storage-manager. Lives at the repo root so Jenkins' "Pipeline from SCM"
// can pick it up without extra config. archiveArtifacts globs each version's jar
// separately (no combined zip).
//
// pollSCM trigger as a placeholder until the Gitea plugin's job-level trigger
// is wired up. The gitea plugin v282 doesn't ship a pipeline DSL `gitea()`
// trigger — it goes in the job config XML directly. We'll move it there.

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
        stage('Release') {
            // Only on tag builds — git describe --exact-match HEAD succeeds iff HEAD is a tag.
            when {
                expression {
                    sh(script: 'git describe --tags --exact-match HEAD', returnStatus: true) == 0
                }
            }
            steps {
                withCredentials([string(credentialsId: 'gitea-token', variable: 'GITEA_TOKEN')]) {
                    sh 'GITEA_URL=http://192.168.8.76:3030 GITEA_REPO=UwUtismXD/storage-manager python3 scripts/release.py'
                }
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