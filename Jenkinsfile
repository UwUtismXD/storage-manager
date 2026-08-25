// Pipeline for storage-manager. Lives at the repo root so Jenkins' "Pipeline from SCM"
// can pick it up without extra config. archiveArtifacts globs each version's jar
// separately (no combined zip).
//
// Triggers: two jobs share this Jenkinsfile. Minecraft/storage-manager-master
// builds only master; Minecraft/storage-manager-tags builds only refs/tags/v*.
// Gitea webhooks fire each job's build URL directly (no SCM polling, no
// branch/tag spec ambiguity — that's the bug this split avoids).
//
// Release stage is gated on `git describe --exact-match HEAD`, which succeeds
// only when HEAD is a tag → tag builds publish, master builds skip.

pipeline {
    agent any

    options {
        timestamps()
        // Keep build history tight; per-version jars are archived per-build anyway.
        buildDiscarder(logRotator(numToKeepStr: '20'))
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
                withCredentials([usernamePassword(credentialsId: 'gitea-storage-manager', usernameVariable: 'GITEA_USER', passwordVariable: 'GITEA_TOKEN')]) {
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
